# Lessons learned

Collected while building this project on Spring Boot 4 / Java 25 (2026-06). Each entry: what bit us, why, the fix.

## Spring Boot 4 / Spring Framework 7 migration

1. **Liquibase is silently inert without its module.** Boot 4 split third-party auto-configurations out of
   `spring-boot-autoconfigure`; `org.liquibase:liquibase-core` alone no longer activates anything. Symptom:
   tests fail with `Schema validation: missing table [accounts]` and **zero** Liquibase log lines. Fix: add
   `org.springframework.boot:spring-boot-liquibase`.
2. **Jackson 3 package move.** Boot 4 ships Jackson 3: `com.fasterxml.jackson.databind.*` →
   `tools.jackson.databind.*` (exceptions are now unchecked). Annotations (`@JsonInclude`, …) stay at
   `com.fasterxml.jackson.annotation`. `JsonNode.asText()` is deprecated → `asString()`.
3. **Test auto-configuration relocations.** `@AutoConfigureMockMvc` moved from
   `org.springframework.boot.test.autoconfigure.web.servlet` to
   `org.springframework.boot.webmvc.test.autoconfigure`. The webmvc test starter is
   `spring-boot-starter-webmvc-test` (not `spring-boot-starter-test`).
4. **Testcontainers versions are no longer BOM-managed.** Boot 4's BOM dropped `org.testcontainers`
   management — import `org.testcontainers:testcontainers-bom` explicitly or every artifact resolves to `:`.
5. **`HttpStatus.UNPROCESSABLE_ENTITY` is deprecated** (RFC 9110 rename) → `UNPROCESSABLE_CONTENT`. Same 422
   on the wire.
6. **Spring Cloud AWS and springdoc lag major Boot releases.** Neither had a Boot 4-compatible release at
   build time — SNS is wired with the raw AWS SDK v2 (`SnsClient` bean + endpoint override), and only
   `swagger-annotations-jakarta` is on the classpath (no UI).
7. **Test-AOT processing can fail the whole build.** The GraalVM native plugin hooks `processTestAot` into
   `build`; `@ServiceConnection` fails AOT when no matching `ConnectionDetails` factory is on the classpath.
   Dropped the plugin (irrelevant here) and kept a JDBC starter in the base.
8. **`spring-boot-starter-aop` is gone in Boot 4** (last release 3.5.x). `spring-aop` ships with core —
   only `org.aspectj:aspectjweaver` needs adding for `@Aspect` support.
9. **Tracing needs the Boot 4 module, not the bare bridge.** `io.micrometer:micrometer-tracing-bridge-otel`
   alone provides no auto-configured `Tracer` bean in Boot 4 — use
   `org.springframework.boot:spring-boot-micrometer-tracing-opentelemetry`.

## Infrastructure

10. **Boot 4 OTel export properties are `management.*`, not `spring.*`.** Several Boot 4 demos use
    `spring.otlp.metrics.export.url` / `spring.opentelemetry.tracing.export.otlp.endpoint` — those keys do
    not exist in 4.0.6 (verified against the modules' `spring-configuration-metadata.json`). Canonical:
    `management.otlp.metrics.export.url`, `management.opentelemetry.tracing.export.otlp.endpoint`,
    `management.opentelemetry.logging.export.otlp.endpoint`, with enable flags
    `management.{otlp.metrics,tracing,logging}.export[.otlp].enabled`. Also: `spring-boot-starter-opentelemetry`
    exists and brings SDK + exporters + the `OpenTelemetry` bean; the logback appender
    (`io.opentelemetry.instrumentation`) is BOM-unmanaged and needs an explicit version. When property names
    are in doubt, decompile/extract the metadata json from the jar — blogs and demos go stale fast.
11. **LocalStack licensing changed.** The `2026.x` CalVer images exit at startup (code 55) without a paid
    `LOCALSTACK_AUTH_TOKEN`; `localstack/localstack:4.x` is the last free community line.
12. **LocalStack ready-hooks must be executable.** A `ready.d` script mounted without `+x` is silently
    skipped — the SNS topic never existed and every publish failed with the publisher's catch-all. The mode
    bit is tracked by git; the same script is reused by Testcontainers via `withCopyFileToContainer`.
13. **Postgres `CHAR(3)` fails Hibernate `ddl-auto: validate`.** Postgres reports `bpchar`, Hibernate expects
    `varchar` for a `String` column → use `VARCHAR(3)` in the migration.

## Design

14. **`@Modifying(clearAutomatically = true)` detaches entities held by outer advice.** The idempotency
    aspect's managed `IdempotencyRecordEntity` was silently detached when the business op ran the guarded
    UPDATE — its `COMPLETED` mutation was never flushed and every replay returned 409 "in progress". Fix:
    explicitly `save(record)` (merge) after the business op. Rule of thumb: after a clearing modifying query,
    assume every previously-loaded entity in the transaction is detached — including ones owned by aspects.
15. **BigDecimal scale changes fingerprints.** `200.0` and `200.00` serialize differently, so an
    idempotency fingerprint treats them as different requests (strict-but-correct). Tests must replay the
    original body verbatim, not a value re-read from a response (JSON float round-trips drop trailing zeros).
16. **Publish events after commit, never inline.** The original snippet's SNS publish was a dual-write (and
    unreachable). `@TransactionalEventListener(AFTER_COMMIT)` removes the publish-on-rollback failure mode;
    a transactional outbox is the production upgrade when delivery must be guaranteed (at-least-once).
