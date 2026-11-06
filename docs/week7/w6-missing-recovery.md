# W6 누락을 아웃박스로 되살린 기록

아웃박스가 없던 W6 코드로 확정됐지만 이벤트가 나가지 못한 주문을, W7 의 아웃박스와 릴레이로
같은 사건 그대로 다시 내보낼 수 있는지 확인한 기록이다.
숫자는 모두 로컬 1회 실행(2026-11-05)의 관찰값이다.

## 격리

| 항목 | 값 |
|---|---|
| DB | 새 컨테이너 `w7-recovery-mysql` (`mysql:8.0`, 3307). 원본 `docker-mysql-1` 은 쓰지 않았다 |
| 토픽 | 새 토픽 `order.events.recovery` |
| 준비 앱 | W6 api `1538a9f` (아웃박스 없음, 커밋 뒤 직접 발행만) · W7 streamer |
| 복구 앱 | W7 relay · W7 streamer. W6 api 는 내렸다 |
| PG | 공용 `pg-simulator`. 콜백이 `host.docker.internal:8099` 로 오므로 W6 api 를 8099 에 띄웠다 |

## 준비: W6 코드로 상태 만들기

| 구분 | 주문 | 만든 방법 | 결과 |
|---|---|---|---|
| 소비 완료 | 2·3·4 (포인트), 1762300000·1762300001 (카드) | 브로커가 살아 있을 때 확정 | 발행 · 구매 활동 반영 |
| **W6 누락** | 1762300002~04 (포인트), 1762300005·06 (카드) | 브로커 셋을 내린 채 확정 | 결제 SUCCESS · 발행 실패 5건 · 구매 활동 없음 |
| 대조군 | 1 | 확정하지 않음 | DRAFT |
| 대조군 | 5 · 6 | 카드 확정 중 PG 가 409 | 주문 CONFIRMED · 결제 PENDING |

격리 DB 는 주문 id 가 1 부터 시작해 시뮬레이터에 남아 있던 예전 주문 5·6 과 겹쳤다. 그 두 건이
409 로 거절돼 결제 미확정 대조군이 됐다. 이후 주문은 `auto_increment` 를 1762300000 으로 올려 피했다.

브로커를 다시 띄운 뒤에도 누락 5건은 소비되지 않았다. W6 에는 다시 보낼 근거가 남지 않는다.

### 준비의 PG 호출

| 주문 | 호출 | 결과 |
|---|---|---|
| 1762300000 · 01 · 05 · 06 | 4 | 접수 · 콜백 SUCCESS |
| 5 · 6 | 2 | 409 거절 |
| 포인트 주문 | 0 | — |
| **합** | **6** | |

## 복구: 백필 + 릴레이

W6 의 누락분은 아웃박스에 행이 없어 릴레이가 찾을 수 없다. 운영 코드를 늘리지 않고 SQL 로
아웃박스에 넣었다. 대상은 "확정됐고 결제가 SUCCESS 인데 아웃박스에 행이 없는 주문" 전부다.

```sql
insert into outbox_event (event_id, topic, event_type, schema_version, payload, message_key, status, created_at)
select concat('order-confirmed:v1:', o.id),
       'order.events.recovery',
       'OrderConfirmed',
       1,
       json_object(
           'eventId', concat('order-confirmed:v1:', o.id),
           'orderId', o.id,
           'userId', o.user_id,
           'confirmedAt', date_format(p.succeeded_at, '%Y-%m-%dT%H:%i:%s.%fZ'),
           'items', (select json_arrayagg(json_object('productId', r.product_id, 'quantity', r.quantity))
                     from stock_reservation r
                     where r.order_id = o.id and r.status = 'COMMITTED'),
           'eventType', 'OrderConfirmed',
           'schemaVersion', 1
       ),
       cast(o.id as char),
       'PENDING',
       utc_timestamp(6)
from orders o
join payment p on p.order_id = o.id and p.status = 'SUCCESS'
where o.status = 'CONFIRMED'
  and not exists (select 1 from outbox_event e where e.event_id = concat('order-confirmed:v1:', o.id))
order by o.id;
```

- eventId 는 W6 가 쓰던 `order-confirmed:v1:{orderId}` 그대로다. 그래서 같은 사건이다.
- items 는 W7 이 확정 이벤트를 만들 때처럼 COMMITTED 예약에서 읽는다.
- `confirmedAt` 은 결제 성공 시각이다. DB 에 UTC 로 저장돼 있어 `Z` 를 붙인다.
- 이미 소비된 주문도 대상에 넣었다. 소비 기록은 streamer 쪽에 있어 api 의 행만으로는 가를 수 없고,
  컨슈머가 eventId 로 거른다.

### 결과

| 항목 | 값 |
|---|---|
| 백필 | 10 행 = 소비 완료 5 + 누락 5. 대조군 1·5·6 은 빠졌다 |
| 릴레이 | `relayed=10` · 실패 0 · 10 행 전부 SENT |
| 누락 5건 | 구매 활동 새로 반영. 처리 기록 5 → 10 |
| 소비 완료 5건 | 컨슈머가 "이미 반영한 이벤트" 로 건너뜀. 구매 활동 그대로 |
| 충돌 · DLT | 0 · 0 |

복구 뒤 구매 활동:

```
2           p1 ×1      1762300002  p1 ×2
3           p2 ×2      1762300003  p2 ×2
4           p3 ×3      1762300004  p3 ×2
1762300000  p1 ×1      1762300005  p2 ×1
1762300001  p2 ×1      1762300006  p3 ×1
```

## 복구가 건드리지 않은 것

복구 전후로 아래를 떠서 비교했다. **diff 가 비었다.**

| 대상 | 뜬 값 |
|---|---|
| 주문 | id · status · version · updated_at |
| 결제 | status · payment_key · succeeded_at · updated_at |
| 예약 | product_id · quantity · status · updated_at |
| 재고 · 포인트 | quantity · balance · updated_at |
| PG | 주문마다 시뮬레이터 조회 · transactionKey · status · requestedAt |

| | PG 호출 | 주문 재확정 | 예약 추가 소비 |
|---|---|---|---|
| 준비 | 6 | — | — |
| **복구** | **0** | **없음** | **없음** |

복구는 DB 를 읽어 아웃박스에 쓰고 릴레이가 보낼 뿐이라 결제·주문·예약 경로를 타지 않는다.

## 한계

- 백필 SQL 은 W6 이벤트의 모양을 손으로 옮긴 것이다. 이벤트 계약이 바뀌면 SQL 도 같이 고쳐야 한다.
- 결제 미확정(5·6) 주문은 백필하지 않는다. 결제가 나중에 확정되면 그때 W7 경로로 적재된다.
- 대상 범위를 기간으로 자르지 않았다. 운영 DB 에서는 주문 수만큼 아웃박스 행과 재발행이 생긴다.
- 격리 DB 1회 실행이다. 원본 DB 의 기존 누락분에는 돌리지 않았다.
