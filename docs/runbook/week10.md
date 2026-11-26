# 결제 미확인(PENDING) 대응 runbook

카드 결제 뒤 고객은 "결제 확인 중"을 보는데 앱 health 는 UP 인 상황에서 쓴다. 근거와 실행 기록은 [실험 일지](../week10/incident-log.md)에 있다.

이 문서에는 DB 를 직접 바꾸거나 결제를 다시 만드는 명령을 넣지 않는다. 외부 거래가 불확실할 때 그 두 가지는 중복 결제나, 돈은 나갔는데 주문은 실패한 상태를 만든다.

## 1. 언제 이 문서를 펴나

| 신호 | 어디서 | 기준 |
| ---- | ---- | ---- |
| 미확인 결제 지표 `payment_pending_stale` | Grafana "W10 결제 관측" 상단, Prometheus | 1 이상. 생성 후 1분(`payment.metrics.stale-pending-after`)이 지나도 PENDING 인 결제 수 |
| 결제 확정 API 500 | WARN·ERROR 로그 패널 | `PG 결제 응답을 받지 못했다 … failure=TIMEOUT` |
| 고객 문의 | — | 결제는 됐는데 "확인 중"에서 안 넘어감 |

health(`/actuator/health`)는 이때도 UP 이다. health 는 프로세스와 설정한 검사가 살아 있는지만 본다. 특정 주문의 결제가 끝났는지는 답하지 않는다. 그래서 health 로 "이상 없음"을 판단하지 않는다.

지표는 실제 상태보다 늦다. 기준 시간(1분)과 수집 간격(5초)만큼 늦게 오른다.

## 2. 먼저 보존할 것

조치(대사)는 PENDING 을 SUCCESS 나 FAILED 로 바꾼다. 바꾼 뒤에는 DB 에 PENDING 이었다는 흔적이 남지 않고 지표도 0 이 된다. 원인 판정과 "같은 주문을 새 결제 없이 복구했다"는 비교가 모두 조치 전 기록에 기댄다. 그래서 조치 전에 아래를 실행마다 새 파일로 남긴다. 실패한 시도의 기록도 지우지 않는다.

| 남길 것 | 방법 |
| ---- | ---- |
| 시각, 대상 주문 목록 | 기록 파일 맨 위 |
| 결제·주문·재고 예약 상태 | 아래 4-2 의 DB 조회 결과 |
| PG 거래 | 주문마다 PG 조회 결과(거래 키, 상태, `requestedAt`) |
| 경계 로그 | 주문번호로 검색한 commerce-api 로그 |
| health, 지표 | `/actuator/health`, `/actuator/prometheus` 의 `payment_pending_stale` 원문 |
| 파일 해시 | `shasum -a 256 <파일> > <파일>.sha256` |

해시는 파일이 나중에 바뀌지 않았다는 것만 보여 준다. 내용이 맞다는 증명은 아니다.

## 3. 영향 범위 고정

영향 주문은 "생성 후 기준 시간이 지난 PENDING 결제"다. 지표와 같은 조건으로 찾는다.

```sql
SELECT p.order_id, p.user_id, p.payment_key, p.amount, p.created_at
FROM payment p
WHERE p.status = 'PENDING' AND p.created_at < UTC_TIMESTAMP() - INTERVAL 1 MINUTE
ORDER BY p.created_at;
```

이 목록을 기록 파일에 고정하고 조치는 이 목록 안에서만 한다. 조치 중에 새로 생긴 PENDING 은 다음 회차에 따로 다룬다.

새 피해를 줄이려면 원인을 확인할 때까지 실험·테스트 주문 투입을 멈춘다. 운영이라면 PG 상태 페이지와 서킷 상태를 함께 본다. 멈춰도 이미 PENDING 인 주문은 그대로 남는다.

## 4. 진단

### 4-1. 가설

