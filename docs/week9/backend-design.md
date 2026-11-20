# W9 주간 구매 랭킹

## 근거와 범위

- [기획서 요구] 실제 구매 활동을 고정한 뒤 주간 랭킹을 계산하고 완성본을 공개한다.
  UTC 월요일 반개구간, 최초 confirmedAt, 상품별 구매 수량 합, 동점 productId 오름차순을 지킨다.
  실패 시 동일 JobInstance에서 재개하고, 늦게 들어온 구매는 같은 주의 새 revision에 반영한다.
- [사용자 결정] [멱등 Writer와 checkpoint 비교 실험](checkpoint-comparison.md)을 별도로 수행한다.
  랭킹 점수의 입력은 W6/W7에서 저장한 구매 활동으로 한정한다.
- [코드 확인] 최초 구현은 W7까지 병합된 `af7e088`에서 검증했다. 제출 브랜치의 기준은 W8까지 병합된 `68be93a`다.
- [설계 결정] 아래는 계약을 구현하기 위한 내부 선택이다. 실시간 집계·자동 예약·마감 정책은 추가하지 않는다.

## 책임과 저장

공유 modules:ranking은 UTC 기간/수량/순위/checksum 규칙, 저장 port, JDBC adapter와 DDL을 소유한다.
commerce-batch의 application은 freeze/집계/검증/공개를 조율하고 interfaces의 Batch adapter가
Job/Step/reader를 연결한다. commerce-api의 application과 controller는 공개본 조회를 연결한다.
기존 JPA 주문/상품 및 streamer를 변경하지 않는다.

DDL 원본은 [ranking-schema.sql](../../modules/ranking/src/main/resources/ranking-schema.sql)이다.
업무 테이블은 자동 생성하지 않는다. 운영 적용도 이번 범위 밖이다.

- ranking_snapshots: snapshotId PK, (periodStart, revision) unique, 기간, FROZEN/COMPLETE,
  frozenAt, 입력/상품 건수, 입력/결과 SHA-256.
- ranking_snapshot_inputs: (snapshotId, orderId, productId) PK, 양수 quantity BIGINT, confirmedAt.
- ranking_score_stages: (snapshotId, productId) PK, 양수 score BIGINT. 절대값 upsert.
- ranking_results: (snapshotId, productId) PK, (snapshotId, calculatedRank) unique.
- ranking_publications: periodStart PK, nullable snapshotId. 주간별 공개 pointer 및 짧은 TX의 잠금 행.

Snapshot은 고정 입력과 산출물의 일관성 경계다. 내부 행은 manifest FK를 가지며 상품/주문과
객체 연관·FK·삭제 전파는 없다. pointer는 (periodStart,snapshotId) FK로 다른 주 참조를 막는다.
CHECK는 기간·revision·상태·양수 ID/수량/점수/순위를 보호한다. 입력과 결과 자동 삭제는 없다.

## 트랜잭션·동시성·재시작

보호할 불변식은 고정 입력의 불변성, chunk와 checkpoint의 공동 commit, 완성본만 공개,
동일 주 최신 revision의 단조 증가다. 같은 주의 실제 Job은 전용 DB 연결의 MySQL GET_LOCK으로
직렬화한다(대기 0, 경합 실행 FAILED). 연결은 Job listener가 보유하고 afterJob에 해제하며,
연결 단절 시 DB가 잠금을 해제한다. 프로세스가 죽어 STARTED가 남은 metadata의 자동 복구는 하지 않는다.
다른 주의 Job은 병행 가능하다. 이 잠금은 운영자 수동 SQL을 보호하지 않는다.

DB 행 잠금 순서는 publication(periodStart) → snapshot(snapshotId)이다.
freeze/publication의 모든 경로가 이 순서를 따른다. 외부 상품/주문 Aggregate를 잠그지 않는다.

1. freeze Step: 실제 DB REPEATABLE_READ TX에서 원본을 orderId/productId순으로 한 번 SELECT하고
   manifest와 입력을 함께 commit한다. frozenAt은 입력 SELECT 직전 DB UTC 시각이며 완료 시각이 아니다.
   max(id) cutoff를 사용하지 않는다. 작은 실습 데이터 전체를 메모리로 읽는 범위이며 대량 paging은 별도 설계다.
   기존 snapshot은 기간/revision/고정 입력 checksum을 검증하고 재사용한다. live와 비교·덮어쓰지 않는다.
