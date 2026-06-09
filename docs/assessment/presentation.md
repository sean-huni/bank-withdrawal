---
marp: true
title: Bank Withdrawal — Assessment Delivery
description: Concurrent-safe withdrawal service, layered architecture, observability and a React ATM client
paginate: true
---

# Bank Withdrawal Service
### Take-home Assessment — Delivery Walkthrough

A concurrency-safe withdrawal + deposit + PIN-auth service, rebuilt from a
defective snippet into a layered, observable, idempotent Spring Boot 4 application.

**Stack:** Java 25 · Spring Boot 4.0.6 · Spring Data JDBC · PostgreSQL 18 · Liquibase · Micrometer + OpenTelemetry · AWS SNS

**Plus a bonus React 19 ATM client** consuming the API.

---

## What the original snippet got wrong

The assessment started from a single controller method (`docs/legacy/OriginalSnippet.java`). It carried **10 defects**:

| # | Defect | Consequence |
|---|--------|-------------|
| 1 | SNS publish unreachable (every branch returned first) | Notifications never sent |
| 2 | Check-then-act on balance | Parallel withdrawals overdraw |
| 3 | No transaction boundary | Balance & event not atomic |
| 4 | Controller did everything | Untestable, SRP violation |
| 5 | `SnsClient` hardcoded in constructor | Not injectable / mockable |
| 6 | Returned `"Withdrawal successful"` string | No HTTP semantics |
| 7 | No idempotency | Network retries double-debit |
| 8 | Manual JSON via `String.format` | Fragile, injection-prone |
| 9 | Sequential `Long` ids | Enumeration leak |
| 10 | No movement record | No audit trail |

The delivery is a direct, defensible answer to each.

---

# 1. Architectural Design

---

## Layered, anti-corruption architecture

```
HTTP ─▶ Controller (api)         dto only — validate, delegate, wrap envelope
          │
          ▼
        Service (service)        @Transactional, business rules, MapStruct mapping
          │                      domain ↔ dto ↔ model
          ▼
        Repository (jdbc/repo)   Spring Data JDBC, atomic guarded SQL
          │
          ▼
        PostgreSQL               the invariant lives here
```

Three packages keep the layers from corrupting each other:

- **`dto`** — immutable `record` request/response payloads. The only thing controllers expose.
- **`domain`** — pure business objects. *No* `jakarta.persistence.*` / `org.springframework.data.*` imports.
- **`jdbc/model`** — persistence entities (`BaseEntity`: UUID PK + audit + `@Version`). The only place persistence imports live.

> Swapping the persistence strategy (we ship both a **JPA** and a **JDBC** branch) touches only `model` + `repo`. Nothing above the service signature changes.

---

## The three pillars of correctness

The whole design rests on pushing correctness to where it can't be raced:

**1. The database enforces the funds invariant — atomically**

```sql
UPDATE accounts
   SET balance = balance - :amount, version = version + 1, updated_at = now()
 WHERE id = :accountId AND balance >= :amount
RETURNING balance;
```
No read-then-write. `0` rows ⇒ business failure. `RETURNING` collapses the re-read into one roundtrip. Concurrent withdrawals **cannot** overdraw.

**2. A UNIQUE idempotency key makes retries safe**

SHA-256 fingerprint of (operation + accountId + amount), reserved in the *same transaction* as the debit. A duplicate key replays the cached response instead of debiting twice.

**3. After-commit event publishing**

`@TransactionalEventListener(AFTER_COMMIT)` → SNS. A rolled-back withdrawal *never* emits an event. (Transactional outbox is the documented upgrade for guaranteed delivery.)

---

## REST surface

All responses wrapped in a consistent `ApiResponse<T>` envelope (`success`, `data`, `error`, `timestamp`, `traceId`). Path-segment versioning (`/api/v1/...`), declared once.

| Method | Path | Returns | Key errors |
|--------|------|---------|-----------|
| `POST` | `/accounts/{id}/withdrawals` | 201 Transaction | 409 idempotency, 422 funds |
| `POST` | `/accounts/{id}/deposits` | 201 Transaction | 400, 404, 409 |
| `GET`  | `/accounts/{id}/transactions` | 200 Paged | 400 bad sort |
| `GET`  | `/accounts/{id}/transactions/{txId}` | 200 Transaction | 404 |
| `GET`  | `/cards/{cardNumber}` | 200 Greeting | 400, 404 |
| `POST` | `/cards/{cardNumber}/pin` | 200 Account | 401 PIN invalid |