| 가설 | 맞다면 보일 것 | 확인 방법 |
| ---- | ---- | ---- |
| PG 처리 지연 | PG 조회 결과가 PENDING | PG 주문번호 조회 (`GET /api/v1/payments?orderId=`) |
| PG 성공 응답 유실 | PG 조회 SUCCESS · 우리 결제 PENDING · `PG 결제 응답을 받지 못했다 … failure=TIMEOUT` 로그 | PG 조회, 결제 상태 조회, 주문번호로 로그 검색 |
| 성공 결과의 내부 반영 실패 | PG 조회 SUCCESS · 반영 시도 로그(콜백 `결제 승인 반영` 또는 에러)와 DB rollback | 주문번호로 로그 검색(콜백·에러), 결제·재고 예약 상태 조회 |

### 4-2. 진단 질의

주문 하나(`{orderId}`)를 대상으로 실행한다. 모두 읽기만 한다.

```bash
# PG 거래. 조회 API 는 거래를 만들거나 바꾸지 않는다.
curl -s "localhost:8082/api/v1/payments?orderId={orderId}"

# 경계 로그: 요청 → 응답/timeout → 대사 → 콜백
grep "orderId={orderId} " ~/w10-observability/logs/commerce-api.log
```

```sql
SELECT o.id, o.status, p.payment_key, p.status, p.amount, p.created_at, p.succeeded_at
FROM orders o JOIN payment p ON p.order_id = o.id WHERE o.id = {orderId};
SELECT order_id, product_id, quantity, status FROM stock_reservation WHERE order_id = {orderId};
SELECT event_id, status FROM outbox_event WHERE message_key = '{orderId}';
```

Grafana 에서는 "W10 결제 관측" 상단 orderId 칸에 번호를 넣으면 commerce-api·streamer 로그가 한 화면에 이어진다. 시간 범위를 고정하고 자동 새로고침을 끄고 본다. 줄 맨 앞 시각은 Loki 가 받은 시각이라 늦을 수 있으니 줄 안쪽의 앱 시각을 본다.

traceId 로는 주문을 따라가지 않는다. PG 호출, 콜백, 대사 경계마다 끊긴다. 주문번호와 paymentKey 로 잇는다.

### 4-3. 판정 기준

| PG 조회 | 우리 로그 | 판정 |
| ---- | ---- | ---- |
| PENDING | — | PG 처리 지연 |
| SUCCESS / FAILED | 요청 뒤 `failure=TIMEOUT`, 이후 반영 시도 없음 | 응답 유실 |
| SUCCESS / FAILED | 응답 또는 콜백을 받은 로그 뒤 에러·rollback | 내부 반영 실패 |
| 거래 없음(404) | — | 판정 보류. PG 쪽 기록을 확인할 수 없다 |

PG 조회와 우리 결제 상태의 조합만으로는 응답 유실과 내부 반영 실패를 가를 수 없다. 둘 다 "PG SUCCESS + 우리 PENDING"을 만든다. 경계 로그까지 봐야 한다.

증거가 부족하면 "미확정"이라고 적고 무엇이 없어서 못 가르는지 함께 적는다. 예: "PG 가 왜 3초를 넘겼는지는 우리 쪽 증거로 알 수 없다(PG 호출 구간에 추적 없음)."

## 5. 조치

### 5-1. 조치 전제

- 2절 보존을 마쳤다.
- 대상이 3절에서 고정한 목록 안에 있다.
- 4절에서 PG 조회 결과를 확인했다.
- 대사 API 는 **주문 주인 계정**(`X-USER-ID`)으로만 부를 수 있다. 다른 사용자의 주문은 404 로 거절된다.

### 5-2. 조치

```bash
curl -s -X POST "localhost:8080/api/v1/orders/{orderId}/reconcile" -H "X-USER-ID: {userId}"
```

대사 API 는 PG 에 같은 주문을 한 번 조회한다. 주문번호·금액이 우리 결제와 같을 때만 정상 콜백과 같은 반영 경로로 결과를 적용한다. 결제를 새로 만들지 않는다.

하지 않는 것:

