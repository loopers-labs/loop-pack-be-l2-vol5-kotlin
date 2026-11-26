# W10 기능별 검증 범위와 남은 위험

과제 8단계. 지금 작업물(`a80913e`) 기준으로 관련 회귀를 새로 실행한 결과와, 기능별로 이번에 확인한 것·확인하지 않은 것을 나눈다.

로컬 통제 실험과 회귀 테스트 결과다. 운영 배포·실계정·실제 PG·부하·모든 장애 종류는 확인하지 않았다.

## 실행한 검사

2026-11-26 실행. 이전 결과를 쓰지 않도록 `cleanTest` 뒤 실행했다. 실행 전 로컬에 띄워 둔 commerce-api·relay·streamer 는 내렸다(같은 로컬 Kafka 컨슈머 그룹을 쓰기 때문).

```bash
./gradlew :apps:commerce-api:cleanTest :apps:commerce-streamer:cleanTest :apps:commerce-batch:cleanTest \
  :apps:commerce-relay:cleanTest :apps:pg-simulator:cleanTest \
  :apps:commerce-api:check :apps:commerce-streamer:check :apps:commerce-batch:check \
  :apps:commerce-relay:check :apps:pg-simulator:check --continue
```

| 모듈 | 테스트 | 건너뜀 | 실패 | ktlint | 비고 |
| ---- | ---- | ---- | ---- | ---- | ---- |
| commerce-api | 491 | 0 | 0 | 통과 | ArchUnit(`ArchitectureTest`) 포함 |
| commerce-batch | 90 | 0 | 0 | 통과 | |
| commerce-relay | 7 | 0 | 0 | 통과 | 로컬 Kafka(19092–19094) 사용 |
| pg-simulator | 38 | 0 | 0 | 통과 | |
| commerce-streamer | 19 | 0 | **3** | 통과 | 아래 "streamer 실패 3개" |

과제 예시 검사와 이 레포의 테스트 이름 대응:

| 과제 예시 | 이 레포 |
| ---- | ---- |
| `*CheckoutIncidentTest` | `CheckoutIncidentTest` |
| `*PaymentIntegrationTest` | `OrderPaymentIntegrationTest` 외 결제 테스트 |
| `*CouponIssueConcurrencyTest` | `CouponIssueConcurrencyIntegrationTest` |
| `*RankingJobRestartTest` | `WeeklyRankingRestartComparisonTest`, `BatchRestartProbeTest` |

### streamer 실패 3개

- `OrderEventConsumerGroupIntegrationTest` 3개가 준비 단계(`@BeforeEach`)에서 "컨슈머 그룹이 파티션을 모두 배정받지 못했다"로 실패한다. 구매 활동 저장 로직을 검사하기 전 단계다.
- 이 클래스만 실행하면 3개 모두 통과한다. streamer 모듈 전체를 실행하면 두 번 모두 실패했다.
- 이번 W10 변경 전 커밋(`7eab0b6`, streamer 테스트를 고치기 전)에서도 모듈 전체 실행 시 같은 3개가 실패했다. 이번 변경으로 생긴 실패는 아니다.
- 원인은 확인하지 않았다. 먼저 도는 다른 테스트 클래스의 Spring 컨텍스트가 캐시된 채 같은 컨슈머 그룹을 잡고 있어 파티션이 나뉘는 것으로 추정한다. 다른 세션 앱과 같은 로컬 Kafka 를 쓰는 환경 영향일 수도 있다.
- 그래서 "streamer 전체 통과"라고 말할 수 없다. 이 클래스가 확인하는 두 컨슈머 그룹 동시 수신·품목별 활동·중복 방지 가운데, 품목별 활동과 중복 방지는 `PurchaseActivityIdempotencyIntegrationTest`(9개)와 `OrderConfirmedStreamerConnectionTest`(2개)가 모듈 전체 실행에서도 통과했다.

## 기능별 정리

### 상품·브랜드·좋아요·관리자

확인할 계약: 조회·삭제·소유권·과거 주문 보존·캐시 보호

