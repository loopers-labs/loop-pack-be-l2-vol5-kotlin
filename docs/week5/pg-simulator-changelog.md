# PG 시뮬레이터 변경 기록

버전은 `apps/pg-simulator/build.gradle.kts` 의 `version` 한 곳에만 있다.
루트 `build.gradle.kts` 는 버전이 비어 있을 때만 git hash 를 넣으므로, 모듈에서 박은 값이 이긴다.

도는 컨테이너에 직접 물어볼 수 있다.

```
$ curl -s localhost:8082/actuator/info
{"build":{"artifact":"pg-simulator","version":"0.1.0","time":"..."}}
```

산출물 이름은 `pg-simulator.jar` 로 고정돼 있다. 버전을 파일명에 넣으면 Dockerfile 의 COPY 가
매번 바뀌고, 이전 버전 jar 가 `build/libs` 에 쌓여 와일드카드 COPY 가 깨진다.
버전은 파일명이 아니라 `build-info.properties` 로 따라간다.

올리는 기준 — 동작이 바뀌면 minor, 버그 수정이면 patch. 계약이 깨지면 major.
단 `0.x` 동안은 계약 변경도 minor 로 올린다. 아직 이 PG 를 쓰는 코드가 commerce-api 하나이고,
양쪽을 같이 고칠 수 있기 때문이다. `1.0.0` 부터 위 기준을 그대로 적용한다.

| 버전 | 날짜 | 바뀐 것 | commerce-api 에 주는 영향 |
| --- | --- | --- | --- |
| 0.7.0 | 2026-11-26 | 응답 유실 확률 `pg.response-lost-rate` 추가 (기본 1%). 걸린 요청은 `3333` 카드처럼 응답을 늦추고 콜백을 보내지 않는다. 테스트 카드에는 적용하지 않는다. 거절 기본 합계를 1.1% 에서 5% 로 올렸다 (한도초과 2.27% · 잔액부족 2.27% · 분실 0.46%, 비율은 그대로) | 코드 영향은 없다. 다만 기본 설정으로 띄우면 결제의 약 1% 가 read timeout 뒤 미확인으로 남고, 약 5% 가 거절된다. 끄려면 환경변수를 0 으로 준다 |
| 0.6.0 | 2026-11-25 | 테스트 카드 `3333` 추가 — 접수 응답을 `pg.slow-card-delay` 만큼 지연하고 콜백을 보내지 않는다. W10 사고 실험을 로컬 앱에서 재현하려고 넣었다 | 없음. 기존 카드 동작은 그대로다 |
| 0.4.0 | 2026-10-18 | 같은 `orderId` 에 **다른 금액**으로 접수하면 `409` 로 거절한다. 같은 금액 재요청은 그대로 멱등이다. 전에는 두 번째 금액을 읽지도 않고 버려서, 99,999원을 보내도 50,000원 거래를 성공으로 돌려줬다 | `PgClient.request` 가 `409` 를 받을 수 있다. 지금은 `HttpClientErrorException` 으로 올라가 롤백된다 |
| 0.3.0 | 2026-10-18 | 거절 사유를 준다 — `LIMIT_EXCEEDED` · `INSUFFICIENT_BALANCE` · `LOST_OR_STOLEN` · `ISSUER_UNAVAILABLE`. 응답과 콜백에 `declineCode` · `declineReason` 이 함께 실린다. 설정이 단일 `decline-rate` 에서 사유별 확률(`pg.decline.*`)로 바뀌었고 기본 합계는 1.1% 다 | 응답 필드가 늘었을 뿐 기존 필드는 그대로라 `PgClient` 는 무영향. 다만 **거절 사유를 읽지 않고 버리고 있다** |
| 0.2.0 | 2026-10-18 | 결제 수단을 받는다 — `cardType` · `cardNo` 필수. 카드번호는 뒤 4자리만 남기고 마스킹. 테스트 카드로 시나리오 지정(`0000` 거절 · `1111` 지연 · `2222` 콜백유실). 거절 사유(`declineReason`) 추가. 확률 설정은 규칙 없는 카드의 폴백으로 내려감 | **계약 변경** — 접수 요청에 필드 2개가 필수로 늘었다. `PgClient` · `PaymentGateway` 가 `CardInfo` 를 받도록 같이 바뀌었다 |
| 0.1.0 | 2026-10-18 | 첫 구동. 접수(202 PENDING) · 거래키 조회 · orderId 조회 · orderId 멱등 · 지연 후 확정 · 콜백 발신(재발신 없음) · 확률 기반 거절·콜백 드롭 | 없음 (연동 전) |
