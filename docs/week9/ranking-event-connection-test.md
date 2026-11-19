# 주문 확정 이벤트 → 랭킹 API 연결 테스트

2026-11-19, 주문 확정 이벤트가 아웃박스 → relay → Kafka → 구매 활동 → 스냅샷 → 랭킹 잡 → 조회 API 까지 이어지는지 확인했다. 앱끼리는 서로 참조할 수 없어서 구간별 자동 테스트로 나누고, 로컬에서 앱을 띄워 한 번 이어서 돌렸다.

## 계약 fixture

모든 구간이 같은 이벤트 3 건을 쓴다. 2026-08-17 주 (`2026_08_3`) 다. 파일은 api·relay·streamer 테스트 리소스의 `contract/order-confirmed-2026-08-17.jsonl` 에 같은 내용으로 하나씩 있다.

| 주문 | 확정 (UTC)           | 품목          |
| ---- | -------------------- | ------------- |
| 42   | 2026-08-18T10:00:00Z | 7×3, 8×1      |
| 43   | 2026-08-19T10:00:00Z | 9×5           |
| 44   | 2026-08-20T10:00:00Z | 7×2           |

기대 랭킹은 7 (5 점, 1 위), 9 (5 점, 2 위), 8 (1 점, 3 위) 다.

## 구간별 자동 테스트

다섯 개 모두 통과했다.

| 구간               | 테스트                                              | 확인한 것                                                                 |
| ------------------ | --------------------------------------------------- | ------------------------------------------------------------------------- |
| 1. api → 아웃박스  | `OrderConfirmedOutboxConnectionTest` (commerce-api) | 아웃박스 payload 가 계약 JSON 과 같고 키는 주문 id                        |
| 2. relay → Kafka   | `OrderConfirmedRelayConnectionTest` (commerce-relay) | Kafka 에 키와 payload 가 그대로 도착                                      |
| 3. Kafka → streamer | `OrderConfirmedStreamerConnectionTest` (commerce-streamer) | 구매 활동 4 행이 남고, 같은 이벤트를 다시 받아도 늘지 않음          |
| 4. 배치            | `WeeklyRankingRestartComparisonTest` (commerce-batch) | 고정한 뒤 같은 주의 늦은 구매가 와도 r1 입력 checksum 과 랭킹이 그대로 |
| 5. 조회 API        | `WeeklyRankingApiConnectionTest` (commerce-api)     | 배치가 쓴 랭킹과 포인터가 같은 점수·순위로 나옴                           |

- 2·3 구간은 기존 테스트처럼 로컬 Kafka (`localhost:19092~19094`) 를 쓴다.
- 결제 성공 때 주문 확정 이벤트를 만드는 코드는 8 주차 머지 때 빠졌고, 되살리지 않기로 했다. 그래서 1 구간은 이벤트를 아웃박스에 넣는 데서 시작한다.

## 로컬 실행

2026-11-19 02:03 (KST), 로컬 `docker-mysql-1` 과 로컬 Kafka 로 돌렸다.

1. 로컬 `outbox_event` 에 계약 이벤트 3 건을 PENDING 으로 넣었다. 토픽은 `order.events.v1` 이다.
2. relay 를 5 초 주기로 띄웠다. 3 건 모두 `SENT` 가 됐다.

   ```bash
   RELAY_CRON='*/5 * * * * *' ./gradlew :apps:commerce-relay:bootRun --args='--management.server.port=18181'
   ```

3. streamer 를 띄웠다. 구매 활동 4 행과 처리 기록 3 건이 남았고, `confirmed_at` 이 계약과 같다.

   ```bash
   ./gradlew :apps:commerce-streamer:bootRun --args='--server.port=18085 --management.server.port=18182'
   ```

4. 배치를 돌렸다. 잡은 `COMPLETED`.

   ```bash
   ./gradlew :apps:commerce-batch:bootRun --args='--job.name=weeklySalesSnapshotJob requestDate=2026-08-24,java.time.LocalDate,false snapshotId=2026_08_3_r1'
   ```

   - 사본 `2026_08_3_r1` 은 4 행이다. 정보 행 checksum `ebddacaa2f0bec7745862bc53449933cf86a5401fe2c7d2ac1b263a5baf81e35` 은 fixture 로 직접 계산한 값과 같다.
   - 랭킹은 7 (5 점, 1 위), 9 (5 점, 2 위), 8 (1 점, 3 위) 이고, 포인터 `2026_08_3` 은 `PUBLISHED`, 상품 수 3 이다.

5. commerce-api 를 8090 으로 띄워 조회했다.

   ```bash
   ./gradlew :apps:commerce-api:bootRun --args='--server.port=8090 --management.server.port=18183'
   curl 'localhost:8090/api/v1/rankings/weekly?periodStart=2026-08-17&limit=10'
   ```

   `PUBLISHED`, `snapshotId` `2026_08_3`, items 는 7 (1 위), 9 (2 위) 다. 로컬 DB 에서 상품 8 이 삭제된 상태라 조회 규칙대로 빠졌다.

- 다른 세션이 띄운 commerce-api jar 가 8080·8081·8083 을 쓰고 있어서 다른 포트로 띄웠고, 다 쓰고 내렸다.
- 배치를 띄울 때 로컬 DB 에 `purchase_activity_snapshot`, `product_sales_snapshot_meta` 가 자동으로 만들어졌다.

## 아직 보지 않은 것

- r2 를 공개하기 전에는 API 가 r1 을, 공개한 뒤에는 r2 를 돌려주는지. r2 revision 공개를 만든 뒤에 볼 수 있다.
- 로컬에서 같은 이벤트를 두 번 보내는 것. 자동 테스트 3 구간에서만 확인했다.
