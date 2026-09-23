# Stock 도메인 규칙 TDD 기록

## 책임과 기대값

`Stock`은 Product Aggregate 안에서 사용할 불변 Value Object다. 단독으로 저장하거나
HTTP 응답을 만들지 않는다. `Product`가 재고 변경을 수행할 때 `Stock`의 행동을 호출하고
반환된 새 값을 자신의 상태에 반영한다. 이번 단계에서는 아직 Product Root를 만들지 않고
재고 값 자체의 규칙만 domain 단위 테스트로 확인한다.

| 입력 | 기대 결과 |
|---|---|
| 초기 재고 0 | 허용 |
| 초기 재고 -1 | 거절 |
| 재고 5에서 2 차감 | 새 재고 3, 기존 값 5 |
| 재고 5에서 5 차감 | 새 재고 0 |
| 재고 5에서 0 또는 -1 차감 | 입력 거절, 기존 값 5 |
| 재고 5에서 6 차감 | `InsufficientStockException`, 기존 값 5 |
| 최종 재고 8 또는 0으로 조정 | 새 값 반환, 기존 값 유지 |
| 최종 재고 -1로 조정 | 입력 거절, 기존 값 유지 |

## Red → Green → Refactor

실행 명령은 매 단계 `./gradlew :apps:commerce-api:test --tests '*StockTest'`였다.
컴파일에 필요한 최소 선언을 둔 다음, 실제 테스트 메서드가 실패하는지 확인했다.

1. **Red 1**: 차감 검사 없는 구현에서 `Stock(5).decrease(6)`이 예외를 던지지 않아
   1건 중 1건 실패했다. 컴파일·환경 문제가 아닌 기대 동작의 실패였다.
2. **Green 1**: 보유 재고를 넘는 수량이면 `InsufficientStockException`을 던지게 해
   1건을 통과시켰다.
3. **Red 2**: 초기 음수 재고, 0·음수 차감 수량을 추가했다. 7건 중 해당 3건이
   기대한 이유로 실패했다.
4. **Green 2**: 생성 시 재고 0 이상, 차감 시 수량 양수 조건을 검사해 7건을
   통과시켰다.
5. **Red 3**: 최종 재고 조정의 정상·오류 사례를 추가했다. 행동의 선언만 있는
   상태에서 10건 중 신규 3건이 실패했다.
6. **Green 3**: 조정 수량으로 새 `Stock`을 만들고 기존 생성 검사를 재사용해
   10건을 통과시켰다.
7. **Refactor**: 재고 부족 예외를 별도 파일로 분리했다. 기대값은 바꾸지 않았고
   같은 10건을 다시 실행해 모두 통과했다.

부족 재고는 특정 도메인 예외로, 음수·0 입력은 `IllegalArgumentException`으로
구분한다. 이후 HTTP 계층에서 전자는 409, 후자는 400으로 매핑할 예정이다.

## 최종 확인

- `./gradlew :apps:commerce-api:test --tests '*StockTest'`: 10건 통과
- `./gradlew :apps:commerce-api:test --tests '*ArchitectureTest'`: 1건 통과
- `./gradlew :apps:commerce-api:ktlintCheck :apps:commerce-api:check`: 성공
- 전체 commerce-api 테스트: 26건 실행, 실패 0건, skip 0건
