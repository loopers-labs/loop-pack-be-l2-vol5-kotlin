# 상품 랭킹 테이블 정의

### product_ranking

| 컬럼           | 타입          | 제약               | 내용                                           |
| -------------- | ------------- | ------------------ | ---------------------------------------------- |
| `id`           | `bigint`      | PK, auto_increment |                                                |
| `snapshot_id`  | `varchar(30)` | not null           | `연도_월_주차`, 리비전 없음 (예 `2026_11_2`)   |
| `period_start` | `date`        | not null           | 주 시작일, 월요일                              |
| `product_id`   | `bigint`      | not null           | 상품                                           |
| `score`        | `bigint`      | not null           | 점수, 1 이상                                   |
| `ranking`      | `int`         | not null           | 순위, 1 부터                                   |
| `created_at`   | `datetime(6)` | not null           | BaseEntity                                     |
| `updated_at`   | `datetime(6)` | not null           | BaseEntity                                     |
| `deleted_at`   | `datetime(6)` |                    | BaseEntity, null 이면 삭제 안 됨               |

| 이름                                   | 종류   | 컬럼                        |
| -------------------------------------- | ------ | --------------------------- |
| `uk_product_ranking_snapshot_product`  | unique | `snapshot_id`, `product_id` |
