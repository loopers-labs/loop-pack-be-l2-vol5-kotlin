# W9 구현과 검증

요구사항과 결정 근거는 [backend-design.md](backend-design.md), 실행 증거는
[verification-report.md](verification-report.md), 후보1 비교 해석은
[checkpoint-comparison.md](checkpoint-comparison.md)를 참고한다.

## 실행 전제

- Java 21, MySQL 8(InnoDB), 기존 W6 구매 활동 schema와 W9 ranking-schema.sql이 필요하다.
- W9 DDL 원본: [modules/ranking/src/main/resources/ranking-schema.sql](../../modules/ranking/src/main/resources/ranking-schema.sql).
- Spring Batch metadata DDL은 실제 5.2.2 dependency의 `org/springframework/batch/core/schema-mysql.sql`이다.
  테스트는 새 테스트 DB 최초 context에서만 Boot initialize-schema=always를 사용한다.
- 실제 작업에는 JPA ddl-auto=none 또는 validate, Batch initialize-schema=never를 유지한다.
  기존 local/test의 create 설정으로 재시작 DB를 초기화하지 않는다.
- 자동 스케줄·재시도·과거 주 승계는 구현하지 않았다. 운영자가 주/스냅샷/revision을 명시한다.

예시(실행 대상 DB 설정은 기존 datasource 설정으로 제공):

```bash
./gradlew :apps:commerce-batch:bootRun --args='--spring.batch.job.name=weeklyRanking --spring.jpa.hibernate.ddl-auto=none --spring.batch.jdbc.initialize-schema=never periodStart=2026-08-17 snapshotId=weekly-20260817-r1 revision=1,java.lang.Long,true'
```

실패 후 재시작은 위의 세 업무 parameter를 그대로 사용한다. 새 입력 반영은 동일 주에
새 snapshotId와 증가한 revision으로 요청한다. 새 run.id나 현재 시각을 추가하지 않는다.
같은 주의 다른 실행이 실행 중이면 MySQL period lease 획득 실패로 종료한다.
이미 새 revision이 공개된 뒤 오래된 공개를 수행하면 실패하며 pointer를 유지한다.

GET `/api/v1/rankings/weekly?periodStart=2026-08-17&limit=10`은 공개된 정확한 주만 반환한다.
미공개와 0건 공개본은 status/snapshotId로 구분한다. limit 기본10, 범위1~100이다.
현재 상품/브랜드의 노출 조건을 적용하므로 계산 순위에 간격이 생길 수 있다.

## 검증 명령

Docker daemon이 실행된 상태에서 테스트 소유 MySQL/Redis 컨테이너와 단일 embedded Kafka broker를
생성·정리한다. Docker Desktop/Compose를 도구가 자동 시작하지 않는다. 실제 사용자 DB는 사용하지 않는다.

```bash
./gradlew :modules:ranking:test
./gradlew :apps:commerce-batch:test --tests '*RankingJobRestartTest'
./gradlew :apps:commerce-api:test --tests '*WeeklyRankingApiTest'
./gradlew :apps:commerce-api:rankingE2e
./gradlew :apps:commerce-batch:test --tests '*ArchitectureTest' :apps:commerce-api:test --tests '*ArchitectureTest'
./gradlew :modules:ranking:ktlintCheck :apps:commerce-batch:ktlintCheck :apps:commerce-api:ktlintCheck
./gradlew :modules:ranking:check :apps:commerce-batch:check :apps:commerce-api:check :apps:commerce-streamer:check --continue
```

RankingJobRestartTest에 실제 INSERT-only probe와 W9 Job 재시작/원자성/제약 검사가 있다.
후보1은 별도 파일 CheckpointComparisonExperiment의 테스트 전용 Job이며 공개 pointer를 바꾸지 않는다.
rankingE2e는 PG HTTP fixture·Outbox·실제 Kafka log/offset·실제 consumer·배치·공개 HTTP를 연결한다.
API check는 기존 outboxE2e와 새 rankingE2e를 모두 포함한다.

## 상태 확인과 복구 경계

확인 순서는 실행 프로세스 종료 여부 → Job/Step metadata → manifest/고정 입력/checksum →
stage/완성 결과 → 해당 주 pointer다. 완료 데이터나 metadata를 일괄 삭제하지 않는다.

- 정상 FAILED: 원인 수정과 입력/코드/state 호환성을 확인한 뒤 같은 parameter로 재실행한다.
- metadata 최종 저장 실패: 실제 공개 TX는 완료됐을 수 있다. 공개본을 먼저 확인한다.
  Job이 UNKNOWN이면 Spring Batch가 재시작을 차단한다. DB Step은 마지막 저장 상태 STARTED로
  남을 수 있다. in-memory 상태와 영속 상태를 혼동하지 않는다.
- STARTED/UNKNOWN의 자동 FAILED 보정이나 시간 기반 재시작은 제공하지 않는다.
  운영자가 실제 실행 종료·checkpoint 유효성을 확인하고 복구를 결정해야 한다.
  공개 동작 자체는 반복 호출해도 결과/checksum을 검증하며 같은 결과에 수렴한다.