| 하지 않는 것 | 이유 |
| ---- | ---- |
| 결제 확정 API 다시 호출 | 주문이 이미 CONFIRMED 라 409 로 거절된다. 막혀 있어도 재결제 시도로 해결하지 않는다 |
| PG 에 결제 생성 다시 요청 | timeout 은 거래가 없다는 뜻이 아니다. 중복 결제가 생긴다 |
| DB 에서 PENDING 을 SUCCESS/FAILED 로 직접 수정 | 재고 예약·Outbox·구매 활동이 따라오지 않고 외부 거래와 어긋날 수 있다 |
| 앱 재시작으로 해결 시도 | 외부 거래와 DB 상태는 재시작으로 바뀌지 않는다 |
| read-timeout 을 늘려 해결 시도 | 앞으로 생길 건수를 줄이는 완화일 뿐이고 이미 PENDING 인 주문은 그대로다 |

### 5-3. 중단 조건

| 대사 결과 | 의미 | 다음 행동 |
| ---- | ---- | ---- |
| `APPLIED` | PG 최종 결과(SUCCESS 또는 FAILED)를 주문·금액 확인 뒤 반영했다 | 6절 복구 확인 |
| `NOTHING_PENDING` | 이미 끝난 결제다(대사·콜백으로 반영됨). 바뀐 것 없음 | 6절 복구 확인 |
| `STILL_PENDING` | PG 도 아직 처리 중이다. 한 번만 조회하고 그대로 뒀다 | 상태 보존. 시간을 두고 다시 대사한다. 계속되면 7절 기준으로 넘긴다 |
| `TRANSACTION_NOT_FOUND` | PG 에 그 주문의 거래가 없다. "결제 안 됨"이 아니라 "확인 불가" | 상태 보존. 결제 실패로 처리하지 않는다. PG 사에 주문번호로 확인을 요청한다 |
| `MISMATCH` | PG 거래의 주문 또는 금액이 우리 결제와 다르다. SUCCESS 여도 반영하지 않았다 | 상태 보존. 즉시 사람에게 넘긴다. 금액 불일치는 별도 사고로 다룬다 |
| `MULTIPLE_PENDING` | 우리 쪽에 같은 주문의 PENDING 결제가 둘 이상이다. 조회하지 않았다 | 상태 보존. 사람에게 넘긴다 |
| 5xx 응답 (조회 실패) | PG 조회 자체가 실패했다(timeout·5xx). 상태는 그대로다 | 상태 보존. PG 상태 확인 뒤 다시 대사한다 |

반영은 조회가 끝난 뒤 짧은 트랜잭션에서 결제를 다시 읽고 아직 PENDING 일 때만 한다. 대사와 늦은 콜백이 겹쳐도 두 번 반영되지 않는다.

## 6. 복구 확인

3절에서 고정한 **같은 주문 목록**을 새로 조회해 확인한다. 다른 주문을 섞어 건수를 채우지 않는다.

| 확인 | 기대 | 방법 |
| ---- | ---- | ---- |
| 결제 | 대상 전부 SUCCESS 또는 FAILED, PENDING 0 | 4-2 DB 조회 |
| PG 거래 | 조치 전과 같은 거래 키 | PG 조회 결과를 2절 기록과 비교 |
| 결제 생성 추가 | 0 | 대상 주문의 `PG 결제 요청` 로그 줄 수가 조치 전과 같다 |
| 재고 | SUCCESS 는 예약 COMMITTED, FAILED 는 RELEASED. 재고가 다시 줄지 않음 | 재고 예약 조회, 상품 재고 |
| Outbox | SUCCESS 주문마다 1건 | `outbox_event` 조회 |
| 구매 활동 | SUCCESS 주문마다 품목 수만큼 1번씩 | `product_purchase_activity` 조회 |
| 지표 | 대상 주문이 빠진 값(대상만 있었다면 0) | Prometheus `payment_pending_stale` |
| 반복 호출 | 같은 대사를 다시 불러도 `NOTHING_PENDING`, 위 수치 그대로 | 대사 API |

복구됐다고 말하지 않는 경우:

- 대상 중 하나라도 `STILL_PENDING`, `TRANSACTION_NOT_FOUND`, `MISMATCH`, `MULTIPLE_PENDING` 으로 남았다.
- 결제 생성 로그가 늘었다.
- 결제는 SUCCESS 인데 Outbox·구매 활동이 없다(후속 경로 끊김).

