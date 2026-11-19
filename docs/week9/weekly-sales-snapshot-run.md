# 주간 판매 스냅샷 수동 실행

스케줄러로 매주 돌리거나, 레포 루트에서 gradle 로 잡을 직접 띄운다.

## 스케줄

```bash
./gradlew :apps:commerce-batch:bootRun --args='--job.name=weeklySalesSnapshotJob --spring.batch.job.enabled=false --ranking.schedule.enabled=true'
```

- 배치 앱이 종료하지 않고 떠 있으면서, UTC 월요일 01:30 (KST 월요일 10:30) 마다 잡을 실행한다.
- 그날 UTC 날짜 하나로 `requestDate` 와 `snapshotId` 를 함께 정한다. 그래서 스케줄 실행에서는 두 값이 어긋나지 않는다.
- `ranking.schedule.enabled` 가 없으면 지금처럼 잡 하나를 돌리고 종료한다.

## 직접 실행

```bash
./gradlew :apps:commerce-batch:bootRun --args='--job.name=weeklySalesSnapshotJob requestDate=2026-11-16,java.time.LocalDate,false snapshotId=2026_11_2_r1'
```

- `requestDate` 의 직전 주(월~일)를 집계한다.
- 같은 잡에서 이어서 랭킹을 매긴다. `snapshotId` 는 랭킹을 매길 스냅샷이고, 보통 아래 표의 `requestDate` 에 맞는 값을 넘긴다.

| `requestDate` | 집계 구간 (UTC)         | `snapshot_id`  |
| ------------- | ----------------------- | -------------- |
| `2026-11-16`  | 11/09 00:00 ~ 11/16 00:00 | `2026_11_2_r1` |
| `2026-11-18`  | 11/09 00:00 ~ 11/16 00:00 | `2026_11_2_r1` |
| `2026-11-09`  | 11/02 00:00 ~ 11/09 00:00 | `2026_11_1_r1` |

- local 프로필로 `localhost:3306/loopers` 에 붙는다.

## 다시 실행할 때

- 같은 `requestDate` 로 다시 띄우면 Spring Batch 가 `JobInstanceAlreadyCompleteException` 으로 거부한다.
- 같은 주의 다른 날짜로 띄우면 잡은 성공하지만, 그 주 스냅샷이 이미 있으므로 아무것도 쓰지 않는다.

## 결과 확인

```bash
docker exec docker-mysql-1 mysql -uapplication -papplication loopers -e "
SELECT product_id, SUM(quantity) AS sales_quantity
FROM purchase_activity_snapshot
WHERE snapshot_id = '2026_11_2_r1'
GROUP BY product_id
ORDER BY sales_quantity DESC, product_id
LIMIT 10;"
```

## 실행 시각

주 경계가 UTC 라서 한 주는 KST 월요일 09:00 에 끝난다. 그 전에 그날 날짜로 띄우면 끝나지 않은 주를 집계해 마지막 9시간이 빠진다. 이미 있는 주는 다시 쓰지 않으므로 빠진 채로 남는다. 스케줄은 UTC 월요일 01:30 (KST 월요일 10:30) 에 돌아서 이 문제가 없다. 직접 실행할 때만 주의한다.

## 나중에 다룰 것

랭킹 조회를 먼저 만들고 아래는 미룬다.

- 늦은 구매. 구매기록은 아웃박스 릴레이(기본 30분 주기)와 streamer 를 거쳐 확정보다 늦게 들어온다. 스냅샷을 만든 뒤에 들어온 그 주 구매는 지금 반영되지 않는다.
- r2. 늦은 구매를 담는 방법으로, 그 주를 처음부터 다시 집계해 새 리비전을 만드는 쪽을 생각하고 있다. 다시 집계하면 늦은 구매를 따로 골라낼 필요가 없다. 늦은 구매만 더하는 방식은 구매기록 표기나 시각 비교가 필요하다. 시각 비교는 커밋 지연 틈에서 빠지는 구매가 생기고, 표기는 배치가 streamer 테이블에 쓰게 된다. r2 를 언제 만들지는 실제 지연 분포를 보고 정한다.
- 스케줄 실행이 빠졌을 때. 지금은 매주 월요일 한 번만 돌아서, 그 실행이 빠지면 그 주는 다음 실행에서 채워지지 않는다. 매일 돌리는 방식은 아직 정하지 않았다.
