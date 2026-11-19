# 주간 랭킹 조회 API

```
GET /api/v1/rankings/weekly?periodStart=2026-08-17&limit=10
```

| 파라미터      | 내용                                                                 |
| ------------- | -------------------------------------------------------------------- |
| `periodStart` | 주 시작일. 날짜 형식이 아니면 거절한다. 월요일이 아니면 그 주 월요일로 맞춘다. 없으면 오늘이 속한 주의 월요일이다. |
| `limit`       | 몇 개 보여 줄지. 기본 10, 1 이상 100 이하.                             |

응답에는 `periodStart`, `periodEnd`, `snapshotId`, 입력 고정 시점, 상품별 점수·순위 목록이 들어간다.

- 공개 여부는 포인터 `product_ranking_pointer` 로 판단한다. 정책은 `ranking-publication-plan.md` 에 있다.
  - 포인터가 없으면 `NOT_PUBLISHED` 와 빈 items 다.
  - 포인터가 `IN_PROGRESS` 나 `FAILED` 면 `IN_PROGRESS` 와 지금까지 쓴 items 다.
  - 포인터가 `PUBLISHED` 면 `PUBLISHED` 와 items 다. 구매가 0 건인 주는 빈 items 다.
- 포인터의 `snapshot_id` 는 주 시작일로 만든다 (`연도_월_그 달의 몇 번째 주`, 2026-11-09 → `2026_11_2`). 배치의 `SnapshotWeek` 와 같은 규칙이다.
- 삭제된 상품은 빼고, 순위 간격은 그대로 둔다 (2 위가 삭제되면 1, 3, 4 위로 보인다).

## 응답

| 필드            | 내용                                                                 |
| --------------- | -------------------------------------------------------------------- |
| `status`        | `PUBLISHED`, `IN_PROGRESS`, `NOT_PUBLISHED`                          |
| `periodStart`   | 주 시작일 (월요일)                                                   |
| `periodEnd`     | 주 마지막 날 (일요일)                                                |
| `snapshotId`    | 포인터의 `snapshot_id`. `NOT_PUBLISHED` 면 null                      |
| `inputFrozenAt` | 그 주 랭킹 행 중 가장 이른 `created_at`. 랭킹 행이 없으면 null       |
| `items`         | `productId`, `score`, `ranking`. 순위, 상품 id 순                    |
