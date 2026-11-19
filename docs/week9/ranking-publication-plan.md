# 주간 랭킹 공개 상태 기획

2026-11-16 기획, 같은 날 구현했다. 테이블 정의는 `product-ranking-pointer-table.md` 에 있다.

## 배경

지금은 랭킹 배치가 중간에 죽으면 API 가 일부만 쓰인 랭킹을 완성본처럼 보여 준다.

- API 는 `product_ranking` 에 그 주 행이 있으면 `PUBLISHED` 로 판단한다.
- 상품 300 개를 100 개씩 쓰다 100 개 뒤에 죽으면, 다시 돌릴 때까지 100 개짜리 랭킹이 `PUBLISHED` 로 보인다.
- 행 100 개가 "원래 100 개짜리 주"인지 "300 개 중 100 개만 쓰인 주"인지 구분할 기록이 없다.
- 구매 0 건인 주와 아직 배치가 안 돈 주도 둘 다 `NOT_PUBLISHED` 로 보인다.

## 목표와 범위

주마다 랭킹 작업의 상태를 포인터 한 줄로 남기고, API 는 이 상태로 완성 여부를 판단한다.

- 완성됐는지를 기록한다. 확인을 통과한 뒤에만 `PUBLISHED` 가 된다.
- 실패해도 기록한다. 상태는 `FAILED` 가 된다.
- 이 기록을 근거로 미완성일 때의 API 정책을 고른다.

과제와의 관계: 실습 문서 p.8 은 "내부 상태 이름은 자유롭게 정하되, 완성 조건과 공개 pointer 변경은 하나의 DB TX 에서 보호"하라고 한다. 실패 기록은 요구가 아니지만 충돌하지도 않는다.

범위 밖: 늦은 구매와 리비전(r2) 교체, 같은 주의 동시 실행 제어.

`product_count` 는 공개 tasklet 이 확인을 통과한 개수만 담고, 실패하면 비워 둔다. 개수는 `StepExecution.writeCount` 가 아니라 `product_ranking` 행 수를 센다. writeCount 는 실행 한 번분이라 재시작하면 누적과 달라진다.

## 잡 흐름

```
beforeJob            IN_PROGRESS 기록
1. 스냅샷 tasklet    입력 고정
2. 랭킹 chunk 스텝   100 개씩 commit
3. 공개 tasklet      행 수 확인, TX 하나. PUBLISHED + 개수
afterJob             COMPLETED 가 아니면 FAILED 로 변경, PUBLISHED 면 건드리지 않음
```

랭킹 스텝이 실패하면 잡이 거기서 멈춰서 공개 tasklet 은 실행되지 않고, afterJob 만 돌아 `FAILED` 로 바꾼다. 같은 파라미터로 다시 돌리면 스냅샷은 건너뛰고 랭킹은 멈춘 곳부터 잇는다.

## 상태 전이

```
행 없음 ──beforeJob──▶ IN_PROGRESS ──공개 tasklet──▶ PUBLISHED (다시 실행해도 유지)
                        │      ▲
              실패(afterJob)  같은 파라미터로 재실행
                        ▼      │
                         FAILED
```

`beforeJob` 과 `afterJob` 의 SQL 에는 모두 `status <> 'PUBLISHED'` 조건이 붙는다. 그래서 재실행하거나 배치 기록이 실패로 남아도 공개된 랭킹은 바뀌지 않는다. `PUBLISHED` 로 바꾸는 건 공개 tasklet 뿐이고, 행 수 확인과 같은 TX 에서 바꾼다.

## API 응답 정책

API 는 포인터를 먼저 읽고, `PUBLISHED` 일 때만 랭킹 행을 완성본으로 보여 준다.

| 상태          | 랭킹 행     | 응답                                   |
| ------------- | ----------- | -------------------------------------- |
| 행 없음       | -           | `NOT_PUBLISHED`, 빈 items              |
| `IN_PROGRESS` | 있거나 없음 | `IN_PROGRESS`, 지금까지 쓴 items       |
| `FAILED`      | 있거나 없음 | `IN_PROGRESS`, 지금까지 쓴 items       |
| `PUBLISHED`   | 1 행 이상   | `PUBLISHED`, 삭제 상품 빼고 `limit` 만큼 |
| `PUBLISHED`   | 0 행        | `PUBLISHED`, 빈 items (구매 0 건인 주) |

- 미완성(`IN_PROGRESS`, `FAILED`)이면 API 는 `IN_PROGRESS` 로 응답하고, 지금까지 commit 된 랭킹 행을 보여 준다. 삭제 상품 제외와 `limit` 은 `PUBLISHED` 와 같이 적용한다. 받는 쪽은 `IN_PROGRESS` 면 미완성인 걸 알고 써야 한다.
- 구매 0 건인 주는 과제대로 `PUBLISHED` 와 빈 items 다. 공개 tasklet 은 스냅샷 0 = 랭킹 0 으로 확인을 통과하고, API 는 `snapshotId` 를 랭킹 행이 아니라 포인터에서 가져온다.
- 이번 주가 미완성이라고 지난주 랭킹을 대신 보여 주지 않는다. 과제는 "임의의 이전 주를 요청한 주의 결과로 반환하지 않는다"고 한다.

검토한 선택지:

