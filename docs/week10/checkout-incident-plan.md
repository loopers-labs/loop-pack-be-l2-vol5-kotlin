# 결제 응답 유실 사고 실험 기획

2026-11-22 기획. 1번(사고 범위와 정상 기준선)부터 하나씩 진행한다.

이 문서는 무엇을 할지만 적는다. 단계마다 한 일·알게 된 것·실행 기록은 [실험 일지](incident-log.md)에, 사고 대응 절차는 7단계에서 `docs/runbook/week10.md` 에 쓴다.

## 목표

health 는 UP 인데 고객은 "결제 확인 중"을 보는 사고를 로컬 테스트에서 일부러 만든다. 증거로 원인을 가리고, 새 PG 결제 없이 같은 주문을 마무리한다. 그 뒤 재고·Outbox·구매 활동·주간 랭킹까지 같은 주문 집합으로 맞춘다.

통제하는 경계는 하나다. **PG 는 거래를 SUCCESS 로 저장했는데 그 생성 응답을 잃어서 내부 결제가 미확인으로 남는 경계**.

범위 밖: 실제 카드·계정, 외부 PG 장애, 운영 DB 변경, 부하, 분산 추적 backend 구축.

## 지금 코드의 상태 (main `7380737` 기준)

| 구간 | 상태 | 근거 |
| ---- | ---- | ---- |
| 주문·재고 확보 | 있음 | `OrderConfirmation.start` 한 TX 에서 재고 예약, Payment `PENDING` 저장, 주문 `CONFIRMED` |
| PG 결제 요청 | 있음 | `PgClient.request`, read-timeout 3s, 서킷 브레이커 `pg` |
| 결과 반영: 콜백 | 있음 | `PaymentCallbackHandler` → `PaymentSettlement.reflect` |
| 결과 반영: 조회(reconcile) | 없음 | `PaymentGateway.findByOrderId` 는 `PgClient` 에 구현만 있고 부르는 곳이 없다 |
| 주문 확정 → Outbox | 연결 안 됨 | `OrderEventOutboxWriter`·`commerce-relay` 는 있지만, 프로덕션 코드에서 `OrderConfirmedEvent` 를 만들거나 `append` 를 부르는 곳이 없다. 테스트가 이벤트를 직접 넣는다 |
| Kafka → 구매 활동 | 있음 | `commerce-streamer` `PurchaseActivityConsumer` |
| 주간 랭킹 | 있음 | W9 스냅샷·랭킹 배치, `GET /api/v1/rankings/weekly` |
| 쿠폰 | 있음 | W8. 결제와 무관하고 보존만 한다 |

관측:

- health 는 liveness·readiness 만 본다 (`supports/monitoring/.../monitoring.yml`). 결제가 꼬여도 UP 이다.
- 지표는 `http.server.requests` 뿐이고 업무 지표가 없다.
- 결제 요청과 timeout 시점에 orderId 가 찍히는 로그가 없다. timeout 은 `ApiControllerAdvice` 의 `Exception : Read timed out` 한 줄로만 남는다. 콜백 로그에만 orderId·transactionKey 가 있다.

## 과제 용어와 이 코드의 대응

주문은 PG 호출 전에 확정된다. 그래서 미확인은 주문이 아니라 Payment 에 남는다.

| 과제 표현 | 이 코드 |
| --------- | ------- |
| 내부 성공 | Payment `SUCCESS` |
| UNKNOWN | Payment `PENDING` (UNKNOWN 상태를 새로 만들지 않는다) |
| paymentAttemptKey | `Payment.paymentKey` |
| providerOrderId | PG 요청의 `orderId` |
| 거래 키 | PG `transactionKey` |

## 정한 것

- **브랜치**: main 에서 딴 `feat/volume10-incident`.
- **PG fixture**: commerce-api 테스트 안에 HTTP 서버를 둔다. pg-simulator 와 같은 계약(`POST`·`GET /api/v1/payments`, commerce-api 로 콜백)을 따른다. pg-simulator 를 컨테이너나 테스트 의존으로 띄우지 않는다. 앱끼리 의존이 생기고, 응답 유실·콜백 보류·create 수를 테스트가 직접 제어하기 어렵기 때문이다.
- **DB**: Testcontainers 로 매 실행 새 DB 에서 시작한다. baseline → fault → recovery 사이에는 비우지 않는다.
- **데이터**: 브랜드·상품·재고·사용자·포인트는 테스트 시작 때 저장한다. 주문과 결제 상태는 실제 API 흐름으로만 만든다. Payment 상태를 DB 에 직접 쓰지 않는다.

