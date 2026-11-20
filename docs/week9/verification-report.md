# W9 구현·검증 결과

2026-11-18, 미커밋 작업 트리 기준이다. [설계](backend-design.md), [실행 방법](README.md),
[후보1 비교 실험](checkpoint-comparison.md), [구조화한 실제 증거](verification-results.json)를 함께 읽는다.

## Git 대조와 변경

브랜치 `jsjouo249`, HEAD `af7e088917af9603cb0d1c2cb15e58bc507b9bf3`로 인계와 일치했다.
시작 당시 staged 변경은 없었고, 기존 AGENTS.md 13줄과 미추적 DS_Store·인계·writing 메모를 확인했다.
기존 사용자 변경을 보존했다. commit·push·PR 발행·배포·운영 데이터 변경은 하지 않았다.

W9 공유 도메인/저장 모듈, 실제 weeklyRanking Job, 단일 statement 공개 조회 API,
DDL, 도메인·MySQL·HTTP·E2E 테스트와 후보1 비교 실험을 추가했다.
필수 lint에서 드러난 기존 demo 코드의 쉼표/빈 블록 형식 오류만 두 파일에서 함께 수정했다.
API 회귀가 다시 쓴 W4 redis-outage 측정 수치는 작업 시작 전 내용으로 복원했다.
W8 쿠폰 구현은 이 checkout에 없으므로 재작성하거나 검증했다고 주장하지 않는다.

## 환경과 실행 결과

기본 Java는18이었다. 임시 디렉터리의 Temurin21.0.13+9로 검증했고 시스템 Java 설정은 바꾸지 않았다.
사용자가 Docker Desktop을 시작한 뒤 테스트 소유 MySQL8/Redis 컨테이너와 embedded Kafka를 사용했다.
PG는 production HTTP adapter가 호출하는 로컬 PG HTTP fixture다. 외부 운영 PG를 호출한 실험은 아니다.

실행한 필수 check:

```bash
./gradlew -Dorg.gradle.java.home=/tmp/jdk-21.0.13+9/Contents/Home :modules:ranking:check :modules:events:check :apps:commerce-batch:check :apps:commerce-api:check :apps:commerce-streamer:check --continue --console=plain
```

`BUILD SUCCESSFUL in 3m 49s`, exit0, 120 tasks 중26실행/94 up-to-date였다.
API·Batch·ranking 모듈의 ktlintCheck와 양쪽 ArchitectureTest가 통과했다.
이 명령에서 events/streamer test는 예전 산출물이 재사용됐으므로, 이후 별도 fresh 실행을 했다.
그 추가 streamer 검증에는 아래와 같이 미해결 실패가 있다. 전체 회귀가 새로 모두 통과했다고 해석하면 안 된다.

| 검사 | 테스트 | 실패 | 오류 | skip | 증거 |
|---|---:|---:|---:|---:|---|
| ranking domain | 4 | 0 | 0 | 0 | 이번 세션 실제 실행 후 check에서 재사용 |
| commerce-batch 전체 | 15 | 0 | 0 | 0 | fresh, RankingJobRestartTest 11 + Architecture 1 + 기존3 |
| commerce-api 전체 | 204 | 0 | 0 | 0 | fresh, 새 MockMvc3·Architecture 포함 |
| 기존 Outbox E2E | 2 | 0 | 0 | 0 | fresh, 실제 W6 fixture도 실행 |
| W9 ranking E2E | 1 | 0 | 0 | 0 | fresh |
| events | 3 | 0 | 0 | 0 | check 뒤 `test --rerun`으로 fresh 실행 |
| 추가 streamer fresh 실행 | 17 | 1 | 0 | 0 | exit1, 기존 listener 재시작 검사 실패 |
| streamer 추가 재실행 | 17 | 7 | 0 | 0 | exit1, Docker 소켓 부재로 MySQL context 로드 실패 |

성공한 위 여섯 그룹은229개다. W9 집중 검사21개(도메인4, Batch11, API3, E2E1, Architecture2)는 모두 통과했다.
Gradle의 `SKIPPED`/`NO-SOURCE` task 표시와 JUnit 테스트 skip은 다르다. 위 테스트 skip은 모두0이다.

## 실제 재시작 증거

영속 context는 JobExplorer로 다시 읽었고 업무 행은 별도 DB connection/query로 확인했다.
실패와 다음 launch 사이에는 DB·입력·stage·metadata를 초기화하지 않았다.

| 시나리오 | instance | 실패 execution / step | 재시작 execution / step | 영속 실패 위치 | 재시작 processor |
|---|---:|---|---|---|---|
| INSERT-only probe | 1 | 1 / 1 | 2 / 2 | purchaseProductProbe.read.count=2 | 9 |
| 실제 weeklyRanking | 10 | 14 / 21 | 15 / 22 | weeklyRankingProducts.read.count=2 | 9 |
| 후보1 B | 3 | 4 / 4 | 5 / 5 | comparisonProducts.read.count=2 | 9 |

probe는 `probe-1` 입력과 복합 PK 테이블을 사용했다. 새 reader·Job 객체로 launch해도
7/8을 다시 INSERT하지 않았고 최종7/8/9를 저장했다.
실제 Job의 실패 직후 stage는7=5/8=1이고 이전 공개 pointer를 유지했다.
재시작은 freeze Step을 반복하지 않았고, freeze 뒤 도착한 구매에도 기존 입력/checksum이 유지됐다.