- 이번 변경: 없음. 로컬 실험에서 관리자 API 로 상품을 만들고 재고를 넣는 데만 썼다.
- 확인: commerce-api 회귀 통과. `ProductV1ApiE2ETest`, `ProductListV1ApiE2ETest`, `ProductListQueryCountTest`, `ProductSearchCacheIntegrationTest`, `ProductServiceIntegrationTest`, `ProductStockSyncIntegrationTest`, `BrandDeleteIntegrationTest`, `BrandDeleteRollbackIntegrationTest`, `BrandV1ApiE2ETest`, `LikeServiceIntegrationTest`, `LikeV1ApiE2ETest`, `MyLikeV1ApiE2ETest`, `AdminProductV1ApiE2ETest`, `AdminV1ApiE2ETest`, `AdminOrderV1ApiE2ETest`, "주문한 뒤 상품이 바뀌어도" 테스트.
- 남은 위험: 이번 주제와 맞닿은 새 위험은 없다.

### 포인트·주문·PG

확인할 계약: 포인트 기록 보존, PG 단일 시도·예약·조회 조정

- 이번 변경
  - 본인 주문 대사 API `POST /api/v1/orders/{orderId}/reconcile` 추가(`PaymentReconciliation`).
  - `PaymentSettlement` 가 결제 성공 반영 때 Outbox 에 기록(W8 에서 빠진 W7 연결 복원), 성공 시각을 `Clock` 빈으로 정함. `Payment.markSuccess(at)` 시그니처 변경.
  - `StockReservationService.commit` 이 확정한 예약을 돌려줌.
  - PG 경계 로그 3줄, `transactionKey` 전달.
- 확인
  - 대사: `PaymentReconciliationIntegrationTest` 8개(SUCCESS·FAILED 반영, PENDING·거래 없음·금액 불일치·조회 실패는 그대로, 반복 호출, 다른 사용자 거절, 결제 생성 1회 유지), `OrderReconcileV1ApiE2ETest` 2개(실제 HTTP fixture).
  - 결제·예약 회귀: `OrderPaymentIntegrationTest`, `PaymentCallbackHandlerIntegrationTest`, `PaymentCallbackV1ApiE2ETest`, `PaymentRepositoryIntegrationTest`, `PgClientTest`, `PgCircuitBreakerIntegrationTest`, `StockReservationIntegrationTest`, `StockLostUpdateControlTest`, 동시 확정·취소 테스트, 포인트 결제·충전 테스트.
  - 단언 확인: 대사의 금액 확인을 빼면 해당 테스트가 실패한다.
- 남은 위험
  - 복수 거래: PG 조회가 주문당 거래 하나만 돌려줘서 같은 주문의 거래가 여럿인 경우를 알아낼 수 없다.
  - 대사는 수동이다. 자동·일괄 대사는 없다.
  - `STILL_PENDING` 이 반복될 때 사람에게 넘길 기준 시간을 정하지 않았다.
  - 대사로 FAILED 를 반영해도 주문은 CONFIRMED 로 남는다(콜백 거절과 같은 기존 동작). 이번에 바꾸지 않았다.

### 이벤트·Outbox

확인할 계약: 실제 구매 활동, 발행 gap 복구·소비 중복 방지

- 이번 변경: 결제 성공 → Outbox 기록 복원.
- 확인
  - 복원 전 실패하던 Outbox·이벤트 테스트 8개(`OrderEventOutboxIntegrationTest` 3, `OrderEventPublishIntegrationTest` 3, `OrderEventSentIntegrationTest` 1, `OrderEventUnpublishedIntegrationTest` 1)가 통과한다.
  - relay: `OutboxRelayIntegrationTest`, `OutboxRelayUnreachableIntegrationTest`(발행 실패 뒤 다시 보냄), `OrderConfirmedRelayConnectionTest`.
  - streamer: `PurchaseActivityIdempotencyIntegrationTest`, `OrderConfirmedStreamerConnectionTest`.
  - 사고 연결 2·3구간: 장애 집합 포함 20건이 Kafka 에 그대로 도착하고, 구매 활동이 주문마다 한 번씩 20건, 다시 받아도 20건.
  - 로컬: 대사 전 Outbox·구매 활동 17건 → 대사 뒤 20건, 반복 대사 뒤에도 20건.
- 남은 위험
  - streamer `OrderEventConsumerGroupIntegrationTest` 모듈 전체 실행 실패(위).
  - 한 실행 안에서 Outbox → 실제 Kafka → 구매 활동 → 랭킹까지 흐르는 자동 테스트는 없다. 구간은 계약 파일로 잇는다.
  - streamer 의 구매 활동 컨슈머는 저장 성공 로그를 남기지 않아 Grafana 에서 저장 여부를 볼 수 없다. DB 로 확인한다.