## 단계별 계획

### 1. 사고 범위와 정상 기준선

바꿀 파일은 전부 `apps/commerce-api/src/test` 아래다.

- `LocalPgFixture`
  - 랜덤 포트 HTTP 서버.
  - `POST /api/v1/payments`: 거래 키를 만들어 `PENDING` 거래를 저장하고 create 수를 센다. 같은 주문의 두 번째 create 는 기록해 두고 테스트를 실패시킨다.
  - `GET /api/v1/payments?orderId=`: 현재 거래.
  - `settle(orderId)`: 거래를 `SUCCESS` 로 바꾸고 `/api/v1/payments/callback` 으로 보낸다.
  - 응답 유실 제어(2단계부터 사용): 지정한 주문은 거래를 저장한 뒤 commerce-api read-timeout 보다 오래 붙잡는다. 지정한 주문의 콜백은 보류한다.
- `LocalPgFixtureTest`: 중복 create 가 기록되는지, 응답 유실 모드에서도 거래가 남는지.
- `CheckoutIncidentTest` (baseline 까지)
  - 앱 랜덤 포트, management 랜덤 포트, `pg.base-url` 은 fixture 주소.
  - 상품 1개, 재고 30, 주문마다 다른 사용자, 카드 결제 10건.
  - fixture `settle` → 콜백 → Payment `SUCCESS`.

baseline 기대값:

| 항목 | 기대 |
| ---- | ---- |
| Payment `SUCCESS` / `PENDING` | 10 / 0 |
| fixture `SUCCESS` 거래 | 10 |
| create 수 | 10 |
| 재고 예약 확정 | 10 |
| health | UP |

### 2. 관측 연결

장애를 내기 전에 볼 수단을 미리 넣어 두는 단계다. 결제 동작(성공·실패·재고·포인트)은 바꾸지 않고 로그와 지표만 추가한다. 설정만으로는 안 되고 코드를 추가해야 한다. 업무 지표와 주문별 로그는 Spring·Micrometer 설정으로 생기지 않는다.

과제 근거: "특정 주문을 연결하는 구조화 로그를 실제 경계에 남기고, 현재 미확인 결제 수를 알려 주는 업무 지표 하나를 추가합니다." / 체크리스트 "실제 health·업무 지표·식별자별 경계 기록으로 증상을 설명한다."

정한 것 (2026-11-23):

- gauge 는 **일정 시간이 지난 PENDING** 만 센다. 정상 결제도 콜백 전 잠깐 PENDING 이라 전체를 세면 0 이 아니어도 사고라고 단정할 수 없다. 기준 시간은 설정값(기본 1분).
- `PaymentTransaction` 에 `transactionKey` 필드를 추가한다. 지금은 PG 응답의 거래 키를 받아 두지 않고 버린다. 5단계 reconcile 에서도 쓴다.

메트릭 1개 (안):

| 이름 | 값 | label |
| ---- | -- | ----- |
| `payment.pending.stale` | 생성 후 기준 시간을 넘긴 PENDING 결제 수 | 없음 (orderId·userId 금지) |

로그 4줄 (안):

| 위치 | 언제 | 남길 값 | 가르는 가설 |
| ---- | ---- | ------- | ----------- |
| `OrderFacade.confirm` | PG 호출 직전 | orderId, paymentKey | 요청을 보냈다는 사실 |
| `OrderFacade.confirm` | PG 응답 받음 | orderId, paymentKey, transactionKey, status | 응답을 받았다 → 응답 유실 아님 |
| `OrderFacade.confirm` | PG 호출 실패 | orderId, paymentKey, failure(`TIMEOUT` 또는 예외 이름) | timeout 이면 응답 유실 쪽 증거 |
| `OrderConfirmation.settle` | DB 반영 뒤 | orderId, paymentKey, 반영한 status | 응답은 받았는데 반영 로그가 없으면 내부 반영 실패 쪽 증거 |

- timeout 판단은 근본 원인이 `SocketTimeoutException` 인지로 한다 (1단계에서 알게 된 것).
- 콜백 로그는 `PaymentCallbackHandler` 에 이미 있다(orderId, transactionKey). 추가하지 않는다.
- 4번째 로그(settle 반영)는 과제 원문에 없어서 넣지 않았다 (2026-11-23).