| 정책                   | 응답                             | 결정                 |
| ---------------------- | -------------------------------- | -------------------- |
| 숨기기                 | `NOT_PUBLISHED`, 빈 items        | 안 고름. 과제 기본안 |
| 진행 중이라고만 알리기 | `IN_PROGRESS`, 빈 items          | 안 고름              |
| 표시하고 보여 주기     | `IN_PROGRESS`, 지금까지 쓴 items | 고름                 |

## 예외 경우

### 공개는 commit 됐는데 배치 기록이 실패로 남는 경우 (발제 p.6)

1. 공개 tasklet 이 `PUBLISHED` 로 바꾸고 TX 를 commit 한다.
2. 그 직후 Spring Batch 가 스텝과 잡 상태를 COMPLETED 로 따로 저장하다 DB 연결이 끊긴다.
3. 포인터는 `PUBLISHED` 인데 `BATCH_JOB_EXECUTION` 은 FAILED 나 STARTED 로 남는다.

대응: `afterJob` 은 `status <> 'PUBLISHED'` 일 때만 `FAILED` 로 바꾼다. 이미 공개됐으면 배치 기록보다 공개 상태를 믿는다.

### 프로세스가 강제로 죽는 경우 (kill, 서버 다운)

- `afterJob` 이 실행되지 않아서 상태가 `IN_PROGRESS` 에 멈춘다. `BATCH_JOB_EXECUTION` 도 STARTED 로 멈춘다.
- 그래서 `IN_PROGRESS` 가 오래 남아 있으면 실제로는 죽은 것일 수 있다. 이때 `product_count` 도 비어 있다.

### 이미 공개된 주를 다시 실행하는 경우

- `beforeJob` 도 `PUBLISHED` 를 `IN_PROGRESS` 로 덮지 않는다. 덮으면 공개된 랭킹이 API 에서 사라진다.

### 공개 tasklet 이 실패하는 경우

공개 tasklet 은 TX 하나라서, 어디서 실패하든 전부 rollback 되고 아무것도 바뀌지 않는다. 실습 문서 p.8 의 "공개 TX 중 오류가 나면 새 pointer 나 일부 ranking 만 노출하지 않습니다"가 이 부분이다.

| 어디서 실패했나                          | 결과                                                                         |
| ---------------------------------------- | ---------------------------------------------------------------------------- |
| 개수 확인이 안 맞음 (스냅샷 300, 랭킹 299) | `check` 가 예외를 던져 rollback. 상태는 `IN_PROGRESS`, `afterJob` 이 `FAILED` 로 바꿈 |
| `PUBLISHED` 로 바꾸는 UPDATE 중 DB 오류  | rollback 돼서 상태 변경이 없던 일이 됨. 이후는 위와 같음                     |
| commit 은 됐는데 그 뒤 배치 기록 저장 실패 | `PUBLISHED` 유지. 이 절 첫 번째 경우와 같음                                  |

- 같은 파라미터로 다시 돌리면 완료된 스냅샷 스텝과 랭킹 스텝은 건너뛰고 공개 tasklet 만 다시 실행된다. 같은 개수를 확인하고 같은 `PUBLISHED` 를 쓰니 여러 번 실행해도 결과가 같다.
- 개수가 계속 안 맞으면 다시 돌려도 계속 실패한다. 일시적인 오류가 아니라 데이터 문제라 사람이 스냅샷과 랭킹 행 수를 직접 비교해야 한다.
- DB 장애로 실패했다면 이어지는 `afterJob` 도 못 쓸 수 있다. 그러면 상태가 `IN_PROGRESS` 에 남아서 강제 종료와 같은 결과가 된다.

## 정한 것

- 실패한 주를 다시 실행하면 이어서 한다. `requestDate` 를 identifying 파라미터에서 빼서, 같은 주는 항상 같은 JobInstance 가 되게 한다. 바꿀 곳은 스케줄러와 테스트의 잡 파라미터 두 곳이다.
  - 실패한 instance 를 다시 실행하면 멈춘 곳부터 잇는다.
  - 성공한 instance 를 다시 실행하면 Spring Batch 가 `JobInstanceAlreadyCompleteException` 으로 막는다.

## 미룬 것

- decider 의 "이미 끝남" 기준. 같은 주가 다른 JobInstance 로 실행될 때만 필요하다. 그런 경우는 r2 를 만들 때라서, r2 작업에서 다시 본다.

## 테스트 시나리오

| 상황                      | 기대 상태                          | 기대 API                           |
| ------------------------- | ---------------------------------- | ---------------------------------- |
| 정상 실행 후              | `PUBLISHED`, 개수 = 스냅샷 상품 수 | `PUBLISHED`, 전체 랭킹             |
| 랭킹 스텝 중간 실패 후    | `FAILED`, 개수 비어 있음           | 완성본이 보이지 않음               |
| 같은 파라미터로 재실행 후 | `PUBLISHED`, 개수 = 스냅샷 상품 수 | `PUBLISHED`, 정상 실행과 같은 랭킹 |
| 공개된 주를 다시 실행     | `PUBLISHED` 유지                   | 그대로                             |
| 구매 0 건인 주            | `PUBLISHED`, 개수 0                | `PUBLISHED`, 빈 items              |
