# 랭킹 배치 로컬 실행 결과

2026-11-16 08:30 (KST), 로컬 `docker-mysql-1` 의 시드 데이터로 `weeklySalesSnapshotJob` 을 직접 돌렸다.

## 준비

- `product_purchase_activity` 는 시드 구매기록 1,304,884 행이다.
- `product_sales_snapshot` 을 비웠다.
- `product_ranking` 은 엔티티가 commerce-api 에 있어 로컬 DB 에 아직 없었다. 엔티티와 같은 구조로 직접 만들었다.

## 1차 실행

```bash
./gradlew :apps:commerce-batch:bootRun --args='--job.name=weeklySalesSnapshotJob requestDate=2026-11-16,java.time.LocalDate snapshotId=2026_11_2_r1'
```

잡은 `COMPLETED`, 38.4 초 걸렸다.

| 스텝                      | 읽음    | 씀      | 커밋  | 시간   |
| ------------------------- | ------- | ------- | ----- | ------ |
| `weeklySalesSnapshotStep` | -       | 280,912 | 1     | 12.0 초 |
| `weeklySalesRankingStep`  | 280,912 | 280,912 | 2,810 | 26.4 초 |

- 랭킹은 280,912 행이다. `snapshot_id` 는 모두 `2026_11_2`, `period_start` 는 모두 `2026-11-09` 다.
- 순위는 1 부터 202 까지다. 동점이 같은 순위를 받아 순위 수가 행 수보다 적다.
- 점수와 스냅샷 판매 수량이 다른 행은 없다.
- `created_at` 은 UTC 로 들어갔다.

| 상품   | 점수   | 순위 |
| ------ | ------ | ---- |
| 1      | 14,040 | 1    |
| 7,920  | 2,735  | 2    |
| 15,839 | 1,861  | 3    |
| 23,758 | 1,405  | 4    |
| 31,677 | 1,107  | 5    |

## 2차 실행 (같은 주)

```bash
./gradlew :apps:commerce-batch:bootRun --args='--job.name=weeklySalesSnapshotJob requestDate=2026-11-17,java.time.LocalDate snapshotId=2026_11_2_r1'
```

잡은 `COMPLETED`, 0.09 초 걸렸다.

- 스냅샷 스텝은 그 주 스냅샷이 이미 있어 아무것도 쓰지 않았다 (38 ms).
- decider 가 그 주 랭킹이 이미 있다고 보고 랭킹 스텝을 실행하지 않았다.
- 스냅샷과 랭킹 모두 280,912 행 그대로다.
