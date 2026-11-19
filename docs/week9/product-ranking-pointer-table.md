# 상품 랭킹 포인터 테이블 정의

엔티티는 commerce-api 가 가진다. 상태와 쓰는 시점은 `ranking-publication-plan.md` 에 있다.

### product_ranking_pointer

| 컬럼            | 타입          | 제약               | 내용                                                         |
| --------------- | ------------- | ------------------ | ------------------------------------------------------------ |
| `id`            | `bigint`      | PK, auto_increment |                                                              |
| `snapshot_id`   | `varchar(30)` | not null           | `연도_월_주차`, 리비전 없음 (예 `2026_11_2`)                 |
| `status`        | `varchar(20)` | not null           | `IN_PROGRESS`, `FAILED`, `PUBLISHED`                         |
| `product_count` | `bigint`      |                    | 공개 확인을 통과한 `product_ranking` 행 수, 0 이상. 공개 전엔 null |
| `created_at`    | `datetime(6)` | not null           | BaseEntity                                                   |
| `updated_at`    | `datetime(6)` | not null           | BaseEntity                                                   |
| `deleted_at`    | `datetime(6)` |                    | BaseEntity, null 이면 삭제 안 됨                             |

| 이름                                  | 종류   | 컬럼          |
| ------------------------------------- | ------ | ------------- |
| `uk_product_ranking_pointer_snapshot` | unique | `snapshot_id` |
