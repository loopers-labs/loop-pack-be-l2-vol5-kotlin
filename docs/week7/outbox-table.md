# 아웃박스 테이블 정의

### outbox_event

| 컬럼             | 타입                      | 제약              | 내용                                         |
| ---------------- | ------------------------- | ----------------- | -------------------------------------------- |
| `id`             | `bigint`                  | PK, auto_increment |                                              |
| `event_id`       | `varchar(100)`            | not null          | 컨슈머가 중복을 가르는 멱등 키               |
| `topic`          | `varchar(100)`            | not null          | 보낼 토픽                                    |
| `event_type`     | `varchar(50)`             | not null          | 어떤 이벤트인지. 예: `OrderConfirmed`        |
| `schema_version` | `int`                     | not null          | 페이로드 계약의 버전                         |
| `payload`        | `text`                    | not null          | 직렬화된 이벤트 JSON                         |
| `message_key`    | `varchar(100)`            | not null          | 카프카 메시지 키 = 파티션 키                 |
| `status`         | `enum('PENDING','SENT')`  | not null          | 생성 시 `PENDING`, 브로커 ack 후 `SENT`      |
| `created_at`     | `datetime(6)`             | not null          | 행을 쓴 시각                                 |

| 인덱스                       | 컬럼         |
| ---------------------------- | ------------ |
| `idx_outbox_event_status_id` | `status, id` |
| `idx_outbox_event_event_id`  | `event_id`   |