고정 fixture의 입력 checksum:
`3e90276ec1f9123f308cd18d155e1dde6bf811ef3aa741105ff5c766c357a298`.
완성 결과 checksum:
`745d50bbd2f04a2e8c937f9ebf5f2cddbf75b958540e964934a424903b0a67d2`.
손계산 기대값인7=5점·1위, 9=5점·2위, 8=1점·3위도 별도로 assert했다.

후보1 C는 실패 instance4/execution6/step6의 stage를 유지하고, 새 비교 instance5/execution7/step7로
7/8/9 모두 처리했다. A/B/C는 같은 입력·결과 checksum과3행으로 수렴했다.
D는 같은 절대값을3번 써도 상품7의 점수가5이고1행이었다.
E는 SQL 후 commit 전 실패 시 stage가 없고 checkpoint=0이며, 재시작에서7/8/9를 다시 처리했다.
완료 reader count=4는 EOF 확인을 포함하는 내부 count이며 상품은3개다.

## 원자성·경계·공개 검증

freeze SQL 후 실패의 manifest/입력 전체 rollback, chunk SQL 후 stage/checkpoint 공동 rollback,
공개 rollback의 결과/COMPLETE/pointer 공동 rollback, 같은 공개의 반복 수렴을 확인했다.
누락/잘못된 stage는 공개하지 않으며 최신 revision 이후의 오래된 공개도 거절했다.
주간별 named lock의 다른 connection 경합과 해제를 검사했다.
DB의 입력 PK, 양수 score CHECK(실제 MySQL3819), 순위 unique, 주간 일치 FK 위반도 확인했다.

공개 commit 후 실제 최종 StepExecution update를 실패시킨 실행19에서는 다음을 관찰했다.

- DB manifest=COMPLETE, 결과와 pointer는 이미 commit되어 유지됐다.
- Job과 메모리 Step=UNKNOWN, 영속 Step=STARTED였다.
- 같은 parameter의 launch는 JobExecutionAlreadyRunningException으로 차단됐다.
- 공개 행동을 다시 호출하면 저장 결과/checksum을 검증하며 같은 결과에 수렴했다.

이 테스트는 실제 JobRepository 호출 실패 주입이며 DB 서버 장애나 JVM 강제 종료 실험이 아니다.
metadata를 자동으로 FAILED로 바꾸거나 정상 공개본을 삭제하지 않았다.
최초 afterStep 상태 변경 테스트로 이 경계를 입증하려던 부분은 독립 리뷰 지적에 따라 교체했다.

HTTP는 잘못된 날짜/월요일/limit, 공개 권한, 미공개/빈 주 구분, 정확한 요청 주,
현재 이름, 상품 삭제/브랜드 비노출, 필터 후 limit, 계산 순위 간격과 과거 결과 보존을 검사했다.
UTC 시작 포함/끝 제외도 domain 및 실제 freeze query의 결과로 확인했다.

## 실제 구매 연결

실제 최초 PG 확정 시각이 속한 UTC 주 `2026-11-16`을 사용했다. 구매 주문1/2/3의4활동을
Outbox relay→실제 Kafka log→실제 consumer로 저장했다. 동일 사건을 다른 offset2로 재발행해도4행이었다.
처리 중 실패한 r1을 유지한 채 늦은 구매 주문4를 도착시켰다.

- r1: inputCount4, 최초 실패 instance1/execution1 → 같은 instance/execution2 재시작.
  완성 점수5/5/1, 입력 checksum `431ba352ced698ee1baf22dbae2eaad65ff82e910ab11afeb39f7a9278787aa0`.
- r2: inputCount5, 점수15/5/1, 입력 checksum
  `e621a8a906cf26d7342eaf32e4b028f363e1697d72033db087bca4d1918b491d`.
- r2 계산 실패 동안 API는 r1 전체 응답을 유지했다. r2 완료 뒤에는 r2의 ID·시각·점수로 바뀌었다.
  PG create는4회였다. 정확한 manifest 시각/결과 checksum/API 내용은 JSON의 WEEK9_FLOW에 있다.

## 추가 streamer 회귀의 미해결 사항

check에서 재사용한 예전 streamer 산출물 대신 `test --rerun`을 실행했다.
17개 중 기존 `PurchaseActivityIntegrationTest`의 listener DB 실패→중단→재시작 검사1개가
committed offset을 읽는290행에서 NullPointerException으로 실패했다.
전체 명령은2h46m57s, 해당 테스트는1603.842초로 기록됐고 Kafka heartbeat/session timeout을 동반했다.
W9에서 streamer 소스는 변경하지 않았다. 이 관찰만으로 원인을 W9 회귀 또는 환경 장애로 단정하지 않는다.

코드를 바꾸지 않고 다시 실행했지만 Docker 소켓이 없어 MySQL context가 시작되지 않았다.
17개 중10개는 통과하고7개는 환경 준비 단계에서 실패했다. `docker ps`도 실제 소켓 부재를 확인했다.
Docker Desktop을 자동 시작하지 않았고, 재시작 요청을 사용자에게 전달했다.
건강한 Docker 환경에서 streamer 전체17개를 다시 실행해 첫 실패의 재현 여부를 확인해야 한다.
이 제한은 이미 통과한 W9 MySQL/Kafka/API E2E의 증거와 구분한다.

## 한계와 최종 확인

작은 실습 입력 전체를 freeze TX에서 읽는 구현이다. 대용량 paging·처리량 최적화·자동 스케줄,
강제 프로세스 종료와 코드 버전 교체는 검증 범위가 아니다. 운영 스키마 적용과 배포도 수행하지 않았다.
기존 AGENTS.md 변경을 보존했고 staged 변경은 없다. 최종 `git diff --check`도 통과했다.
