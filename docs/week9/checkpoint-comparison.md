# 멱등 Writer와 checkpoint의 역할 비교

실제 MySQL·Spring Batch 5.2.2에서 같은 JobInstance의 재시작과 새 JobInstance의 전체 재처리를 비교했다.
전체 실행 ID와 실제 로그는
[verification-results.json](verification-results.json), 종합 결과는 [검증 보고서](verification-report.md)에 보관한다.

## 조건

입력은 UTC `[2026-08-17, 2026-08-24)`의 네 활동이다.
`(42,7,2)`, `(42,8,1)`, `(43,7,3)`, `(44,9,5)`이며 reader item은 상품7/8/9 세 개다.
상품별 frozen 수량 합과 절대값 upsert, PK(snapshotId,productId), chunk=2를 사용한다.
실행 ID와 snapshotId를 내용 checksum에서 제외하고, 입력/결과 순서와 TAB/LF/UTC microsecond 형식은 고정했다.

비교 Job은 [CheckpointComparisonExperiment](../../apps/commerce-batch/src/test/kotlin/com/loopers/ranking/CheckpointComparisonExperiment.kt)에만
있다. snapshot을 실험별로 분리한다. 같은 실험의 실패와 다음 실행 사이에는 DB·frozen 입력·stage·Batch metadata를
지우지 않는다. production weeklyRanking의 parameter 정책을 바꾸지 않고 공개 pointer도 변경하지 않는다.

## 관찰

| 실험 | 실패 시 영속 reader 위치 / stage | 후속 실행의 processor 호출 | 후속 결과 |
|---|---|---|---|
| A 정상 | 실패 없음 | 7,8,9 | 7=5, 8=1, 9=5 |
| B 같은 instance 재시작 | comparisonProducts.read.count=2 / 7=5,8=1 | 9 | A와 같은 점수 |
| C 새 비교 instance 전체 재처리 | count=2 / 7=5,8=1 유지 | 7,8,9 | 행 수3, A와 같은 점수 |
| D Writer 같은 절대값3회 반복 | C의 stage 유지 | processor 호출 없음 | 상품7은 1행, 5점 유지 |
| E SQL 후 chunk commit 전 실패 | count=0 / stage 없음 | 재시작에서 7,8,9 | A와 같은 점수 |

B와 C의 첫 실패 시 processor 호출 기록은 둘 다 7,8,9다. 마지막9는 계산 전에 실패한 시도다.
B 재시작은 같은 JobInstance에 새 JobExecution/StepExecution으로 실행됐고9만 계산했다.
C 후속 실행은 비교 parameter를 바꾼 별도 instance이므로7/8도 다시 계산했다.
C의 비교 parameter를 production 재시작에 사용하지 않는다.

완료 시 이 JdbcCursorItemReader의 저장 read.count는4로 관찰됐다. 상품은3개이며,
EOF 확인을 포함한 reader 내부 count를 상품 수나 productId로 해석하지 않는다.
실패 직후 영속값2와 재시작 호출 `[9]`를 함께 확인해야 checkpoint 복원을 입증할 수 있다.

내용 checksum은 A/B/C 모두 다음과 같다.

- 입력: `3e90276ec1f9123f308cd18d155e1dde6bf811ef3aa741105ff5c766c357a298`
- 결과: `745d50bbd2f04a2e8c937f9ebf5f2cddbf75b958540e964934a424903b0a67d2`
- 손계산 순위: 상품7=5점·1위, 상품9=5점·2위, 상품8=1점·3위.

비교 Job은 stage 계산에 집중한다. 결과 checksum은 각 stage를 결정적 순서로 순위화해서 비교했다.
별도로 실제 weeklyRanking 정상 실행과 실패/재시작 실행의 **저장된 완성 결과와 checksum**도 비교했다.

## 해석과 한계

이 조건에서 전체 재처리는 이미 commit한 상품도 다시 계산하지만 같은 결과에 수렴했다.
고정 입력은 재시작 중 도착한 구매로 계산 대상이 바뀌는 일을 막고, 절대값 writer와 유일 키는
같은 상품을 다시 쓸 때 점수·행 수가 누적되는 일을 막았다. checkpoint는 commit한 상품의 계산을
반복하지 않도록 했다. 결과 checksum만 같다는 사실은 checkpoint에서 재개했다는 증거가 아니다.

E는 SQL을 실행했다는 사실과 commit됐다는 사실의 차이도 보여준다. SQL 뒤 실패한 chunk의
stage와 reader 위치가 모두 rollback됐고, 그 상품들을 다시 처리했다. checkpoint는 commit 전
writer의 재실행을 없애지 않으므로 writer의 안전성을 대신하지 않는다.

처리 상품3개의 호출 경로를 비교한 실험이다. 소요시간이나 대규모 처리량 개선을 측정하지 않았다.
계산식 변경/외부 부수 효과/비결정적 입력까지 안전하다는 주장은 하지 않는다. 후보2의 프로세스
강제 종료와 후보3의 코드 버전 교체 실험도 이번 비교에 포함하지 않았다.