17. **Idempotency must share the business transaction.** Caching the response outside the mutation's
    transaction creates the "debited but no response recorded" window — a replay then double-charges. The
    aspect opens the transaction itself (`TransactionTemplate`, `@Order(HIGHEST_PRECEDENCE)` so it wraps the
    `@Transactional` advisor) and lets the unique key constraint — not an application lock — serialize racing
    retries. The duplicate-key exception must be caught *outside* the rolled-back transaction.
18. **Guarded UPDATE beats read-then-write.** `UPDATE … SET balance = balance - :amount WHERE balance >=
    :amount` makes the funds check atomic in the database — no lost updates, no lock waits, 0 rows ⇒ 422.
    `SELECT … FOR UPDATE` is the alternative when more invariants must hold inside the transaction.
19. **Timing AOP: use Micrometer, don't hand-roll.** `@Observed` + `ObservedAspect` is AOP under the hood and
    yields timer metrics *and* trace spans from one annotation; a custom stopwatch aspect would duplicate
    half of it.
20. **Layer separation pays off mechanically.** Keeping `domain` free of persistence imports meant the
    idempotency aspect could cache/replay pure domain records, and swapping the locking strategy touched only
    `jpa.repo` — nothing above the service signature changed.
21. **`PagedModel` for paged responses.** Serializing `PageImpl` directly is unstable API; Spring Data's
    `PagedModel` wrapper is the supported wire format.
22. **`UPDATE … RETURNING` collapses three roundtrips into one.** JPQL `@Modifying` updates can't return the
    new value, forcing pre-checks and re-reads (`existsById` → UPDATE → `findById`). A native Postgres
    `RETURNING` query does the guarded mutation and hands back the new balance in one statement; the entity
    reference for related inserts comes from `getReferenceById` (lazy proxy, no SELECT — `getId()` doesn't
    initialize it), and the existence check moves to the failure path. This also removed
    `clearAutomatically`, the root cause of lesson 14.
23. **Spring Framework 7 ships first-class API versioning.** `@RequestMapping(value = "/api/{version}/…",
    version = "1")` + `spring.mvc.apiversion.use.path-segment: <index>` replaces hardcoded `/v1` prefixes.
    The semantic parser tolerates the `v` prefix (URL `v1` matches `version = "1"`); unsupported versions
    raise `InvalidApiVersionException` — map it in the advice or it falls into the 500 catch-all. Path-segment
    versioning cannot be mixed with header/query strategies. The supported-version set comes from EITHER
    `@RequestMapping(version = …)` declarations (detected) OR `spring.mvc.api-version.supported` in yaml —
    declare neither and ALL requests fail as invalid (empty supported set). With the yaml list, unversioned
    mappings match any supported version. Two property gotchas (verified against the decompiled
    `WebMvcProperties$Apiversion`): the default key is `default-version`, not `default`; and a default is
    meaningless with path-segment versioning anyway — the path resolver never yields "no version" (it either
    finds the segment or the path doesn't match), so there is no gap for a default to fill.
24. **Cache only what cannot change.** `@Cacheable(sync = true)` on the by-id ledger read is trivially
    correct *because* the entry is write-once — no eviction logic, no staleness window; TTL/size bounds exist
    purely for memory. Statements were deliberately left uncached: correct eviction across page/sort key
    permutations costs more than the read saves. Caching policy follows mutability, not traffic.