2. score Step: named JdbcCursorItemReader가 distinct productId ASC를 읽는다. saveState=true,
   이름 weeklyRankingProducts, state key weeklyRankingProducts.read.count. processor는 frozen 입력만 합산한다.
   chunk=2는 checkpoint 관찰용 설정이며 성능 최적값이 아니다. writer와 영속 JobRepository는 Boot의
   동일 DataSource 및 실제 PlatformTransactionManager를 사용한다(JPA manager의 JDBC 참여 포함).
3. publish Step: publication → snapshot 잠금 아래 stage 전체를 frozen 정답과 비교한다.
   순위 생성·결과 저장·COMPLETE·pointer 교체가 같은 TX다. 실패하면 이전 공개본을 유지한다.
   이미 COMPLETE이면 저장 결과/checksum을 검증한다. 동일 공개는 no-op, 낮은 revision은 거절한다.
   commit 후 Batch metadata 실패는 공개 사실을 삭제하지 않는다. FAILED이면 같은 parameters로 재실행한다.
   최종 metadata 저장 실패로 UNKNOWN이면 Batch가 재시작을 차단할 수 있다. 실제 실행 종료/commit 결과/
   checkpoint 유효성을 조사한 뒤 운영자가 복구한다. 자동 metadata 보정은 없다. 공개 행동 자체의 재호출은 멱등하다.

Job 이름 weeklyRanking, identifying parameters는 periodStart(ISO 날짜), snapshotId, revision(양수 Long)이다.
run.id/현재 시각 incrementer/운영 실패 hook/skip 정책은 없다. 실패 시 같은 parameter로 외부에서 재실행한다.
완료 freeze Step은 기본 재시작에서 건너뛴다. 새로운 입력 반영은 원래 주의 명시적 새 revision이다.
local/test의 기존 ddl-auto=create 및 Batch initialize=always는 restart 실행에 쓰지 않는다.
스키마를 최초 한 번 준비한 뒤 JPA none/validate, Batch initialize=never로 DB와 metadata를 유지한다.

## 조회 계약

GET /api/v1/rankings/weekly?periodStart=2026-08-17&limit=10, 인증 없는 고객 공개 API다.
periodStart는 엄격한 yyyy-MM-dd 월요일, limit 기본10/1~100, 위반은 400 INVALID_REQUEST다.
기존 ApiResponse envelope 안에 periodStart/periodEnd/status/snapshotId/frozenAt/items를 반환한다.
item은 productId/name/score/rank다. 이름은 현재 상품 이름이며 상품·브랜드 deleted_at IS NULL을
먼저 적용한 후 limit한다. 계산 순위는 다시 매기지 않는다.

단일 SELECT가 publication → COMPLETE manifest → 필터된 결과/현재 상품을 읽으므로 pointer와
결과가 같은 statement read view에 속한다. stage 또는 요청과 다른 주로 fallback하지 않는다.
미공개는 NOT_PUBLISHED/null snapshotId/null frozenAt/빈 items, 0건 완성본은
PUBLISHED/실제 snapshotId/frozenAt/빈 items다.

## 결정적 내용 증거

UTF-8, 필드 TAB, 행 LF, 마지막 LF, SHA-256 소문자 hex.
입력은 orderId/productId ASC의 orderId,productId,quantity,confirmedAt,
시각은 UTC `uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'`(microsecond)다.
결과는 rank순 productId,score,rank다. 빈 입력은 빈 byte열의 SHA-256.
snapshotId·실행 ID·시각은 checksum에 섞지 않고 별도 기록한다.
합산 overflow는 Math.addExact로 실패시키며 건너뛰거나 음수로 바꾸지 않는다.

## 검증 계획

대표 규칙 Red→Green, 실제 MySQL INSERT-only restart probe → 실제 Job 재시작/원자성 →
MockMvc 조회 → 실제 PG/Outbox/Kafka/consumer/late arrival/revision E2E를 수행한다.
후보1은 테스트 전용 비교 Job으로 전체 재처리를 실행하며 production Job의 식별 정책을 바꾸지 않는다.
테스트 사이에는 소유 자원을 정리하되 실패/재시작 두 실행 사이에는 DB/입력/stage/metadata를 유지한다.
영속 context는 JobExplorer 재조회, 업무 결과는 새 connection 조회로 관찰한다.
실행 건수·실패·skip·종료 상태와 ID/checksum/처리 경로는 verification-report.md에 실제 결과만 기록한다.
