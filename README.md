# bank-withdrawal

Take-home assessment: improve a bank account **withdrawal + event notification** flow while preserving the business
capability. Original (intentionally flawed) snippet: [`docs/legacy/OriginalSnippet.java`](docs/legacy/OriginalSnippet.java) ·
Assessment brief: [`docs/assessment/take-home-assessment-tech.pdf`](docs/assessment/take-home-assessment-tech.pdf)

## Stack

- Java 25 · Spring Boot 4.0.6 · Gradle 9.5.1 (wrapper)
- PostgreSQL 17 (alpine) — schema managed by **Liquibase**
- AWS **SNS** via AWS SDK v2 + **LocalStack** for local development
- Testcontainers (PostgreSQL + LocalStack) for integration tests
- Lombok · MapStruct · Bean Validation · Actuator

## Run

```shell
./gradlew clean build        # full build incl. tests (Docker must be running)
./gradlew bootRun            # spring-boot-docker-compose starts Postgres + LocalStack automatically
```

`compose.yaml` provisions `postgres:17-alpine` and LocalStack (SNS only); `localstack/init-sns.sh` creates the
`bank-withdrawal-events` topic on startup.

## Branching model

`feat-*` → `dev` → `rel-*` → `int-*` → `main`

| Branch      | Purpose                                                  |
|-------------|----------------------------------------------------------|
| `feat-jpa`  | Implementation variant using Spring Data **JPA**         |
| `feat-jdbc` | Implementation variant using Spring Data **JDBC**        |
| `dev`       | Integration branch                                       |
| `main`      | Persistence-neutral base skeleton                        |

## Library / compatibility notes

- **Spring Cloud AWS** (`io.awspring.cloud`) has no Spring Boot 4-compatible release (latest: 3.4.0, Boot 3.x) — the
  raw AWS SDK v2 `SnsClient` is wired manually in `SnsClientConfig`, pointed at LocalStack via `app.aws.*` properties.
- **springdoc-openapi** likewise has no Boot 4 release (latest: 2.8.6) — only `swagger-annotations-jakarta` is on the
  classpath so `@Operation`/`@Tag` compile; add the UI starter once a Boot 4-compatible version ships.
- The Spring Cloud BOM (`2025.1.1`) is imported and ready, though no Spring Cloud starter is currently used.
- **LocalStack** is pinned to `4.14` — the last free community line; the `2026.x` CalVer images exit at startup unless
  a `LOCALSTACK_AUTH_TOKEN` (paid license) is provided.

---

# Submission

## 1. Approach outline

> _TODO — outline the approach; the fundamental business capability (withdraw + notify) remains unchanged._

## 2. Implementation choices

> _TODO — elaborate on design/implementation decisions (transactionality, atomic balance update, event publishing
> reliability e.g. transactional outbox, error handling, observability, idempotency, money handling, …)._

## 3. Fixed code

> _TODO — see `src/main/java/com/example/bank/` (branches `feat-jpa` / `feat-jdbc`)._

## 4. Library usage notes

> _TODO — document any non-obvious library usage._