랭킹은 주간 배치가 돌아야 반영된다. 이미 공개된 스냅샷은 고치지 않고 복구된 구매는 그 주의 다음 실행이나 늦은 revision 에 들어간다. 주간 소속은 결제 성공을 처음 기록한 시각(`confirmedAt`)이다. 복구한 주문은 대사한 시각이 그 값이고 덮어쓰지 않는다.

## 7. 재진입

- 대사가 중간에 실패해도 상태는 PENDING 그대로다. 반영은 한 트랜잭션이라 결제만 바뀌고 재고 예약이나 Outbox 가 빠지는 중간 상태가 남지 않는다.
- 다시 시도하는 방법은 같은 대사 API 를 다시 부르는 것뿐이다. 이미 반영된 주문은 `NOTHING_PENDING` 으로 끝나고 재고·Outbox 가 다시 늘지 않는다.
- 재시도 전 2절 기록을 덮어쓰지 않는다. 새 시도는 새 파일에 남긴다.
- `STILL_PENDING` 이 반복되면 PG 사의 정산 기준 시간을 넘긴 시점에 사람에게 넘긴다. 이번 실험에서는 기준 시간을 정하지 않았다.
- `TRANSACTION_NOT_FOUND`, `MISMATCH`, `MULTIPLE_PENDING` 은 재시도로 바뀌지 않는다. 바로 사람에게 넘긴다.

## 8. 후속

**원인 (이번 실험)**: PG 는 거래를 SUCCESS 로 만들었는데 우리 서버가 read-timeout(3초) 안에 응답을 받지 못해 결제가 PENDING 으로 남았다. 콜백도 오지 않아 저절로 고쳐지지 않았다. PG 의 `requestedAt` 이 우리 timeout 보다 늦다. PG 는 우리가 포기한 뒤에 거래를 만들었다. 내부 반영 실패는 아니다(반영 시도 흔적 없음).

**빠져 있던 방어**

- 본인 주문 대사 API 가 없었다. 과제는 W5 에 있다고 전제했다. 이번에 만들었다.
- 결제 성공 → Outbox 연결이 W8 커밋에서 빠져 있어 구매 활동·랭킹으로 이어지지 않았다. W7 의 연결을 복원했다.
- 미확인 결제를 세는 업무 지표가 없었다. 이번에 추가했다.

**개선 후보 (검증 필요)**

- read-timeout 재검토: 숫자는 근거 없이 정하지 않는다. W5 `docs/week5/slow-pg-capacity.md` 의 스레드 점유 결과와 함께 본다. 완화일 뿐 대사를 대신하지 않는다.
- 자동 대사: 기준 시간이 지난 PENDING 을 주기적으로 찾아 같은 대사 로직을 부른다. 대상 선정, 한 번에 처리할 양, 반복 PENDING 상한, 서버 여러 대의 중복 조회를 정해야 한다.
- `STILL_PENDING` 이 반복될 때 사람에게 넘길 기준 시간.

**확인하지 않은 위험**

- 분산 추적: PG 호출·콜백·Kafka 구간의 trace 가 이어지지 않는다. PG 안에서 얼마나 걸렸는지 볼 수 없다.
- 복수 거래: PG 조회가 주문당 거래 하나만 돌려줘서 같은 주문의 거래가 여럿인 경우를 우리 쪽에서 알아낼 수 없다.
- 쿠폰: 사고 테스트에서 쿠폰을 쓰지 않아 "쿠폰 불변"은 확인하지 않았다.
- 끝까지 한 번에 흐르는 테스트: 후속 경로(Outbox → Kafka → 구매 활동 → 랭킹)는 구간별 테스트와 계약 파일로 이었다. 한 실행에서 실제 Kafka 를 거쳐 랭킹까지 흐르는 자동 테스트는 없다. 로컬에서 한 번 손으로 확인했다.
- 운영 PG·실계정·부하 조건은 확인하지 않았다. 로컬 통제 실험 결과다.
