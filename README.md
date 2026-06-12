# bank-withdrawal

Take-home assessment: improve a bank account **withdrawal + event notification** flow while preserving the business
capability. Original (intentionally flawed) snippet: [`docs/legacy/OriginalSnippet.java`](docs/legacy/OriginalSnippet.java) ·
Assessment brief: [`docs/assessment/take-home-assessment-tech.pdf`](docs/assessment/take-home-assessment-tech.pdf)

**Contents:** [Quick start](#quick-start--clone--launch-backend--atm-frontend) ·
[Run & configure](#run) · [Architecture](#architectural-approaches) · [REST API](#rest-api) ·
[i18n](#internationalisation-i18n) · [Passkey ATM](#passkey-enabled-atm-spring-security-7-native-webauthn) ·
[OAuth 2.1 & security](#oauth-21--endpoint-security) · [Migrations](#database-migrations-liquibase) ·
[Observability](#observability--opentelemetry--grafana-lgtm) · [References](#references) ·
[Submission](#submission)

## Quick start — clone & launch (backend + ATM frontend)

Built for a copy-paste launch — no prior Java/Node setup needed. Install the three one-time
prerequisites, then run one block per terminal:

- **[Docker Desktop](https://www.docker.com/products/docker-desktop/)** — must be running (databases & dashboards live in containers)
- **[SDKMAN!](https://sdkman.io/install)** — installs the exact Java toolchain for the backend
- **[nvm](https://github.com/nvm-sh/nvm#installing-and-updating)** — installs the exact Node toolchain for the frontend

```shell
# Terminal 1 — backend API (also starts Postgres, LocalStack and Grafana via docker compose)
git clone git@github.com:sean-huni/bank-withdrawal.git && cd bank-withdrawal
sdk env install && sdk env       # one-time: pinned JDK + Gradle from .sdkmanrc
cp .env.example .env             # activates the dev profile (cheat-sheet banner + observability)
./gradlew bootRun
```

```shell
# Terminal 2 — ATM frontend (React)
git clone git@github.com:sean-huni/fe-bank-withdrawal.git && cd fe-bank-withdrawal
npm run setup                    # one-time: Node via nvm + dependencies + Playwright
nvm use && cp .env.example .env && npm run dev
```

When the backend console prints the **`DEV TEST DATA — Swagger UI cheat sheet`** banner,
everything below is live:

| What | Where | Sign in with |
|---|---|---|
| 🏧 ATM app (React SPA) | http://localhost:5173 | Card `4539 1488 0343 6467` (Alice) or `6011 0009 9013 9424` (Bob) · PIN `1234` |
| 📜 Swagger UI (REST API) | http://localhost:8080/swagger-ui.html | **Authorize** (client `swagger-ui`, PKCE public — leave `client_secret` EMPTY) → form login `operator` / `atm-demo` |
| 🧾 OpenAPI spec | http://localhost:8080/v3/api-docs | — |
| 📊 Grafana (dashboards + alerts) | http://localhost:3000 | `admin` / `admin` |
| 📈 Prometheus (raw metrics) | http://localhost:9090 | — |
| 🤖 OAuth2 token (scripts/m2m) | `POST http://localhost:8080/oauth2/token` | `curl -u atm-ops:atm-ops-secret -d 'grant_type=client_credentials&scope=atm.read atm.write' http://localhost:8080/oauth2/token` |

The dev banner re-prints all of the above on every start — plus the **live seeded account and
transaction ids** (regenerated per database, so the console is the always-true cheat sheet).
All credentials here are committed demo defaults for local development only; override any of
them via `.env` and the banner prints a redaction notice instead of the value.

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

`compose.yml` provisions `postgres:18-alpine3.23` and LocalStack (SNS only); `localstack/init-sns.sh` creates the
`bank-withdrawal-events` topic on startup.

### `.env` setup

Copy `.env.example` → `.env`, or start from this minimal skeleton:

```dotenv
# ENV files should not be added to git, this is just a skeleton to build upon
SPRING_PROFILES_ACTIVE=dev
```

`.env` is gitignored and must stay that way — it is the place for personal/secret values. A clean clone also
runs with no `.env` at all: every yml value keeps a working default (`${VAR:default}` placeholders). See
`.env.example` for all supported keys (`SERVER_PORT`, `AWS_*`, `OTLP_BASE_URL`, `POSTGRES_*`).

The same file is consumed three ways — one override point for everything:

1. **Spring** auto-loads it as a property source (`spring.config.import: optional:file:.env[.properties]`),
   feeding every `${VAR:default}` placeholder.
2. **Gradle** (`bootRun` / `bootTestRun`) exports it as real environment variables — required for
   `SPRING_PROFILES_ACTIVE`, whose relaxed mapping to `spring.profiles.active` only applies to genuine OS
   environment, not imported property files.
3. **docker compose** reads it natively (`POSTGRES_*`).

Precedence: yml default < `.env` < real environment variable (the shell always wins, e.g.
`SPRING_PROFILES_ACTIVE=default ./gradlew bootRun` overrides the file). With the `dev` profile active,
startup prints a **DEV TEST DATA** banner — the full local cheat sheet: Swagger/Grafana/Prometheus URLs,
seeded account/transaction ids, sortable fields, a fresh `Idempotency-Key`, the Swagger OAuth2 login,
an `atm-ops` token curl, and the ATM frontend/passkey crib notes. Credentials in the banner follow a
**redaction guard**: a secret prints only while it equals the committed demo default — any value
overridden via `.env`/environment is replaced by a redaction notice, because dev logs also ship to the
OTLP collector (Loki) and must never persist real secrets.

## Architectural approaches

### Gitflow branching model

`feat-*` → `dev` → `int-*` → `rel-*` → `main`

| Branch      | Purpose                                                                                  |
|-------------|------------------------------------------------------------------------------------------|
| `feat-jpa`  | Implementation variant using Spring Data **JPA** + programmatic API-versioning config    |
| `feat-jdbc` | Implementation variant using Spring Data **JDBC** + yml API-versioning config            |
| `dev`       | Integration trunk — feature branches merge here first; carries the JDBC implementation   |
| `int-*`     | Integration/staging verification (cut from `dev`)                                       |
| `rel-*`     | Release stabilization before production promotion                                       |
| `main`      | Production-ready history — only ever receives releases                                  |

Features are developed in isolation and flow toward `main` only through integration and release gates —
history stays linear per branch, every commit is thematic and `./gradlew clean build`-verified at its tip.

### 12-Factor App alignment

| Factor | How this project applies it |
|---|---|
| I. Codebase | One repo, many deploys; gitflow branches track the same codebase |
| II. Dependencies | Explicitly declared and isolated: Gradle wrapper, versions centralized in `gradle.properties`, toolchain pinned via `.sdkmanrc` (`sdk env install`) |
| III. Config | Strict env/code separation: `${VAR:default}` placeholders, `.env` auto-import, Spring profiles (`dev` for observability); nothing environment-specific is hardcoded |
| IV. Backing services | Postgres, SNS and the OTLP collector are attached resources, addressed by URL and swappable per environment (LocalStack/LGTM locally, real AWS/collector elsewhere) without code change |
| V. Build, release, run | Strictly separated: `./gradlew build` produces an immutable `bootJar`; config (profile/env) binds at run time, never baked in |
| VI. Processes | Stateless service — all state lives in Postgres; the Caffeine cache holds only immutable, write-once entries (a correctness-safe local optimization, not session state) |
| VII. Port binding | Self-contained embedded Tomcat exporting HTTP on `${SERVER_PORT:8080}` — no external server required |
| VIII. Concurrency | Scales horizontally: the DB-atomic guarded debit and unique idempotency-key reservation make N instances as safe as one (no application-level locks) |
| IX. Disposability | Fast startup, graceful shutdown; idempotent POSTs mean a killed-mid-request instance is safely retried against another |
| X. Dev/prod parity | Testcontainers and docker compose run the same Postgres 18/LocalStack engines used at runtime; tests hit real infrastructure, not in-memory stand-ins |
| XI. Logs | Treated as event streams: stdout locally, OTLP export to the collector (Loki via LGTM) under the dev profile — the app never manages log files |
| XII. Admin processes | Schema migrations are versioned Liquibase changesets executed by the app at startup against the bound database — no manual DDL |

## Package layout — anti-corruption layering

Strict separation between the Controller, Service and Data layers; objects never leak across, MapStruct maps between
them:

| Package       | Role                                                                                                                                 |
|---------------|--------------------------------------------------------------------------------------------------------------------------------------|
| `api`         | REST controllers + `api/advice` centralized error handling (`@RestControllerAdvice` + `@ResponseStatus`)                             |
| `api/dto`     | Controller-boundary objects — immutable request/response records, wrapped in `ApiResponse<T>` (+ `ApiError` with code/violations)    |
| `domain`      | Pure business objects between the controller and data layers — **no** `jakarta.persistence.*` / `org.springframework.data.*` imports |
| `exception`   | Business exceptions — mapped to HTTP statuses exclusively by the advice                                                              |
| `jdbc/model`  | Persistence models only (Data JDBC aggregates extending `BaseEntity`) — the only package allowed persistence imports                  |
| `jdbc/repo`   | Spring Data repositories — work with `jdbc/model` objects                                                                            |
| `idempotency` | `@Idempotent` aspect: key reservation + cached-response replay in the business transaction                                           |
| `service`     | Transactional use-case orchestration — `domain` objects internally, returns ready-to-serve DTOs                                      |
| `mapper`      | MapStruct mappers (used by the service layer) — the only crossing points between entity, domain, dto and event objects               |
| `event`       | Domain events + publisher abstraction (SNS adapter behind a port, after-commit listener)                                             |
| `config`      | Bean wiring (`SnsClient`, auditing, `TransactionTemplate`, `ObservedAspect`, properties)                                             |

Flow: **Repository (`jdbc/model`) → Service (`model` ↔ `domain` ↔ `dto` via MapStruct) → Controller (`dto`, lean pass-through)**

## REST API

| Operation | Method + Path | Success | Notable errors |
|-----------|---------------|---------|----------------|
| Withdraw (debit) | `POST /api/v1/accounts/{accountId}/withdrawals` | `201` | `422` insufficient funds · `404` · `400` validation / missing key · `409` idempotency conflict |
| Deposit (credit) | `POST /api/v1/accounts/{accountId}/deposits` | `201` | `404` · `400` · `409` |
| Statement | `GET /api/v1/accounts/{accountId}/transactions` | `200` (paged) | `404` |
| Single transaction | `GET /api/v1/accounts/{accountId}/transactions/{transactionId}` | `200` | `404` |
| Card greeting (no balance) | `GET /api/v1/cards/{cardNumber}` | `200` | `404` CARD_NOT_FOUND (unknown card) · `400` VALIDATION_FAILED (malformed, violation on cardNumber) |
| Verify PIN (returns balance) | `POST /api/v1/cards/{cardNumber}/pin` | `200` | `401` PIN_INVALID (wrong PIN) · `404` CARD_NOT_FOUND · `400` VALIDATION_FAILED (malformed pin/card) |
| ATM session bootstrap (card+PIN) | `POST /api/v1/atm/session` | `200` (session cookie) | `401` PIN_INVALID · `404` CARD_NOT_FOUND · `400` VALIDATION_FAILED — see **Passkey-enabled ATM** below |
| ATM session end (kiosk exit) | `POST /api/v1/atm/session/end` | `204` (idempotent) | — (always `204`, even with no session) |
| Session snapshot (whoami) | `GET /api/v1/atm/session` | `200` | `401` no authenticated session · `404` principal has no ATM account — hydrates the kiosk SPA after a passkey login |

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
4. **First-class API versioning** (Spring Framework 7): API versioning is configured purely in `application.yml`
   via `spring.mvc.api-version.*` (path-segment at index 1, supported set `["1"]`). Version validation only
   applies to requests matching versioned mappings, so springdoc and actuator need no exclusions (verified
   empirically; Boot 4.0.6 has no `ignored-paths` property). The sibling `feat-jpa` branch demonstrates the
   same Spring Framework 7 versioning configured programmatically (`ApiVersioningConfig` implementing
   `WebMvcConfigurer`) — together the branches show both setup styles. No version attributes on mappings, no
   hardcoded `v1` prefix; unsupported versions are rejected with `400 UNSUPPORTED_API_VERSION`. Evolving to v2 =
   add `"2"` to the supported set + a `version = "2"` handler only where behavior diverges. (`default-version`
   is deliberately absent: the path resolver never yields "no version", so a default cannot apply to
   path-segment versioning.)
5. **`Idempotency-Key` header on every POST** (Stripe/PayPal-style): network retries must not double-debit.
   Replay returns the original representation; same key with a different body is a `409`.
6. **Customer vocabulary on the wire, accounting vocabulary inside** — endpoints say withdraw/deposit, the
   ledger stores `DEBIT`/`CREDIT`; the double-entry language never leaks.
7. **Errors are structured and machine-readable** — stable `error.code`, human message, per-field violations,
   and a `traceId` correlating the response with server-side traces/logs.

## Internationalisation (i18n)

Error messages are resolved per request from `src/main/resources/i18n/messages*.properties`
via the `Accept-Language` header. Wire-level error codes (`ACCOUNT_NOT_FOUND`, …) are
locale-independent — clients branch on codes, humans read messages.

| Locale | Bundle | Notes |
|---|---|---|
| English | `messages.properties` | Base — also the fallback for unsupported locales |
| Shona (`sn`) | `messages_sn.properties` | `curl -H 'Accept-Language: sn' …` |

Validation messages come from the same catalog (constraint annotations carry braced keys,
e.g. `{error.amount.positive}`); each field violation also exposes its machine-readable
`code` (the bundle key) and the `rejectedValue`. A catalog completeness test fails the
build if any referenced key is missing from any bundle.

## Passkey-enabled ATM (Spring Security 7 native WebAuthn)

A creative twist on the assessment: a **passkey replaces card-number entry on return visits**.
The passkey is the customer's *identity* on the kiosk; the bank **PIN remains the transaction
authorization secret**. Signature verification is performed by **Spring Security's built-in
WebAuthn DSL** (`http.webAuthn(...)`, backed by webauthn4j) — real attestation/assertion
verification, not a hand-rolled verifier.

Session-based by design (HttpSession cookie, no bearer tokens in browser storage) — the correct
model for a **shared ATM kiosk**: identity lives in a server session the kiosk clears between
customers, never in `localStorage` on a public terminal.

### Flow

```
First visit (enrol):                         Return visit (skip the card):
  insert card + enter PIN                       tap passkey  (POST /webauthn/authenticate/options
   → POST /api/v1/atm/session                                 → POST /login/webauthn — username-less,
   → authenticated kiosk session                                discoverable credential)
   → "Enable passkey on this ATM"               → authenticated kiosk session
   → POST /webauthn/register/options            → enter PIN to AUTHORIZE the withdrawal
   → POST /webauthn/register  (attestation)        (PIN stays the transaction secret)
```

Card + PIN bootstrap is what makes `POST /webauthn/register/options` reachable: registration
requires an authenticated principal (the account UUID; the card number is never the principal,
never logged). The bootstrap also links that principal to a WebAuthn user entity whose
`displayName` is the masked card, so the authenticator shows a human-friendly label.

### Endpoints

| Step | Method + Path | Auth | Notes |
|---|---|---|---|
| ATM session bootstrap | `POST /api/v1/atm/session` | card + PIN | Body `{cardNumber, pin}` → `200` + session cookie; `{accountId, maskedCardNumber, passkeyEnrolled}`. `401` wrong PIN, `404` unknown card (existing wire shapes). CSRF-exempt (under `/api/**`), but **rotates the session id** on success (session-fixation defence, OWASP A07) and **primes the `XSRF-TOKEN` cookie** (via `CsrfCookieFilter`) so the next ceremony POST succeeds first try. |
| ATM session end (kiosk exit) | `POST /api/v1/atm/session/end` | — | Invalidates the HttpSession + clears the SecurityContext. **Idempotent: always `204`**, even with no session (no error leak). The kiosk calls this between customers. |
| Registration options | `POST /webauthn/register/options` | session required | Mints a creation challenge for the authenticated customer. Without a session the framework filter refuses with `400` (never issues a challenge — user-enumeration-safe). |
| Registration | `POST /webauthn/register` | session required | Verifies attestation, stores the credential. CSRF-protected (echo `XSRF-TOKEN` cookie as `X-XSRF-TOKEN`). |
| Authentication options | `POST /webauthn/authenticate/options` | public | Username-less discoverable-credential challenge for a returning customer. |
| Login | `POST /login/webauthn` | public | Verifies the assertion signature and establishes the session. |

### Configuration (`atm.passkey.*`, env-overridable)

| Property | Env var | Default | Meaning |
|---|---|---|---|
| `rp-id` | `PASSKEY_RP_ID` | `localhost` | Registrable domain credentials are scoped to (production: the bank's apex domain). |
| `rp-name` | `PASSKEY_RP_NAME` | `Bank Withdrawal ATM` | Human-readable relying-party name shown by the authenticator. |
| `origins` | `PASSKEY_ORIGINS` | `http://localhost:5173` | Exact browser origins allowed to run the ceremony. |

Validated at startup (`@Validated`, braced i18n keys) — a blank value fails fast. Precedence:
yml default < `.env` < real environment variable.

### Security & kiosk notes

- **HTTPS in production.** WebAuthn requires a [secure context]; browsers grant `localhost` the
  one non-HTTPS exception for local dev. A deployed ATM must serve every origin over HTTPS, and
  `rp-id` must be the production apex domain.
- **Registration is origin-bound.** A credential registered against one origin cannot be used
  from another — keep `origins` exact and minimal.
- **Persistence.** Credentials and user entities live in Postgres (`user_entities`,
  `user_credentials` — Liquibase changeset `006`) via Spring's JDBC WebAuthn repositories, so an
  enrolled passkey survives a restart.
- **CSRF.** The existing JSON API (`/api/**`) and the pre-authentication ceremony endpoints stay
  CSRF-exempt; the post-authentication registration ceremony is CSRF-protected with a readable
  (`httpOnly=false`) cookie token the SPA echoes back. A `CsrfCookieFilter` (the official Spring
  Security SPA pattern, `addFilterAfter(BasicAuthenticationFilter.class)`) primes the `XSRF-TOKEN`
  cookie on **every** response — including the CSRF-exempt bootstrap — so the SPA's first
  CSRF-protected ceremony POST succeeds on the first try (no fetch-then-403-then-retry dance).
- **Session fixation.** The bootstrap rotates the session id (`changeSessionId()`) on successful
  card+PIN, so a pre-authentication session cannot survive privilege elevation (OWASP A07 / ASVS
  V3.2.1). The kiosk-exit endpoint (`POST /api/v1/atm/session/end`) invalidates the session and
  clears the SecurityContext between customers; it is idempotent (always `204`).
- **Kiosk session timeout.** `server.servlet.session.timeout` defaults to **2 minutes**
  (`ATM_SESSION_TIMEOUT`, env-overridable) — a shared terminal drops an abandoned authenticated
  session fast. Precedence: yml default < `.env` < real environment variable.
- **`[!CONVENTION-OVERRIDE]`** `/api/**`, actuator and OpenAPI are `permitAll` to keep this
  assessment app's existing public API contract byte-identical (all 50 prior tests unchanged). A
  production bank would require authentication there — leaving them public would be
  [OWASP A01:2021 Broken Access Control] and is acceptable *only* because this is an assessment.

> **Testing limitation.** The full register/login ceremony (attestation + assertion with a real
> authenticator) is not e2e-testable on the JVM without a virtual authenticator (e.g. a headless
> Chrome WebAuthn virtual authenticator under Playwright). The Cucumber scenarios verify the
> *wiring* — endpoints exist, are protected correctly, and emit well-formed challenge JSON; the
> signature verification itself is the framework's (webauthn4j) and is covered by Spring
> Security's own test suite.

[secure context]: https://developer.mozilla.org/en-US/docs/Web/Security/Secure_Contexts
[OWASP A01:2021 Broken Access Control]: https://owasp.org/Top10/A01_2021-Broken_Access_Control/

## OAuth 2.1 & endpoint security

An embedded **Spring Authorization Server** (merged into Spring Security 7) runs in the same JVM.
Its `@Order(1)` filter chain covers `/oauth2/**`; the application/resource-server chain is `@Order(2)`.
The JWT decoder reads the in-process `JWKSource` bean directly — no HTTP self-call, no RANDOM_PORT issues.

### Registered clients

| Client | Type | Grant | Notes |
|---|---|---|---|
| `swagger-ui` | Public (no secret) | Authorization Code + PKCE | Redirect URI `http://localhost:8080/swagger-ui/oauth2-redirect.html`. OAuth 2.1 mandates PKCE for public clients; `requireProofKey=true` is enforced. Consent screen disabled. |
| `atm-ops` | Confidential | Client Credentials | Secret `atm-ops-secret` (BCrypt-encoded at registration). For m2m scripting and the Cucumber integration suite. |

### Scopes

| Scope | Meaning |
|---|---|
| `atm.read` | Read transactions and statements |
| `atm.write` | Create withdrawals and deposits |
| `atm.ops` | Operational access — all remaining actuator endpoints |

### Demo identities

All defaults follow the demo-PIN convention and are env-overridable:

| Identity | Default | Env var | Use |
|---|---|---|---|
| Operator username | `operator` | `ATM_OPERATOR_USERNAME` | Form-login user for the Swagger "Authorize" dialog |
| Operator password | `atm-demo` | `ATM_OPERATOR_PASSWORD` | Same |
| `atm-ops` client secret | `atm-ops-secret` | `ATM_OPS_CLIENT_SECRET` | Client-credentials grant |

These print in the dev banner only while they equal the committed defaults above; an overridden
value is redacted in the banner and masked in `SecurityProperties.toString()`, so a real credential
never reaches the console or the OTLP log stream (Loki).

### Using Swagger UI

1. Open `/swagger-ui.html` (redirects to `/swagger-ui/index.html`).
2. Click **Authorize** → select the scopes you want → click **Authorize**. The browser is redirected to
   `/oauth2/authorize` and then to the form-login page.
3. Sign in as `operator` / `atm-demo`.
4. **Try it out** on any operation. Swagger sends the bearer token automatically — PKCE is pre-wired via
   `springdoc.swagger-ui.oauth.use-pkce-with-authorization-code-grant: true`.

The dev banner prints the same recipe on every start:

```
Swagger login   : Authorize (client swagger-ui, PKCE public — leave client_secret EMPTY) -> form login operator / atm-demo
```

> **Common mixups** (both hit in real testing; full flow verified end-to-end with a scripted browser):
>
> - The form login authenticates the **operator user** (`operator` / `atm-demo`). The
>   `atm-ops` / `atm-ops-secret` pair is a *client-credentials client* for curl/m2m — on the login
>   form it always yields *Invalid credentials*. Two clients, two flows (see the table above).
> - Swagger's default `pageable` example sends `"sort": ["string"]`, which the sort whitelist rejects
>   with `400 VALIDATION_FAILED` — not an auth failure. Use e.g.
>   `{"page": 0, "size": 5, "sort": ["createdAt,desc"]}`.

### Token issuance (client-credentials)

```bash
curl -u atm-ops:atm-ops-secret \
  -d 'grant_type=client_credentials&scope=atm.read atm.write' \
  http://localhost:8080/oauth2/token
```

### Endpoint security matrix

| Surface | Access rule |
|---|---|
| `POST /api/*/atm/session`, `POST /api/*/atm/session/end` | Public — the login mechanism itself |
| `GET /api/*/cards/*`, `POST /api/*/cards/*/pin` | Public — card greeting and PIN verification |
| `/webauthn/authenticate/options`, `/login/webauthn` | Public — passkey login ceremony |
| `/login` | Public — form login |
| `/v3/api-docs/**`, `/swagger-ui/**`, `/swagger-ui.html` | Public — UI assets (individual try-it-out calls are individually secured) |
| `/actuator/health`, `/actuator/health/**`, `/actuator/info` | Public — liveness/readiness probes |
| `/actuator/**` (all other endpoints) | `SCOPE_atm.ops` required |
| Transaction write operations (`POST withdrawals`, `POST deposits`) | `SCOPE_atm.write` **or** session owner (`@accountAccess.isOwner`) |
| Transaction read operations (`GET transactions`) | `SCOPE_atm.read` **or** session owner |
| Everything else (WebAuthn registration, whoami) | Any authenticated principal (session cookie or JWT bearer) |

Dual-auth is transparent: a request authenticates either as a kiosk customer via the HttpSession
established by card+PIN or passkey login, or as an OAuth 2 caller via a JWT bearer token. The kiosk
SPA itself never uses bearer tokens — it is session-cookie only.

### Caveats

- **Per-start signing key.** The RSA JWK is generated in-memory on startup. A backend restart
  invalidates all previously issued tokens — in-flight Swagger sessions must re-Authorize after a
  restart.
- **Session cookie is `SameSite=Strict`** (`application.yml`). All legitimate flows (same-origin SPA
  proxy, WebAuthn ceremonies, the OAuth2 authorize/login redirects) are same-site; no functional
  impact.
- **The kiosk SPA never uses bearer tokens.** Session-cookie dual-auth is intentional for a shared
  ATM terminal — no access token ever sits in browser storage on a public device.

## Database migrations (Liquibase)

Schema is owned by Liquibase: an XML master (`db/changelog/db.changelog-master.xml`) including one
changeset file per logical step (`db/changelog/changes/NNN-*.xml`), applied at app startup. Typed
tags keep the DDL DB-portable; `<sql>` is used only for genuinely vendor-specific statements with no
typed tag (CHECK constraints, `INSERT … SELECT`).

### Changelog identity & re-run safety

**Every changeset carries a `<preConditions onFail="MARK_RAN">` schema-state guard** (e.g.
`<not><tableExists/></not>`, `<columnExists/>`, a `COUNT(*)` `<sqlCheck>`), so each changeset is a
no-op when its effect is already present and executes only when it is genuinely needed.

Why this matters: Liquibase identifies a changeset by the triple **(id, author, filename)**. When the
changelogs were converted from `.sql` to `.xml` the *filename* changed, so against any database that
had already applied the old `.sql` changelogs, the converted changesets counted as **new** and
re-executed their DDL — surfacing as `relation "accounts" already exists` on startup. Fresh
Testcontainers databases never exposed this because they ran the XML from empty.

We deliberately chose **state-guards over identity-pinning**: we did **not** add `logicalFilePath` to
re-pin the old identities, because the preconditions also make the schema converge from *any* reachable
historical state (fresh / fully-migrated / partially-migrated) to the same final schema, and keep every
changeset idempotent against manual DDL drift. The `004 → 005` chain is the subtle case: `004` adds an
interim `accounts.card_number` column that `005` migrates into the `cards` table and then drops, so
`004`'s guard runs it only on a genuinely fresh DB (`not cards AND not accounts.card_number`), while the
`005` chain still runs on a pre-`005` database to complete the normalisation. Verified across all three
states 2026-06-11.

## Observability — OpenTelemetry + Grafana LGTM

`compose.yml` includes `grafana/otel-lgtm` (all-in-one: Grafana + embedded OTLP collector + Prometheus/Mimir,
Tempo, Loki). The app exports **metrics, traces and logs** via OTLP — but only under the **dev profile**:

```shell
SPRING_PROFILES_ACTIVE=dev ./gradlew bootRun   # Grafana at http://localhost:3000 (admin/admin)
                                               # Prometheus UI at http://localhost:9090
```

Both UIs are also linked from the dev startup banner (override via `GRAFANA_URL` /
`PROMETHEUS_URL` in `.env`).

- `application-dev.yml` carries all Grafana/Prometheus-facing config (OTLP endpoints on :4318, 100% trace
  sampling, 10s metric step); the default profile disables OTLP export so tests/CI never dial a collector.
- Logs flow through the `OpenTelemetryAppender` (`logback-spring.xml`), installed by
  `OpenTelemetryAppenderInitializer`.
- Every response envelope carries the current `traceId` — paste it into Grafana/Tempo to see the request's
  spans (including the `@Observed` service timings).

### Dashboards & alert rules (what to look at)

Sign in at http://localhost:3000 as `admin` / `admin`:

| Where in Grafana | Asset | Shows |
|---|---|---|
| **Dashboards → `wat-alerts` folder** | `bank-cards-pin` | RED panels for the cards/PIN endpoints, *Auth failures (401/403) rate*, *Authentications by type* (client-credentials vs auth-code vs WebAuthn vs form login) |
| **Alerting → Alert rules** | *Bank — high PIN failure rate* · *Bank — auth failure spike (401/403)* | Fire on brute-force-shaped traffic |
| **Explore → Tempo** | trace lookup | Paste the `traceId` from any API error envelope to see that request's spans |
| **Explore → Loki** | log search | App logs, including the dev banner — the reason banner secrets are redaction-guarded |

The **ATM frontend ships its own dashboard** (sessions, withdrawals/deposits by result, Web Vitals
p95) with import steps in the
[fe-bank-withdrawal README](https://github.com/sean-huni/fe-bank-withdrawal#import-the-grafana-dashboard).

Two operational notes: `otel-lgtm` storage is **container-local** — recreating the compose stack
wipes dashboards, alert rules and metric history (re-provision via the Grafana MCP workflow below);
and dashboards/alerts are **managed through mcp-grafana tool calls**, never hand-edited JSON.

### mcp-grafana — Grafana via MCP (dashboards & alerts as tool calls)

`compose.yml` also runs **`grafana/mcp-grafana`** so AI tooling (Claude Code & co.) manages
dashboards and alert rules through tool calls instead of hand-edited JSON. It rides the same
compose lifecycle as the rest of the stack (started by `bootRun`'s docker-compose support).

**Wiring (verified 2026-06-11):**

| Piece | Value | Why |
| ----- | ----- | --- |
| Transport | `streamable-http`, container port 8000 → host **8001** | host 8000 clashes easily; the MCP client config points at 8001 |
| `GRAFANA_URL` | `http://grafana-lgtm:3000` — the **compose service name** | inside the container, `localhost:3000` is the MCP container itself → every tool call fails with `dial tcp [::1]:3000: connect: connection refused` |
| `GRAFANA_SERVICE_ACCOUNT_TOKEN` | `${GRAFANA_SA_TOKEN:-}` from `.env` (gitignored; see `.env.example`) | never committed — GitHub push protection blocks `glsa_…` tokens; empty default keeps a clean clone bootable |
| Client registration | `~/.claude.json` → `"grafana": { "type": "http", "url": "http://localhost:8001/mcp" }` | Claude Code talks to the container over HTTP MCP |

**Pain points & pitfalls (each one cost real debugging time):**

1. **`localhost` inside the container.** The single most common failure. If a tool call errors with
   `dial tcp [::1]:3000: connect: connection refused` while `curl http://localhost:3000/api/health`
   works from the host, the MCP process is resolving `localhost` to *itself*. `GRAFANA_URL` must use
   the compose service name (`http://grafana-lgtm:3000`) — or `host.docker.internal` for a
   non-compose Grafana.
2. **Stale MCP client sessions.** Claude Code pins a streamable-HTTP session at startup. When the
   container is recreated mid-session (every `bootRun` cycles the compose stack), tool calls can keep
   failing with errors from the *old* server's config even though `curl -X POST
   http://localhost:8001/mcp` (initialize) works fine. Fix: `/mcp` → reconnect (or restart the
   session). The server is almost never the problem at that point.
3. **Stray manually-started containers.** A leftover `docker run grafana/mcp-grafana` (no
   `GRAFANA_URL`, defaults to `localhost:3000`) is indistinguishable from the real one in error
   messages. `docker ps --format '{{.Names}} {{.Ports}}'` and `docker inspect <name> --format
   '{{json .Config.Env}}'` identify which container actually serves :8001.
4. **`uri="UNKNOWN"` for security-filter endpoints.** `http_server_requests_*` only gets a real
   `uri` tag for MVC-handled requests. Everything answered inside the Spring Security filter chain —
   401/403 rejections, **all `/oauth2/*` authorization-server endpoints**, `/login/webauthn` — is
   tagged `uri="UNKNOWN"`. Don't build panels on `uri="/oauth2/token"` (always empty); use the
   `spring_security_authentications_*` observation metrics, whose `authentication_request_type` /
   `error` labels distinguish client-credentials, auth-code, WebAuthn and form logins, including
   failures.
5. **`query_prometheus` needs `startTime` *and* `endTime`** (e.g. `"now-5m"` / `"now"`) — omitting
   `endTime` fails with a cryptic `parsing end time: syntax error`.
6. **Fresh-stack amnesia.** `otel-lgtm` storage is container-local: a recreate wipes metric history,
   and `rate()` needs ≥2 scrape points (10s step) — generate traffic and wait ~25s before declaring
   a panel query broken.
7. **No export, no data.** OTLP export only runs under the **dev profile**
   (`SPRING_PROFILES_ACTIVE=dev`, auto-loaded from `.env` by the `bootRun` env block). If Prometheus
   has zero `http_*`/`jvm_*` series, check the profile before blaming the stack.

**Usage / smoke test:**

```shell
# is the MCP server alive and pointed at the right Grafana?
curl -s -X POST http://localhost:8001/mcp \
  -H 'Content-Type: application/json' -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"probe","version":"0"}}}'
# → serverInfo "mcp-grafana". A follow-up tools/call (with the returned Mcp-Session-Id header)
#   to search_dashboards proves the Grafana connection end-to-end.
```

Current managed assets are listed in **Dashboards & alert rules** above.

## Library / compatibility notes

- **Spring Cloud AWS** (`io.awspring.cloud`) has no Spring Boot 4-compatible release (latest: 3.4.0, Boot 3.x) — the
  raw AWS SDK v2 `SnsClient` is wired manually in `SnsClientConfig`, pointed at LocalStack via `app.aws.*` properties.
- **springdoc-openapi 3.x is the Boot 4 line** — `springdoc-openapi-starter-webmvc-ui:3.0.3` serves the UI at
  `/swagger-ui/index.html` and the spec at `/v3/api-docs` (the deprecated 1.x `springdoc-openapi-ui` must never be
  used). Two integration gotchas, both hit and fixed here: an explicit `swagger-annotations-jakarta` pin alongside
  the starter causes `NoSuchMethodError: Schema.$dynamicRef()` during spec generation (let the starter own the
  swagger stack), and path-segment API versioning is scoped to versioned mappings only — springdoc's `/v3/api-docs`
  and actuator need no special exclusions when version validation only applies to requests matching versioned handlers
  (verified empirically on Boot 4.0.6).
- The Spring Cloud BOM (`2025.1.1`) is imported and ready, though no Spring Cloud starter is currently used.
- **LocalStack** is pinned to `4.14` — the last free community line; the `2026.x` CalVer images exit at startup unless
  a `LOCALSTACK_AUTH_TOKEN` (paid license) is provided.
- **Spring Data JDBC + UUIDs**: Data JDBC includes the id column in INSERTs, bypassing the DB-side `gen_random_uuid()` default — ids are assigned client-side by a `BeforeConvertCallback` (`JdbcAuditingConfig`).
- **Data JDBC `@Query`**: always native SQL; `UPDATE … RETURNING balance` runs as a row-returning statement on PostgreSQL, keeping the funds check atomic without JPA.
- **Optimistic locking**: Data JDBC throws the `org.springframework.dao.OptimisticLockingFailureException` root type (spring-orm's JPA subclass is not on the classpath) — the advice handles exactly that type.

## References

Standards and sources this project deliberately follows (cited inline throughout):

| Topic | Reference |
|---|---|
| REST API design | Zalando RESTful API Guidelines — https://opensource.zalando.com/restful-api-guidelines/ |
| Idempotency keys on POST | Stripe idempotent requests — https://docs.stripe.com/api/idempotent_requests |
| 12FactorApp Alignment | https://12factor.net/ |
| Gitflow branching model | https://nvie.com/posts/a-successful-git-branching-model/ |
| OAuth 2.1 (PKCE mandatory for public clients) | https://oauth.net/2.1/ |
| Spring Authorization Server | https://docs.spring.io/spring-authorization-server/reference/index.html |
| WebAuthn / passkeys | W3C WebAuthn Level 3 — https://www.w3.org/TR/webauthn-3/ · Spring Security passkeys — https://docs.spring.io/spring-security/reference/servlet/authentication/passkeys.html |
| API versioning (Spring Framework 7) | https://docs.spring.io/spring-framework/reference/web/webmvc-versioning.html |
| OWASP | Top 10 — https://owasp.org/Top10/ · Logging cheat sheet (why banner secrets are redaction-guarded) — https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html |
| Dev-credential console precedent | Spring Boot's generated dev password — https://docs.spring.io/spring-boot/reference/web/spring-security.html |
| Database migrations | Liquibase best practices — https://docs.liquibase.com/concepts/bestpractices.html |
| Observability | OpenTelemetry — https://opentelemetry.io/docs/ · Grafana LGTM all-in-one — https://github.com/grafana/docker-otel-lgtm · RED method — https://grafana.com/blog/2018/08/02/the-red-method-how-to-instrument-your-services/ |
| WebAuthn secure-context requirement | https://developer.mozilla.org/en-US/docs/Web/Security/Secure_Contexts |
| ATM frontend (React SPA) | https://github.com/sean-huni/fe-bank-withdrawal |

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
- **Caching is deliberately minimal** — a single `@Cacheable` on the immutable transaction-by-id read. Write-once
  ledger rows can never go stale, so no `@CacheEvict`/`@CachePut` exists anywhere; account balances and statements
  are never cached because they change with every write.
- **Layering** — strict dto / domain / persistence-model separation with MapStruct as the only crossing point. This
  paid off mechanically: swapping pessimistic locking for the guarded `RETURNING` update touched only `jdbc/repo`,
  and the idempotency cache replays pure domain records.
- **Persistence** — Spring Data JDBC keeps the persistence model deliberately simple: aggregates load and save eagerly
  in single statements, there is no persistence context or lazy proxying, and cross-aggregate references are plain ids.
- **Testing** — 30 Cucumber scenarios (positive + negative per flow) run over real HTTP: `@SpringBootTest(RANDOM_PORT)`
  plus Spring Framework 7's `RestTestClient`, including W3C `traceparent` propagation scenarios that only a real server
  can satisfy. Scenarios cover: idempotent replay debits once, key reuse with a different body conflicts, two parallel
  withdrawals of 70 against a balance of 100 yield exactly one `201` and one `422`, cache hits proven via Caffeine
  statistics.

## 3. Fixed code

The fixed implementation is this repository (the Spring Data JDBC variant, merged from `feat-jdbc`; `feat-jpa` carries the Spring Data JPA variant of
the persistence baseline). Entry points, in reading order:

1. [`AccountTransactionController`](src/main/java/com/example/bank/ctl/AccountTransactionController.java) — the lean HTTP boundary
2. [`AccountTransactionService`](src/main/java/com/example/bank/service/AccountTransactionService.java) — the business flow (compare directly with the original snippet)
3. [`AccountRepo`](src/main/java/com/example/bank/data/repo/AccountRepo.java) — the atomic guarded `RETURNING` debit
4. [`IdempotencyAspect`](src/main/java/com/example/bank/idempotency/IdempotencyAspect.java) — retry safety
5. [`WithdrawalEventListener`](src/main/java/com/example/bank/event/listener/WithdrawalEventListener.java) / [`SnsWithdrawalEventPublisher`](src/main/java/com/example/bank/event/publisher/SnsWithdrawalEventPublisher.java) — the fixed notification path
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
- **MapStruct** — `componentModel = "spring"`; generated mappers are the only layer-crossing code; with Data JDBC
  there are no lazy proxies to worry about — aggregate references are plain `UUID` fields.
- **Data JDBC `@Query` + `RETURNING`** — Data JDBC's `@Query` is always native SQL. The `UPDATE … RETURNING balance`
  statement is issued as a row-returning query (`queryForObject`-style), keeping the funds check and debit atomic in
  one round-trip without a JPA persistence context.
- **Spring Cloud AWS** — still no Boot 4-compatible release; the raw AWS SDK v2 `SnsClient` bean is wired
  manually (`SnsClientConfig`).
- **springdoc** — the 3.x line (`springdoc-openapi-starter-webmvc-ui:3.0.3`) is the Boot 4-compatible release
  and is integrated; the starter owns the swagger-annotations stack (no explicit pin — a version mismatch
  causes `NoSuchMethodError` during spec generation).
- **Testcontainers** — versions are no longer managed by the Boot 4 BOM (explicit `testcontainers-bom` import);
  LocalStack is pinned to the `4.x` community line (`2026.x` images require a paid license token) and reuses the
  same SNS init hook as docker compose.
