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
4. **First-class API versioning** (Spring Framework 7): the path is `/api/{api-version}/…`, configured in
   `ApiVersioningConfig` — `usePathSegment(1, path → path startsWith "/api/")` plus the supported set. Java
   config rather than `spring.mvc.api-version.*` properties for one reason: the path predicate (which keeps
   framework endpoints like springdoc's `/v3/api-docs` out of version resolution) is only expressible
   programmatically. No version attributes on mappings, no hardcoded `v1` prefix; unsupported versions are
   rejected with `400 UNSUPPORTED_API_VERSION`. Evolving to v2 = add `"2"` to the supported set + a
   `version = "2"` handler only where behavior diverges. (`default-version` is deliberately absent: the path
   resolver never yields "no version", so a default cannot apply to path-segment versioning.)
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
- **springdoc-openapi 3.x is the Boot 4 line** — `springdoc-openapi-starter-webmvc-ui:3.0.3` serves the UI at
  `/swagger-ui/index.html` and the spec at `/v3/api-docs` (the deprecated 1.x `springdoc-openapi-ui` must never be
  used). Two integration gotchas, both hit and fixed here: an explicit `swagger-annotations-jakarta` pin alongside
  the starter causes `NoSuchMethodError: Schema.$dynamicRef()` during spec generation (let the starter own the
  swagger stack), and path-segment API versioning must be scoped to `/api/**` via the programmatic path predicate
  or springdoc's own `/v3/api-docs` is rejected as "version 'api-docs'".
- The Spring Cloud BOM (`2025.1.1`) is imported and ready, though no Spring Cloud starter is currently used.
- **LocalStack** is pinned to `4.14` — the last free community line; the `2026.x` CalVer images exit at startup unless
  a `LOCALSTACK_AUTH_TOKEN` (paid license) is provided.

---

# Submission

## 1. Approach outline

The fundamental business capability is preserved exactly: **debit an account if funds are sufficient, record the
movement, and emit a withdrawal notification.** Everything around it is restructured.

The original snippet conflated four responsibilities inside one controller method — HTTP handling, persistence,
business invariants and infrastructure (SNS) — and contained these concrete defects:

| # | Defect in the original | Consequence | Fix |
|---|---|---|---|
| 1 | SNS publish is **unreachable** (every branch returns first) | The withdrawal event never fires | Domain event raised in the transaction, published `AFTER_COMMIT` |
| 2 | **Check-then-act** on the balance (read, then `balance - ?`) | Lost update under concurrency; parallel withdrawals overdraw | Atomic guarded `UPDATE … WHERE balance >= :amount` evaluated by the database |
| 3 | **No transaction boundary** | Balance update and event are a dual-write | `@Transactional` unit of work; event tied to commit |
| 4 | Controller does everything | Untestable, violates SRP | Layered controller → service → repository with anti-corruption mapping |
| 5 | `SnsClient` built in the constructor, hardcoded region/ARN | Not injectable, not configurable, not testable | Injected bean; all settings externalized with overridable defaults |
| 6 | Returns `String` (`"Withdrawal successful"`) | No HTTP semantics; lies to clients and proxies | `201 Created` + typed envelope; errors via `@RestControllerAdvice` |
| 7 | **No idempotency** | A network retry double-debits | Mandatory `Idempotency-Key` + transactional reservation/replay aspect |
| 8 | Manual JSON via `String.format` | Fragile, no escaping | Jackson serialization |
| 9 | Sequential `Long` account ids | Enumeration/information leak | UUID primary keys (`BaseEntity`) |
| 10 | No movement record | No audit trail | Immutable `transactions` ledger as the first-class resource |

The redesign models the **ledger entry as the resource**: withdrawals and deposits are command sub-resources that
append immutable `DEBIT`/`CREDIT` entries, which also back the statement and single-transaction queries (full REST
rationale in the section above). Correctness rests on three pillars: the **database** enforces the funds invariant
atomically, the **unique idempotency key** makes retries safe without application locks, and the **after-commit
listener** guarantees no event is ever emitted for a rolled-back withdrawal.

## 2. Implementation choices

- **Atomic balance mutation** — native `UPDATE … SET balance = balance - :amount WHERE id = :id AND balance >=
  :amount RETURNING balance`: funds check, debit and new balance in **one statement**. No lost updates, no lock
  waits, no re-read; `0` rows ⇒ insufficient funds (account existence is checked only on the failure path to
  sharpen 404 vs 422). `@Version` optimistic locking remains as belt-and-braces for entity-mediated writes.
- **Idempotency shares the business transaction** — the `@Idempotent` aspect (at `HIGHEST_PRECEDENCE`, opening the
  transaction via `TransactionTemplate` so the inner `@Transactional` joins it) flushes a unique key reservation,
  runs the business operation, and caches the response in the same physical transaction. Failure rolls everything
  back and frees the key; the duplicate-key exception is caught *outside* the rolled-back transaction and served
  from a fresh read. Same key + same fingerprint ⇒ replay of the original response; different body ⇒ `409`. The
  unique constraint — not an application lock — serializes racing retries.
- **Event publishing** — `@TransactionalEventListener(AFTER_COMMIT)` bridges the domain event to an SNS adapter
  behind a port. Publish failures are logged, never propagated (the withdrawal has committed; the client must not
  see it fail). This is at-most-once delivery; the production upgrade is a **transactional outbox** (same-transaction
  event row + relay/CDC) for at-least-once — documented trade-off, deliberately not built here.
- **Money handling** — `BigDecimal` end-to-end, `NUMERIC(19,4)` columns, `@Digits(15,4)` validation; comparisons via
  `compareTo` (scale-insensitive). Currency stored per account (single-currency operations assumed in scope).
- **Validation** — declarative only: `@Valid` at the controller, class-level `@Validated` + parameter constraints at
  the service (defense-in-depth); zero manual checks. Business invariants that live in database state (existence,
  funds) are exceptions mapped by the advice.
- **Error handling** — every failure path maps through one `@RestControllerAdvice` to a uniform envelope: stable
  machine-readable `error.code`, human message, field violations and `traceId`. Controllers contain no try/catch and
  no `ResponseEntity`; statuses are declared via `@ResponseStatus`.
- **Observability** — `@Observed` on every service operation (timer metrics + trace spans from one annotation),
  OTLP export of metrics/traces/logs to a Grafana LGTM stack under the dev profile, `traceId` in every response.
- **Read path** — immutable ledger entries are cached (Caffeine, `sync=true`): a write-once row can never go stale,
  so the cache is trivially correct. Statements are deliberately uncached (mutation-coupled, eviction complexity
  outweighs the win). Caching policy follows **mutability, not traffic**.
- **Layering** — strict dto / domain / persistence-model separation with MapStruct as the only crossing point. This
  paid off mechanically: swapping pessimistic locking for the guarded `RETURNING` update touched only `jpa/repo`,
  and the idempotency cache replays pure domain records.
- **Testing** — 14 Cucumber scenarios (positive + negative per flow) against **real** Postgres and LocalStack via
  Testcontainers: idempotent replay debits once, key reuse with a different body conflicts, two parallel withdrawals
  of 70 against a balance of 100 yield exactly one `201` and one `422`, cache hits proven via Caffeine statistics.

## 3. Fixed code

The fixed implementation is this repository (branch `feat-jpa`; `feat-jdbc` carries the Spring Data JDBC variant of
the persistence baseline). Entry points, in reading order:

1. [`AccountTransactionController`](src/main/java/com/example/bank/api/AccountTransactionController.java) — the lean HTTP boundary
2. [`AccountTransactionService`](src/main/java/com/example/bank/service/AccountTransactionService.java) — the business flow (compare directly with the original snippet)
3. [`AccountRepo`](src/main/java/com/example/bank/jpa/repo/AccountRepo.java) — the atomic guarded `RETURNING` debit
4. [`IdempotencyAspect`](src/main/java/com/example/bank/idempotency/IdempotencyAspect.java) — retry safety
5. [`WithdrawalEventListener`](src/main/java/com/example/bank/event/WithdrawalEventListener.java) / [`SnsWithdrawalEventPublisher`](src/main/java/com/example/bank/event/SnsWithdrawalEventPublisher.java) — the fixed notification path
6. [`GlobalExceptionHandler`](src/main/java/com/example/bank/api/advice/GlobalExceptionHandler.java) — centralized error semantics

## 4. Library usage notes

Non-obvious usage, including what required ground-truthing against Spring Boot 4.0.6 (full war stories with
symptoms and fixes: [`docs/lessons.md`](docs/lessons.md)):

- **Boot 4 module split** — Liquibase, AOP, tracing and OpenTelemetry support moved out of the classic
  starters/autoconfigure: `spring-boot-liquibase`, bare `aspectjweaver` (starter-aop is gone),
  `spring-boot-micrometer-tracing-opentelemetry` (the bare Micrometer bridge yields no `Tracer` bean),
  `spring-boot-starter-opentelemetry`. Several published Boot 4 demos use property names that do not exist in
  4.0.6 — the OTLP export properties are `management.*`, verified against the modules'
  `spring-configuration-metadata.json`.
- **Jackson 3** — Boot 4 ships `tools.jackson.*` packages (annotations remain `com.fasterxml.jackson.annotation`).
- **Spring Framework 7 API versioning** — the `{api-version}` path segment + yml-declared supported set; the
  `version` mapping attribute or `supported` list is mandatory (an empty supported set rejects every request), and
  `default-version` cannot apply to path-segment resolution.
- **MapStruct** — `componentModel = "spring"`; generated mappers are the only layer-crossing code, including the
  lazy-proxy-safe `account.id` read that avoids initializing the `@ManyToOne` reference.
- **`@Modifying(clearAutomatically)` interaction** — a clearing modifying query detaches every loaded entity in the
  transaction, including ones held by surrounding aspects (it silently dropped the idempotency record's `COMPLETED`
  state until re-merged). The final `RETURNING`-based design removes the clearing query entirely.
- **Spring Cloud AWS / springdoc** — no Boot 4-compatible releases at submission time; raw AWS SDK v2 (`SnsClient`
  bean) and `swagger-annotations-jakarta` respectively, with the springdoc starter to be added the moment a
  compatible version ships (`springdoc-openapi-ui` 1.x is deprecated and must not be used).
- **Testcontainers** — versions are no longer managed by the Boot 4 BOM (explicit `testcontainers-bom` import);
  LocalStack is pinned to the `4.x` community line (`2026.x` images require a paid license token) and reuses the
  same SNS init hook as docker compose.
