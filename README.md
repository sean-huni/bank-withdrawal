# bank-withdrawal

Take-home assessment: improve a bank account **withdrawal + event notification** flow while preserving the business
capability. Original (intentionally flawed) snippet: [`docs/legacy/OriginalSnippet.java`](docs/legacy/OriginalSnippet.java) ·
Assessment brief: [`docs/assessment/take-home-assessment-tech.pdf`](docs/assessment/take-home-assessment-tech.pdf)

## Stack

- Java 25 · Spring Boot 4.0.6 · Gradle 9.5.1 (wrapper)
- PostgreSQL 18 (alpine) — schema managed by **Liquibase**
- AWS **SNS** via AWS SDK v2 + **LocalStack** for local development
- Testcontainers (PostgreSQL + LocalStack) + **Cucumber** BDD scenarios
- Lombok · MapStruct · Bean Validation · Actuator · Micrometer Tracing (OTel) · AspectJ · Caffeine cache

## Run

```shell
sdk env install              # one-time: installs the pinned JDK (and gradle) from .sdkmanrc
sdk env                      # activate them for this shell (or enable sdkman_auto_env)
./gradlew clean build        # full build incl. tests (Docker must be running)
./gradlew bootRun            # spring-boot-docker-compose starts Postgres + LocalStack automatically
```

`compose.yaml` provisions `postgres:18-alpine3.23` and LocalStack (SNS only); `localstack/init-sns.sh` creates the
`bank-withdrawal-events` topic on startup.

**Local overrides:** copy `.env.example` → `.env` (gitignored). The app auto-loads it via
`spring.config.import: optional:file:.env[.properties]` and docker compose reads the same file natively —
one override point for both. All yml values keep working defaults (`${VAR:default}` placeholders), so a clean
clone runs with no `.env` at all; precedence is yml default < `.env` < real environment variable.

## Branching model

`feat-*` → `dev` → `rel-*` → `int-*` → `main`

| Branch      | Purpose                                                  |
|-------------|----------------------------------------------------------|
| `feat-jpa`  | Implementation variant using Spring Data **JPA**         |
| `feat-jdbc` | Implementation variant using Spring Data **JDBC**        |
| `dev`       | Integration branch                                       |
| `main`      | Persistence-neutral base skeleton                        |

## Package layout — anti-corruption layering

Strict separation between the Controller, Service and Data layers; objects never leak across, MapStruct maps between
them:

| Package       | Role                                                                                                                                 |
|---------------|--------------------------------------------------------------------------------------------------------------------------------------|
| `api`         | REST controllers + `api/advice` centralized error handling (`@RestControllerAdvice` + `@ResponseStatus`)                             |
| `api/dto`     | Controller-boundary objects — immutable request/response records, wrapped in `ApiResponse<T>` (+ `ApiError` with code/violations)    |
| `domain`      | Pure business objects between the controller and data layers — **no** `jakarta.persistence.*` / `org.springframework.data.*` imports |
| `exception`   | Business exceptions — mapped to HTTP statuses exclusively by the advice                                                              |
| `jpa/model`   | Persistence models only (JPA entities extending `BaseEntity`) — the only package allowed persistence imports                         |
| `jpa/repo`    | Spring Data repositories — work with `jpa/model` objects                                                                             |
| `idempotency` | `@Idempotent` aspect: key reservation + cached-response replay in the business transaction                                           |
| `service`     | Transactional use-case orchestration — `domain` objects internally, returns ready-to-serve DTOs                                      |
| `mapper`      | MapStruct mappers (used by the service layer) — the only crossing points between entity, domain, dto and event objects               |
| `event`       | Domain events + publisher abstraction (SNS adapter behind a port, after-commit listener)                                             |
| `config`      | Bean wiring (`SnsClient`, auditing, `TransactionTemplate`, `ObservedAspect`, properties)                                             |

Flow: **Repository (`jpa/model`) → Service (`model` ↔ `domain` ↔ `dto` via MapStruct) → Controller (`dto`, lean pass-through)**

## REST API

| Operation | Method + Path | Success | Notable errors |
|-----------|---------------|---------|----------------|
| Withdraw (debit) | `POST /api/v1/accounts/{accountId}/withdrawals` | `201` | `422` insufficient funds · `404` · `400` validation / missing key · `409` idempotency conflict |
| Deposit (credit) | `POST /api/v1/accounts/{accountId}/deposits` | `201` | `404` · `400` · `409` |
| Statement | `GET /api/v1/accounts/{accountId}/transactions` | `200` (paged) | `404` |
| Single transaction | `GET /api/v1/accounts/{accountId}/transactions/{transactionId}` | `200` | `404` |

```shell
curl -X POST localhost:8080/api/v1/accounts/<uuid>/withdrawals \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" \
  -d '{"amount": 250.00}'
```

### Design rationale (REST best practices)

1. **Resources are nouns; verbs live in the HTTP method.** The original `POST /bank/withdraw` is an RPC verb.
   `withdrawals`/`deposits` are command sub-resources of an account: they carry distinct validation and side
   effects, and both append entries to one `transactions` ledger that backs querying and audit.
2. **The created ledger entry is the resource** — `201 Created` with the complete representation in the
   body, replacing the original's `200 "Withdrawal successful"` string that lied to clients, caches and
   proxies alike. No `Location` header: the response already contains everything (id, balance, timestamps),
   so a canonical-URI pointer would only invite a redundant follow-up read.
3. **The account id belongs in the path, not the body** — the URI identifies the resource acted upon;
   the body carries only the command payload.
4. **First-class API versioning** (Spring Framework 7): the path is `/api/{api-version}/…`;
   `spring.mvc.api-version.use.path-segment: 1` tells Spring which segment carries the version and
   `spring.mvc.api-version.supported: ["1"]` declares the supported set — entirely in yaml, no version
   attributes and no hardcoded `v1` prefix in code. Unversioned mappings match any supported version;
   unsupported ones are rejected with `400 UNSUPPORTED_API_VERSION`. Evolving to v2 = add `"2"` to the yaml
   list + a `version = "2"` handler only where behavior diverges. (`default-version` is deliberately absent:
   the path resolver never yields "no version", so a default cannot apply to path-segment versioning.)
5. **`Idempotency-Key` header on every POST** (Stripe/PayPal-style): network retries must not double-debit.
   Replay returns the original representation; same key with a different body is a `409`.
6. **Customer vocabulary on the wire, accounting vocabulary inside** — endpoints say withdraw/deposit, the
   ledger stores `DEBIT`/`CREDIT`; the double-entry language never leaks.
7. **Errors are structured and machine-readable** — stable `error.code`, human message, per-field violations,
   and a `traceId` correlating the response with server-side traces/logs.

## Observability — OpenTelemetry + Grafana LGTM

`compose.yaml` includes `grafana/otel-lgtm` (all-in-one: Grafana + embedded OTLP collector + Prometheus/Mimir,
Tempo, Loki). The app exports **metrics, traces and logs** via OTLP — but only under the **dev profile**:

```shell
SPRING_PROFILES_ACTIVE=dev ./gradlew bootRun   # Grafana at http://localhost:3000 (admin/admin)
```

- `application-dev.yml` carries all Grafana/Prometheus-facing config (OTLP endpoints on :4318, 100% trace
  sampling, 10s metric step); the default profile disables OTLP export so tests/CI never dial a collector.
- Logs flow through the `OpenTelemetryAppender` (`logback-spring.xml`), installed by
  `OpenTelemetryAppenderInitializer`.
- Every response envelope carries the current `traceId` — paste it into Grafana/Tempo to see the request's
  spans (including the `@Observed` service timings).

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
