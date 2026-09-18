# 2주차 Implementation Quest 대조 기록

## 근거와 판정 범위

- 원문: 사용자가 2026-09-17 대화에 붙여 넣은 `Implementation Quest` 전체 본문. URL이나
  저장소 파일은 제공되지 않았으므로 원문을 저장소만으로 다시 열어 검증할 수는 없다.
- 설계: [`commerce-design.md`](commerce-design.md). API 계약은 10장, 저장·정책은 6~13장,
  테스트 근거는 14장에 있다.
- 구현: 기준 revision `963240c504662a00c7e1beda38e63e86e920b192` 대비 2주차 변경.
  기준 revision의 Example API와 이번 25개 신규 API를 구분했다.
- 판정: **확인**은 원문·문서·구현·테스트 근거가 일치한다는 뜻이며, **부분 확인**은
  기능 구현은 확인했지만 원문에서 요구한 검증이나 제출 자료를 저장소에서 모두 확인하지
  못했다는 뜻이다. 현재 스냅샷으로 과거 Red/Green 실행 사실은 재현할 수 없다.

최종 검사: `./gradlew :apps:commerce-api:ktlintCheck :apps:commerce-api:check` 성공.
JUnit XML 기준 20개 suite, 테스트 54건, 실패 0건, 오류 0건, skip 0건이다.
`ArchitectureTest` 1건도 포함된다. Gradle의 `SKIPPED`는 테스트 case skip이 아니라
Kotlin plugin 검사 등 일부 태스크에 표시된 상태다.

## 요구사항별 대조