`/cards/{n}` returns a **greeting only** — balance and accountId are revealed **only after a correct PIN**.

---

# 2. Crucial Decisions

*Short & precise — the "why" behind the non-obvious choices.*

---

## Crucial decisions (1/2)

- **Guarded atomic `UPDATE ... WHERE balance >= :amount RETURNING`** over `SELECT FOR UPDATE` — the DB evaluates the invariant; no lock waits, no lost updates.
- **Idempotency reservation shares the business transaction** — caching the response *outside* it leaves a "mutated but no response recorded" window. A UNIQUE constraint (not an app lock) serializes racing retries.
- **Spring Data JDBC over JPA** — no lazy-loading surprises, no dirty-checking, explicit SQL. (A JPA branch exists to prove the layering holds.)
- **BCrypt PIN verification** (`passwordEncoder.matches`) — never equality, never logged, never a metric label.
- **Dual-layer validation** — `@Valid` at the controller boundary **and** `@Validated` + param constraints on the service. The service defends its own contract for non-HTTP callers (jobs, listeners, tests).

---

## Crucial decisions (2/2)

- **Centralized `@RestControllerAdvice`** maps *every* exception to a status + machine-readable code. The three validation exception types (`MethodArgumentNotValid`, `ConstraintViolation`, `HandlerMethodValidation`) all normalize to a 400 with the same violation shape — none leak as a 500.
- **`ErrorCode` enum = single source of truth** binding wire code ↔ i18n message key. Violations expose `{field, code, message, rejectedValue}` (control-chars scrubbed).
- **`@Observed` for metrics** — one annotation yields both a Micrometer timer *and* a trace span. No hand-rolled stopwatch aspects.
- **Liquibase for migrations** (not Flyway); **PostgreSQL everywhere** including tests via Testcontainers — strict dev/prod parity.
- **UUID PKs, client-assigned** via `BeforeConvertCallback` — no sequential-id enumeration leak.

---

# 3. Other sub-topics

---

## Validation & error handling

- **Three validation layers, one response shape.** Body (`@Valid`), service contract (`@Validated`), and controller params (sort-property whitelist via a custom `@AllowedSortProperties` constraint) all converge on a 400 with field-level violations.
- **Sort whitelisting** stops an unknown sort property from blowing up at query time (a 500) — and stops injection probes reaching the SQL renderer.
- **Portable exceptions only** — catch `OptimisticLockingFailureException` (→409), never the ORM subclass.
- **Non-leaking catch-all** — unexpected errors return a generic 500 body; details stay in logs.

## Internationalization

- Message bundles: `messages.properties` (English) + `messages_sn.properties` (**Shona**).
- `Accept-Language` → `LocaleContextHolder`; unsupported locales fall back to English.
- **Logs stay English** (operator-facing); only payload messages are localized.

---

## Observability — done means dashboards

- **`@Observed`** on every service method → `card_pin_verify_*`, `account_withdraw_*`, etc., plus auto `http_server_requests_*` (RED metrics).
- **Histogram buckets enabled** where percentiles matter — Micrometer exports only `+Inf` by default, so p95 needs explicit buckets.
- **`grafana/otel-lgtm`** container in `compose.yaml` — Grafana (3000), Prometheus (9090), OTLP (4317/4318). Exports are **dev-profile opt-in** (clean clone needs no collector).
- **Failed-PIN signal** is literally `card_pin_verify_..._count{error="PinInvalidException"}` — the success-vs-failure split rides the `error` tag.
- Every response carries a `traceId` → one click to the trace in Tempo.

## Testing

- **Cucumber + real HTTP** (`RestTestClient`, `RANDOM_PORT`) — exercises Tomcat, not MockMvc.
- **Testcontainers** for Postgres (`@ServiceConnection`) + LocalStack (SNS).
- Positive **and** negative per flow, including a **parallel-race** scenario (two withdrawals vs one balance → one 201, one 422) and **byte-verbatim idempotency replays**.

---

## 12-Factor alignment

- **Config from the environment** — `${VAR:default}` placeholders, auto-imported `.env` (gitignored; `.env.example` committed). Precedence: yml < `.env` < real env.
- **Backing services as attached resources** — Postgres, SNS, OTLP collector all swappable by config.
- **Dev/prod parity** — same Postgres 18 + LocalStack in tests and compose.
- **Stateless processes** — the only cache (Caffeine) holds write-once immutable ledger reads; horizontally scalable behind the guarded UPDATE + unique keys.
- **Logs as event streams** — stdout / OTLP, never file management.