함께 바꿀 것: `PgDto` → `PaymentTransaction` → `PaymentResult` 로 `transactionKey` 전달, 기준 시간 설정 1줄.

확인: 테스트가 실제 Actuator health 와 Prometheus 응답을 가져와 gauge 가 존재하고 바뀌는지, 로그에 orderId 와 `TIMEOUT` 이 찍히는지 본다.

### 3. 같은 상태를 유지하는 사고 실험

한 테스트 메서드에서 baseline → fault → 증거 보존 → recovery 를 잇는다. fault 와 recovery 까지 `CheckoutIncidentTest.recoversIncidentWithoutNewPayment` 한 메서드에서 잇는다(3단계에서 fault, 5단계에서 recovery).

fault 기대값 (별도 장애 집합 10건, 그중 고정한 3건만 응답 유실 + 콜백 보류를 함께):

| 항목 | 기대 |
| ---- | ---- |
| Payment `SUCCESS` / `PENDING` | 7 / 3 |
| fixture `SUCCESS` 거래 | 10 (누적 20) |
| health | UP |

실패해도 `finally` 에서 켠 주입은 끄되, 거래와 증거는 지우지 않는다.

### 4. 경쟁 가설

조치 전에 가설과 예상 관찰을 적고, 진단용 조회는 읽기만 한다. 복구 API 는 fault 증거를 보존한 뒤에만 부른다.

### 5. 같은 주문 복구

본인 주문 reconcile API 로 같은 3건을 조회해 기존 `PaymentSettlement` 로 반영한다. create 추가 0, 재고 추가 차감 없음, 포인트·쿠폰 불변, 반복 호출해도 같은 결과.

### 6. 후속 결과

사고용 계약 파일로 W9 연결 5구간을 한 번 더 잇는다(2026-11-26 완료, 실험 일지 참고).

같은 장애 집합의 Outbox → relay → Kafka → 구매 활동 → 새 스냅샷 → 랭킹 API. baseline 10 + 장애 10 이 같은 주에 들어가면 상품 점수 20.

### 7. 증거와 runbook

실행마다 새 증거 위치에 세 단계 기록을 남기고 `docs/runbook/week10.md` 를 쓴다.

### 8. 회귀와 남은 위험

관련 회귀·ktlint·check 결과와, 이번에 확인하지 않은 위험을 구분해 적는다.

## 아직 정하지 않은 것

- ~~reconcile 경로를 이번에 만들지~~: 만들었다(2026-11-26). 중단 조건은 [실험 일지](incident-log.md)의 "대사 API" 참고.
- ~~주문 확정 → Outbox 연결~~: W7 에 결제 성공 시점으로 정해져 있었고 W8 커밋에서 빠진 것이었다. 같은 시점으로 복원했다(2026-11-26).
- **사용자 범위**: PG 계약(`PgDto.PaymentRequest`)에 사용자 필드가 없다. "같은 계정" 검증을 무엇으로 할지.
- ~~미종결 gauge 이름과 범위~~: 범위는 정함(일정 시간 지난 PENDING). 이름은 `payment.pending.stale` 안.
- ~~settle 반영 로그를 넣을지~~: 넣지 않음. 4단계에서 증거가 부족하면 다시 본다.

## 과제 후 추가할 것

과제 원문이 요구하지 않거나 "선택"으로 둔 것이다. 과제를 마친 뒤 본다.

- **분산 추적**: PG 호출에 trace 헤더 전달(`PgClient` 가 RestClient 를 직접 만들어서 지금은 안 실림), 수집 backend(Tempo 등). 콜백과 reconcile 은 원래 다른 요청이라 붙여도 같은 trace 가 되지 않는다.
- **Grafana "PG 결제 경계 로그" 패널 필터**: `PG 결제` 글자로 거르기 때문에 콜백 줄(`결제 승인 반영`)이 빠진다. orderId 로만 거르면 요청·응답·콜백이 한 화면에 이어진다.
- **Alloy 로그 해석**: 줄 안의 시각과 레벨을 읽게 해서 Loki 시각 밀림과 `unk` 를 없앤다.
- **read-timeout 재검토**: 근거 없이 숫자를 정하지 않는다. W5 `docs/week5/slow-pg-capacity.md` 의 스레드 점유 결과와 같이 본다.
