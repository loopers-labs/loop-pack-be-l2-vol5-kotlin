# 구매 활동 스냅샷 테이블 정의

엔티티는 commerce-batch 가 가진다. 예전 합계 사본 `product_sales_snapshot` 은 지웠다.

### purchase_activity_snapshot

그 주에 확정된 구매 활동을 주문·상품 행 그대로 옮긴 사본이다.

| 컬럼           | 타입          | 제약               | 내용                                                    |
| -------------- | ------------- | ------------------ | ------------------------------------------------------- |
| `id`           | `bigint`      | PK, auto_increment |                                                         |
| `snapshot_id`  | `varchar(30)` | not null           | 스냅샷 식별자, `연도_월_주차_r리비전` (예 `2026_11_2_r1`) |
| `period_start` | `datetime(6)` | not null           | 집계 구간 시작 (UTC 월요일 00:00)                       |
| `period_end`   | `datetime(6)` | not null           | 집계 구간 끝, 시작보다 늦다                             |
| `order_id`     | `bigint`      | not null           | 주문                                                    |
| `product_id`   | `bigint`      | not null           | 상품                                                    |
| `quantity`     | `int`         | not null           | 수량, 1 이상                                            |
| `confirmed_at` | `datetime(6)` | not null           | 확정 시각, 구간 안                                      |
| `created_at`   | `datetime(6)` | not null           | BaseEntity                                              |
| `updated_at`   | `datetime(6)` | not null           | BaseEntity                                              |
| `deleted_at`   | `datetime(6)` |                    | BaseEntity, null 이면 삭제 안 됨                        |

| 이름                                          | 종류   | 컬럼                                   |
| --------------------------------------------- | ------ | -------------------------------------- |
| `uk_purchase_activity_snapshot_order_product` | unique | `snapshot_id`, `order_id`, `product_id` |

`snapshot_id` 의 월은 두 자리로 쓴다 (`2026_03_1_r1`). 주차는 월요일부터 일요일까지를 한 주로 센다. 주는 월요일이 속한 달에 들어가고, 그 달의 n 번째 월요일로 시작하는 주가 n 주차다.

| 주 (월~일)   | `snapshot_id`  |
| ------------ | -------------- |
| 10/26 ~ 11/1 | `2026_10_4_r1` |
| 11/2 ~ 11/8  | `2026_11_1_r1` |
| 11/30 ~ 12/6 | `2026_11_5_r1` |

### product_sales_snapshot_meta

한 주·한 리비전마다 한 행이다.

| 컬럼           | 타입          | 제약               | 내용                                              |
| -------------- | ------------- | ------------------ | ------------------------------------------------- |
| `id`           | `bigint`      | PK, auto_increment |                                                   |
| `snapshot_id`  | `varchar(30)` | not null           | `purchase_activity_snapshot` 과 같은 식별자       |
| `period_start` | `datetime(6)` | not null           | 집계 구간 시작                                    |
| `period_end`   | `datetime(6)` | not null           | 집계 구간 끝                                      |
| `revision`     | `int`         | not null           | 식별자의 `r` 뒤 숫자                              |
| `status`       | `varchar(20)` | not null           | `FROZEN`                                          |
| `frozen_at`    | `datetime(6)` | not null           | 고정 시각 (UTC)                                  |
| `row_count`    | `bigint`      | not null           | 사본 행 수, 0 이상                                |
| `checksum`     | `char(64)`    | not null           | 입력 checksum, SHA-256 소문자 hex                 |
| `created_at`   | `datetime(6)` | not null           | BaseEntity                                        |
| `updated_at`   | `datetime(6)` | not null           | BaseEntity                                        |
| `deleted_at`   | `datetime(6)` |                    | BaseEntity, null 이면 삭제 안 됨                  |

| 이름                                      | 종류   | 컬럼          |
| ----------------------------------------- | ------ | ------------- |
| `uk_product_sales_snapshot_meta_snapshot` | unique | `snapshot_id` |
