# 주문 확정 → 발행 → 소비에 100 VU 를 걸어본 기록

카프카 설정이 부하에서 돌아가는지 확인하려고 돌린 것이다. 빌드 간 성능 비교는 아니다.

## 조건

| 항목 | 값 |
|---|---|
| 프로듀서 | `commerce-api` @ `5d02b7b` · :8097 |
| 컨슈머 | `commerce-streamer` @ `5d02b7b` · :8093 |
| 스크립트 | `docs/week6/k6-order-events.js` |
| 부하 | 100 VU · 30초 · `constant-vus` |
| 경로 | 주문 생성 → 확정(POINT) → 발행 → 소비 → `product_purchase_activity` 저장 |
| 브로커 | 로컬 단일 KRaft (`docker/infra-compose.yml`) |
| 토픽 | `order.events.v1` · 파티션 1 · RF 1 (자동 생성) |
| 컨슈머 그룹 | `purchase-activity`(저장) · `stock-tracking`(로그만) |

포인트 결제를 쓴다. 카드는 PG 가 미확정을 돌려주므로 확정만으로는 발행이 일어나지 않는다.
발행은 결제가 SUCCESS 로 반영될 때 `PaymentSettlement.succeed()` 에서 일어난다.

## 서빙

| 항목 | 값 |
|---|---|
| confirm 성공 | 18,195 |
| confirm 처리량 | 597/s |
| confirm 거절 (4xx) | 57 |
| 5xx | 0 |
| confirm p50 | 74.9ms |
| confirm p95 | 168.0ms |
| confirm p99 | 297.5ms |
| confirm max | 903.4ms |

컨슈머가 같이 돌아도 API 처리량이 떨어지지 않았다. 컨슈머를 띄우지 않고 잰 앞선 두 번이
501/s · 678/s 였고 이번이 597/s 로 그 사이다. 소비가 밀려도 쓰는 쪽이 막히지 않는다.

## lag

```
 t(s)   committed    log-end     lag
    0       54,016     54,208       192
    7       54,854     59,919     5,065
   15       58,796     65,476     6,680   ← 최대
   25       67,168     69,344     2,176
   33       71,586     72,211       625
   36       72,211     72,211         0   ← 소진
```

부하 중 최대 6,680 까지 쌓였고, k6 종료 후 6초 만에 0 이 됐다.

쌓인 이유는 처리 속도가 모자라서가 아니라 **묶어서 가져가기 때문**이다.
`fetch.min.bytes=1MiB` · `fetch.max.wait.ms=5000` 이라 한 번 폴링할 때까지 메시지가 모인다.
committed 가 3~5초 간격으로 계단처럼 뛰는 것이 그것이다. 유입(~600/s)보다 빼내는 속도가
빨라서 단조 증가하지 않고 톱니로 오르내린다.

## DB

| 항목 | 값 |
|---|---|
| `product_purchase_activity` 증가 | 18,195 행 |
| 확정 성공 수 | 18,195 |

누락도 중복도 없었다. 다만 **중복 전달이 없었기 때문**이다. 지금 유일 제약이 없어서 같은
메시지가 두 번 오면 그대로 두 행이 된다.

## 이 측정이 말하지 못하는 것

- **빌드 간 비교가 안 된다.** 같은 빌드를 여러 번 쟀는데 처리량이 501 ~ 678/s 로 35% 흔들렸다.
  머신에 JVM 이 여럿 떠 있고 MySQL 을 공유한다. 워밍업도 주지 않았다.
- **발행 자체의 비용을 모른다.** 발행을 뺀 같은 코드와 교대로 재야 갈린다.
- **중복 전달을 겪지 않았다.** 재전달이 없었으므로 멱등이 검증된 것이 아니다.
- **복제 보장을 확인하지 못했다.** 브로커가 하나라 ISR 이 리더뿐이고, `acks` 선택이
  무엇을 지키는지는 이 실험으로 증명되지 않는다.
- **파티션이 1개다.** 자동 생성된 토픽이라 키가 파티션을 고르는 효과가 나타나지 않는다.
  모든 메시지가 같은 줄로 갔고, 컨슈머 병렬성도 1 에 묶였다.

## 재현

```
# 상품 id
docker exec docker-mysql-1 sh -c 'mysql -uroot -proot -D loopers -N -e "
  select id from product where status=\"SELLING\" and deleted_at is null and stock >= 50 limit 5000
"' | python3 -c 'import sys,json; print(json.dumps([int(l) for l in sys.stdin if l.strip()]))' \
  > product-ids.json

# 부하
VUS=100 DURATION=30s BASE_URL=http://localhost:8097 IDS_FILE=product-ids.json \
  k6 run docs/week6/k6-order-events.js

# lag
docker exec kafka kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --describe --group purchase-activity
```