| 원문 요구 | 설계 문서 | API·구현 | 테스트 근거와 판정 |
|---|---|---|---|
| 버드뷰, 계층 역할·의존, 도메인 관계, 대표 협력 흐름, API 입력·성공·대표 오류, 규칙 기대값, 대안 비교 | 4~10장과 13장, ERD 및 `aggregate-boundaries.svg` | `interfaces / application / domain / infrastructure` 분리 | `ArchitectureTest`와 기능별 테스트. **확인**. 단, 설계 대화에서 AI에 던진 질문과 반례의 역사 자체는 문서 기록으로만 확인 가능 |
| Kotlin ktlint, ArchUnit, AI 작업 규칙 | 5.2장, 14.6장, `AGENTS.md` | `build.gradle.kts`의 ArchUnit 의존성과 `ArchitectureTest` | `ktlintCheck`, `ArchitectureTest`, 전체 `check` 통과. **확인** |
| 대표 도메인 규칙의 Red → Green → Refactor | 14.1장, [`stock-tdd.md`](stock-tdd.md) | `Stock` Value Object | `StockTest`의 현재 기대값과 기록은 확인. 과거 단계별 실패·통과 로그는 현재 저장소에 없으므로 **부분 확인** |
| 고객 브랜드 상세, 상품 목록·상세, 브랜드·좋아요 수 | 10.1장 | `BrandController`, `ProductController`, `CustomerProductQueryService` | `CustomerBrowseMockMvcTest`의 조회·집계·삭제 상품 제외·쿼리 수. **확인** |
| 브랜드 필터·페이지, `latest`·`price_asc`·`likes_desc`, 동률 보조 정렬과 잘못된 입력 | 9.5장, 10.1장 | `JpaProductEntityRepository`, `ProductJpaRepository` | `CustomerBrowseMockMvcTest`의 세 정렬, ID 동률 순서, 필터·페이지·오류. **확인** |
| 좋아요 등록·취소·내 목록, 중복 방지, 삭제 상품의 새 관계 거절·목록 제외·기존 관계 취소 | 6.3장, 10.2장 | `ProductLikeController`와 저장소, `product_likes` 복합 unique | `ProductLikeMockMvcTest`의 멱등성·사용자 격리·삭제 흐름, `CommerceSchemaTest`의 unique 스키마. **확인** |
| 충전·잔액, 1포인트=1원, 양의 정수 입력, 잔액 0 허용, 누락·타입·범위 초과 거절과 기존 값 유지 | 6.4장, 10.3장 | `PointController`, `PointApplicationService`, `PointAccount`·`PointBalance` | `PointMockMvcTest`의 저장 후 조회와 입력 오류, `PointAccountTest`의 overflow·잔액 불변. **확인** |
| 여러 품목 DRAFT 주문, 생성 시 무차감, 중복 품목 합산 또는 거절, 단가·합계 저장 | 6.5장, 10.4장, 12.1장. 합산은 별도 `[사용자 결정]` | `OrderController`, `OrderApplicationService.create`, `Order`/`OrderItem` | `OrderMockMvcTest`의 중복 합산·OrderItem 두 행·재고·잔액 유지·overflow. **확인** |
| 본인 DRAFT 확정, 상품 활성·재고·잔액 재검사, 결제액·결과 저장, 실패 시 전체 상태 유지 | 10.4장, 12.2장 | `OrderApplicationService.confirm`의 단일 트랜잭션 | `OrderMockMvcTest`의 성공, 타인·재확정·재고·포인트 부족 및 삭제 상품 확정 실패 후 DB 재조회. **확인** |
| 내 주문 목록·상세와 관리자 주문 목록·상세 | 10.4·10.7장 | `OrderController`, `AdminOrderController` | `OrderMockMvcTest`의 고객·관리자 조회와 소유권 거절. **확인** |
| 관리자 브랜드·상품 CRUD, 재고 변경, 활성 상품이 남은 브랜드 삭제 거절 | 10.5·10.6장, 12.4장 | `AdminBrandController`, `AdminProductController`, `BrandApplicationService` | `AdminCommerceMockMvcTest`의 CRUD·DB 상태와 **재고 0 활성 상품**이 있어도 브랜드 삭제 거절. **확인** |
| 삭제된 브랜드·상품의 고객 조회·새 주문 제외, 상품 수정·재고 변경 대상 제외, 주문 정보 보존 | 6.1·6.2장, 10장, ADR-004 | soft delete와 활성 대상 조회 | `AdminCommerceMockMvcTest`, `CustomerBrowseMockMvcTest`, `OrderMockMvcTest`의 삭제 후 404·주문 snapshot 유지. **확인** |
| 고객 식별 누락·없는 사용자·타인 리소스, 관리자 ADMIN/USER/미식별 요청과 CSRF | 9.3·9.4장 | `Requester`, `AdminSecurityConfig` | `ProductLikeMockMvcTest`, `OrderMockMvcTest`, `AdminCommerceMockMvcTest`의 인증·소유권·유효 CSRF 요청. **확인** |
| `server.address=127.0.0.1`, 공개 배포·실제 비밀정보 금지 | 2.2장, 9.4장 | `application.yml`의 loopback 설정 | 설정 파일로 **확인**. 실제 배포 여부나 테스트 외 데이터 사용 여부는 저장소에서 **확인 불가** |
| 실제 controller·application·repository·DB MockMvc, 충전 10,000원→7,000원 주문→잔액 3,000원 | 14.4·14.5장 | 위 API 연결 | `OrderMockMvcTest.charge draft confirm and read snapshots end to end`. **확인** |
| 테스트 건수·실패·skip·종료, 기술 글·PR 설명·수업용 CI | 14.6장과 `stock-tdd.md` | `check` 태스크, `.github`에는 workflow 없음 | 검사 건수는 최종 실행 결과로 판정. 별도 기술 글은 저장소에서 **확인 불가**. PR 설명은 GitHub에서 별도 작성 |

## 원문 대조로 바로잡은 해석

- 원문은 중복 주문 상품을 **합산하거나 거절**하도록 선택을 허용한다. 합산은 문서의
  `[사용자 결정]`이며, `[기획서]`가 합산 자체를 직접 요구한 것으로 적지 않는다.
- 원문은 포인트 충전액을 양의 정수로 요구한다. 기존 Jackson의 `Long` 변환은
  `"100"`을 받아들여 충전했다. 회귀 테스트에서 이를 재현한 뒤 포인트 요청에서
  JSON 정수 여부와 `Long` 범위를 직접 확인하도록 고쳤다.
- 재고 0인 활성 상품도 브랜드 삭제를 막아야 한다는 조건은 원문에 직접 명시되어 있다.
- 동시성 잠금·경합 정책은 원문 기본 기능의 요구가 아니며 현재 설계에서는 3주차 범위다.
- 14장의 별도 application·repository 테스트 항목은 각 계층마다 같은 시나리오의 테스트
  클래스를 만들어야 한다는 뜻이 아니다. 실제 MockMvc와 DB 재조회가 같은 경계를
  검증하는 항목은 그 근거를 14장에 연결했다.