---

# 4. Bonus — React ATM client

*Not required by the assessment, but the API deserved a face.*

---

## `fe-bank-withdrawal` — a real ATM experience

**Stack:** React **19** · Vite 8 · TypeScript 6 · Tailwind 4 · TanStack Query · Zustand · React Hook Form + Zod · Vitest + Playwright.

**The flow:** `Card → PIN → Menu → Balance / Withdraw / Deposit / Statement → Receipt`

- **Welcome** — card entry with **Luhn validation**; auto-inserts the card the instant 16 valid digits are entered (no Enter). Saved cards appear as tap-to-use tiles (Zustand `persist`).
- **PIN** — 4-digit entry that **auto-authenticates on the 4th digit**:

```ts
useEffect(() => { if (pin.length === 4) void authenticate(pin) }, [pin])
```

- Balance is fetched (and revealed) **only after** the PIN verifies — mirroring the backend's contract.
- **Idempotency-Key** generated per withdraw/deposit and reused on retry.
- **i18n EN/SN** toggle, persisted, sent as `Accept-Language`.

---

## The frontend is observable too

- **OpenTelemetry web SDK** → OTLP/HTTP to the same LGTM collector (`/v1/traces`, `/v1/metrics`).
- Custom counters: **`atm_pin_verify_total{result}`**, `atm_withdrawal_total{result}`, `atm_card_lookup_total{result}`, `atm_txn_duration` histogram.
- **Web Vitals** (CLS, INP, LCP, TTFB, FCP) exported as a histogram.
- The raw PIN is **never** a metric label — same privacy discipline as the backend.
- Ships a pre-built Grafana dashboard JSON; **12-factor** config through a single `config/env.ts` read-point (`VITE_*`).

> Front and back share one observability stack, one trace context, one privacy posture.

---

# 5. Further improvements

---

## Passwordless auth with WebAuthn / Passkeys

The current PIN flow is deliberately simple. The natural next step is **passwordless authentication**, and the chosen stack makes adoption low-friction.

**Backend — Spring Security is a near drop-in:**

- Spring Security 6 ships **first-class WebAuthn / Passkey support** (`webAuthn()` DSL) — registration & assertion ceremonies, credential storage, challenge management mostly handled.
- Our layering helps: auth becomes a **filter-chain concern at the boundary**, leaving the service/repo layers untouched. The card→PIN endpoints stay as a fallback.
- A `cards` table already exists — adding a `webauthn_credentials` table (one Liquibase **XML** changeset) is the only schema change.
- The `@RestControllerAdvice` already normalizes 401s, so auth failures keep the existing envelope shape.

**Frontend — the React stack is ready:**

- The browser **WebAuthn API** (`navigator.credentials.create/get`) needs no new dependency; TanStack Query mutations wrap the two ceremonies exactly like `useVerifyPin` today.
- Passkeys replace the 4-digit keypad with a biometric prompt — **better UX *and* stronger security** (phishing-resistant, no shared secret on the wire).
- New counter `atm_passkey_auth_total{result}` slots straight into the existing telemetry.

---

## Other improvements on the roadmap

- **Transactional outbox** — upgrade after-commit SNS publishing from at-most-once to guaranteed delivery.
- **Spring Security authorization** — method-level security so an account holder can only touch their own account (currently open by assessment scope).
- **Native Shona review** — `messages_sn.properties` is machine-drafted, pending a native speaker.
- **Rate limiting** on PIN/passkey attempts — lockout after N failures, surfaced as a metric + alert.
- **Convert legacy Liquibase changesets to XML** — new changesets are already XML (declarative + XSD validation); the original `001–005` SQL set is the only exception, deferred to avoid checksum breakage on live dev DBs.

---

# Summary

| Concern | How it's answered |
|---------|-------------------|
| **Correctness** | Atomic guarded UPDATE — concurrency-safe by construction |
| **Safety** | Idempotency key in-transaction; after-commit events |
| **Security** | BCrypt PINs, balance gated, UUID ids, no leaks |
| **Maintainability** | Anti-corruption layering; JPA & JDBC branches prove it |
| **Operability** | `@Observed` RED metrics + traces → Grafana LGTM |
| **Correctness, proven** | Cucumber real-HTTP + Testcontainers, race & replay tests |
| **Experience** | React 19 ATM, auto-submit PIN, shared observability |
| **Future** | Passwordless via Spring Security WebAuthn / Passkeys |

### Thank you — questions welcome.