### 쿠폰

확인할 계약: 총량·사용자 중복·동시 실행 수용·본인 목록

- 이번 변경: 없음.
- 확인: commerce-api 회귀 통과. `CouponIssueConcurrencyIntegrationTest`, `CouponIssueProcessorIntegrationTest`, `CouponIssueRedisFailureIntegrationTest`, 쿠폰 발급·목록 API 테스트, `CampaignTest`.
- 남은 위험: 사고 테스트와 로컬 실험에서 쿠폰을 쓰지 않았다. "PG 복구가 쿠폰을 바꾸지 않는다"는 직접 확인하지 않았다. 이 레포는 쿠폰을 PG 할인에 적용하지 않아 결제 경로가 쿠폰을 건드리지 않는다.

### 랭킹

확인할 계약: 실제 입력·같은 instance 재시작·revision·공개 API

- 이번 변경: 프로덕션 코드 없음. 사고 연결 테스트(`IncidentWeeklyRankingConnectionTest`, `WeeklyRankingApiConnectionTest.returnsIncidentRankingWrittenByBatch`) 추가.
- 확인
  - 기존 W9 회귀를 생략하지 않았다: `WeeklyRankingRestartComparisonTest`(재시작해도 같은 점수·순위·checksum, 고정 뒤 늦은 구매가 r1 을 바꾸지 않음), `BatchRestartProbeTest`, `WeeklySalesSnapshotJobE2ETest` 28개, `WeeklySalesReaderTest`, `WeeklyRankingWriterTest`, `WeeklyRankingSchedulerTest`, `SnapshotWeekTest`, 랭킹 조회 API 테스트.
  - 사고 연결 4·5구간: 정상 10 + 복구한 장애 10 → 새 스냅샷 입력 20건, 상품 점수 20, 1위, 포인터 PUBLISHED, API 응답 점수 20.
  - 로컬: 같은 시나리오로 주간 배치를 직접 실행해 랭킹 API 에서 상품 점수 20 확인.
- 남은 위험
  - 로컬 배치는 주가 끝나기 전에 그 주를 집계했다. 확인용 지름길이다.
  - 사고와 복구 사이에 주가 바뀌는 경우(복구 주문이 다음 주로 가는 경우)는 테스트하지 않았다.

### 이번 사고

확인할 계약: 응답 유실 진단·같은 주문 복구·후속 결과

- 확인
  - `CheckoutIncidentTest.recoversIncidentWithoutNewPayment`: 한 테스트 안에서 정상 10/0 → 장애 7/3(health UP, 지표 3, `failure=TIMEOUT` 로그) → 대사 복구 10/0. 결제 생성 누적 20 그대로(추가 0), 거래 키 그대로, 재고 예약 COMMITTED, 재고 다시 줄지 않음, 포인트 그대로, Outbox 주문마다 1건, 반복 대사·늦은 콜백 뒤에도 같음, 지표 0. Outbox payload 20건이 사고용 계약 파일과 같다.
  - `CheckoutIncidentTest.observesLostResponse`: 경계 로그와 지표가 기준 시간 전 0, 뒤 1.
  - `LocalPgFixtureTest` 5개, pg-simulator `3333` 카드 테스트.
  - 단언 확인: 응답 유실 주입을 빼면 장애 단언이, Outbox 기록을 빼면 복구 단언이, 복구 시각을 고정하지 않으면 계약 비교가 실패한다.
  - 진단: 실험 일지 4번 판정(응답 유실, 내부 반영 실패 아님).
  - 증거: `~/w10-observability/evidence/` 의 대사 전·후 기록과 `.sha256`.
- 남은 위험
  - PG 가 왜 3초를 넘겼는지는 우리 쪽 증거로 알 수 없다. PG 호출·콜백·Kafka 구간의 분산 추적이 없다.
  - 장애 주입은 테스트용 fixture 와 로컬 pg-simulator 카드다. 실제 PG 의 응답 유실 양상(헤더는 왔는데 본문이 끊기는 경우 등)은 만들지 않았다.
  - 증거 파일은 레포 밖 로컬에 있다.
