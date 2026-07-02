# payment-gateway-simulator — Design Blueprint

Single source of truth for all 3 services. Every phase prompt references a **section number** here instead of re-specifying schemas or contracts. Drop this file into the repo as `docs/DESIGN.md` in Phase 1 and point `CLAUDE.md` at it.

> **How to use this doc:** each phase prompt should be one line — *"Implement Phase N per DESIGN.md §X, §Y. Satisfy the Definition of Done in §16. Obey the guardrails in §13."* If a prompt needs to explain a schema, contract, or ID, this doc is incomplete — fix the doc, not the prompt.

> **Positioning:** this project targets a **PayPay / Japanese fintech backend** role. §18 covers the market-specific details (JPY correctness, throughput proof, README pitch) that make a recruiter think *"this person has built what we need."* Keep them in scope.

---

## 0. Tech Stack Lock (do not drift across phases)

| Concern | Locked choice |
|---|---|
| Language / JDK | Java 21 (LTS) |
| Framework | Spring Boot 3.4.x (needs `logging.structured.format`) |
| Build | Gradle (Kotlin DSL), one Gradle module per service |
| Messaging | Spring Kafka (`spring-kafka`) |
| DB | PostgreSQL 16, one container, **three logical databases** (`gateway`, `processor`, `notification`) — see §2 |
| Migrations | Flyway, per-service migration folders |
| Cache / counters | Redis 7 (shared) |
| API docs | springdoc-openapi → Swagger UI at `/swagger-ui` on every service |
| Auth | Thin JWT (Spring Security) with a demo-token dev endpoint — see §6, §14. Cuttable if time-constrained. |
| Dashboards | Prometheus + Grafana, **pre-provisioned**, non-blocking (app runs without them) |
| Money | `BigDecimal` in Java, `NUMERIC(19,4)` in DB, **string** on the wire, **scale validated per ISO-4217 currency exponent** (§4) |
| Container | `docker compose up` brings up the **entire** system, zero cloud, zero paid keys |

Claude Code must not swap build tool, JDK, or broker mid-project. **AWS and Kubernetes are never runtime dependencies** — they are a demonstration/talking-point layer only (§17). If a version genuinely can't be used, stop and ask.

---

## 1. Goals, Non-Goals & the Plug-and-Play Contract

**Goals:** demonstrate idempotent payment processing, event-driven architecture with Kafka, the transactional **outbox** pattern (no dual-write), ACID double-entry ledger correctness, retry/DLQ with business-vs-infra error classification, reconciliation, and correct multi-currency (incl. **JPY zero-decimal**) money handling — the concrete skills fintech / PayPay JDs ask for.

**Non-goals:** real payment-rail integration, real money movement, FX conversion, real KYC/fraud. This is a **simulator** — say so plainly in the README.

**Plug-and-play contract (hard requirement — a recruiter must succeed in ~60 seconds):**
1. `git clone` → `docker compose up` brings up all 3 services + Postgres + Kafka + Redis + Grafana. Nothing else. No cloud account, no paid API key, no manual DB setup.
2. Flyway **seeds demo accounts** (JPY + USD) on startup so payments work immediately.
3. `POST /auth/token` (dev) returns a **demo JWT**, pre-wired into Swagger's Authorize button and the README curl.
4. **Swagger UI** is reachable per service; a recruiter can click "Try it out" and watch a payment flow end to end.
5. A **`./demo.sh`** script fires a happy payment, a duplicate-idempotency-key payment, and an insufficient-funds payment, and prints the results + reconciliation status.
6. A **Grafana** dashboard shows live payment metrics. It is nice-to-have: if a recruiter skips it, everything else still works.

K8s manifests + a CI deploy stage exist as a demonstration layer only (via `kind`), never required to try the project.

---

## 2. Service Boundaries & Database-per-Service

Each service owns its own database. One Postgres **container**, three logical **databases** — logical isolation at zero extra cost. A service never reads or writes another service's tables; state crosses boundaries only via Kafka events or explicit HTTP calls.

| Service | Port | Owns DB | Responsibility |
|---|---|---|---|
| `api-gateway` | 8080 | `gateway` | Public REST API + JWT auth + Swagger. Validates, enforces idempotency, writes an **outbox** row that becomes `payment.created`. Never touches the ledger. `GET` status/reconciliation **proxy** to `payment-processor`. |
| `payment-processor` | 8081 | `processor` | Consumes `payment.created`. Owns accounts + ledger. Debits/credits in one ACID transaction, emits `payment.completed`/`payment.failed` **via outbox**. Owns retry/DLQ + `/reconciliation`. |
| `notification-service` | 8082 | `notification` | Consumes `payment.completed`/`payment.failed`. Sends stub notifications (webhook-ready, §17). Idempotent delivery (dedupes on `eventId`). |

**Rule:** only `payment-processor` writes the ledger. `api-gateway` never does. This boundary is what makes the event flow meaningful instead of decorative.

---

## 3. Identifiers (four different IDs — never conflate them)

This table exists because these get mixed up constantly. It is load-bearing.

| ID | Generated by | Scope | Used for |
|---|---|---|---|
| `Idempotency-Key` | **Client** | One HTTP `POST /payments` attempt | De-dupe retried POSTs (§7). |
| `paymentId` | **Server** (api-gateway) | The payment's lifetime | Payment identity; Kafka message **key** (§5). |
| `eventId` | **Producer** of each event | One event | Consumer-side de-dupe via `consumed_events` (§10). |
| `correlationId` | **api-gateway**, per request | End-to-end across all services | Tracing; lives in the Kafka **header** + MDC (§14). |

---

## 4. Event Envelope & Money Rules

```json
{
  "eventId": "uuid",
  "eventType": "payment.created | payment.completed | payment.failed",
  "occurredAt": "ISO-8601 timestamp",
  "correlationId": "uuid, propagated from the originating HTTP request",
  "paymentId": "uuid",
  "payload": { }
}
```

`correlationId` also goes in the Kafka message **header** (not just the body), so it survives even if a consumer reads headers for logging before deserializing the body.

**Concrete payloads (fixed — do not reinvent per phase):**

```jsonc
// payment.created
{ "fromAccount": "string", "toAccount": "string", "amount": "1000", "currency": "JPY" }

// payment.completed
{ "fromAccount": "string", "toAccount": "string", "amount": "1000", "currency": "JPY",
  "debitEntryId": "uuid", "creditEntryId": "uuid" }

// payment.failed
{ "fromAccount": "string", "toAccount": "string", "amount": "1000", "currency": "JPY",
  "failureReason": "INSUFFICIENT_FUNDS | ACCOUNT_NOT_FOUND | CURRENCY_MISMATCH | INVALID_AMOUNT_SCALE" }
```

**Money rules (fintech-critical, JP-specific):**
- `amount` is always a **string** in JSON, never a JSON number — no float precision loss.
- Validate the amount's decimal **scale against the currency's ISO-4217 minor-unit exponent**: **JPY = 0 decimals** (¥1000, never ¥1000.00), USD = 2, KWD = 3. An amount with more fractional digits than the currency allows → `400 INVALID_AMOUNT_SCALE`. Store as `NUMERIC(19,4)` (a safe superset), but **validate and format** by exponent.
- Getting JPY right is the detail a Japanese fintech reviewer notices first — treat it as a core requirement, not a nicety.

---

## 5. Kafka Topics

| Topic | Key | Purpose |
|---|---|---|
| `payment.created` | `paymentId` | api-gateway → payment-processor |
| `payment.completed` | `paymentId` | payment-processor → notification-service |
| `payment.failed` | `paymentId` | payment-processor → notification-service |
| `payment.created.dlq` | `paymentId` | dead-letter target for `payment-processor`'s consumer, after retries are exhausted (infra errors only) |
| `payment.completed.dlq` | `paymentId` | dead-letter target for `notification-service`'s consumer, same classification rule |
| `payment.failed.dlq` | `paymentId` | dead-letter target for `notification-service`'s consumer, same classification rule |

Keyed by `paymentId` so all events for one payment land on the same partition — per-payment ordering without global ordering.

---

## 6. API Contract — `api-gateway`

```
POST /auth/token            (dev only) → { "token": "<demo JWT>" }   # frictionless demo auth

POST /payments
Header: Authorization: Bearer <JWT>
Header: Idempotency-Key: <client-generated UUID>   (required)
Body:   { "fromAccount": "string", "toAccount": "string",
          "amount": "string decimal", "currency": "string (ISO 4217)" }

201  { "paymentId": "uuid", "status": "PENDING", "idempotencyKey": "uuid" }
200  duplicate key, same request body → the original stored response, no reprocessing
400  validation error (incl. amount scale invalid for currency, §4)
401  missing/invalid JWT
409  same Idempotency-Key reused with a DIFFERENT request body (request-hash mismatch)
429  rate limit exceeded

```

**Not implemented in `api-gateway`** (unbuilt "read-only admin views," §17 — a deliberate scope
cut, not an oversight): a `GET /payments/{paymentId}` status proxy and a `GET /reconciliation`
proxy. `payment-processor` exposes `GET :8081/reconciliation` directly (§12) — that's what
`./demo.sh` and any manual check use today. Payment status can be read from the `processor` DB's
`payments` table until a proxy endpoint exists.

Every service exposes **Swagger UI** at `/swagger-ui` with the demo JWT pre-fillable via the Authorize button. Auth is a thin layer — if time-constrained, ship a permissive `demo` profile and note it in the README.

---

## 7. Idempotency Design (§6 partner)

Two-layer: **Postgres is the source of truth, Redis is the fast path.**

1. On `POST`, check Redis `idempotency:{key}`. Hit → return cached response, no reprocessing, no event.
2. Miss → check `idempotency_keys` in Postgres (Redis may have evicted). Hit → return stored response, repopulate Redis.
3. Miss in both → proceed:
   - In **one transaction**: insert `idempotency_keys` (key, paymentId, `request_hash`, `response_status`, `response_body`) **and** insert an `outbox` row for `payment.created` (§8). Commit.
   - Then cache the response in Redis, 24h TTL.

**Concurrency (must be handled):** two simultaneous requests with the same key can both miss steps 1-2. Rely on the `idempotency_keys` **primary-key constraint** — the loser catches the unique-violation, re-reads the winner's row, returns that stored response. Never double-publish.

**Key reuse with a different body:** compute `request_hash` over the canonical body. Key exists but hash differs → `409` (do not process). This is Stripe's behavior and interviewers ask about it.

Why Postgres is truth and Redis is only cache: eviction or a restart must never cause a duplicate payment. In doubt, hit Postgres.

---

## 8. Transactional Outbox (removes the dual-write)

Publishing to Kafka and writing the DB are **not** one atomic action. Both `api-gateway` and `payment-processor` use an outbox:

1. Business transaction writes its rows **and** an `outbox` row (event, headers, payload) in the **same DB transaction**. All-or-nothing.
2. A relay (`@Scheduled` poller, small fixed delay) reads unpublished `outbox` rows in order, publishes to Kafka, marks `published_at`. At-least-once by design; duplicates absorbed by consumer de-dupe (§10).

Guarantees an event is emitted **iff** its transaction committed — no lost events, no phantom events. (CDC/Debezium is a valid alternative; the poller is chosen for zero-cost simplicity — note this trade-off in the README.)

If time-constrained: keep the outbox in `payment-processor` non-negotiable (it guards the completed/failed events that carry the actual ledger outcome). The `api-gateway` outbox is the more defensible one to defer, since a lost `payment.created` publish is detectable as a stuck `PENDING` payment via reconciliation, whereas a lost `payment.completed` publish silently masks a correctly-settled payment from downstream consumers.

---

## 9. Schemas (Flyway, per service)

**`gateway` DB (api-gateway):**
```sql
CREATE TABLE idempotency_keys (
    key             VARCHAR PRIMARY KEY,
    payment_id      UUID NOT NULL,
    request_hash    VARCHAR NOT NULL,
    response_status INT NOT NULL,
    response_body   JSONB NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE outbox (
    id           UUID PRIMARY KEY,
    aggregate_id UUID NOT NULL,          -- paymentId
    event_id     UUID NOT NULL UNIQUE,
    event_type   VARCHAR NOT NULL,
    headers      JSONB NOT NULL,         -- includes correlationId
    payload      JSONB NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ             -- NULL until relayed
);
```

**`processor` DB (payment-processor):**
```sql
CREATE TABLE accounts (
    account_id      VARCHAR PRIMARY KEY,
    balance         NUMERIC(19,4) NOT NULL,
    opening_balance NUMERIC(19,4) NOT NULL, -- immutable seed value; §12 reconciliation compares against this
    currency        VARCHAR(3) NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE payments (
    id             UUID PRIMARY KEY,
    status         VARCHAR NOT NULL CHECK (status IN ('PENDING','COMPLETED','FAILED')),
    from_account   VARCHAR NOT NULL,
    to_account     VARCHAR NOT NULL,
    amount         NUMERIC(19,4) NOT NULL,
    currency       VARCHAR(3) NOT NULL,
    failure_reason VARCHAR,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE ledger_entries (
    id         UUID PRIMARY KEY,
    payment_id UUID NOT NULL,
    account_id VARCHAR NOT NULL,
    direction  VARCHAR NOT NULL CHECK (direction IN ('DEBIT','CREDIT')),
    amount     NUMERIC(19,4) NOT NULL,
    currency   VARCHAR(3) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE consumed_events (
    event_id    UUID PRIMARY KEY,
    event_type  VARCHAR NOT NULL,
    payment_id  UUID,                        -- populated for payment.created; used by §12 reconciliation
    consumed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- plus an outbox table, same shape as gateway.outbox, for completed/failed events
```

**`notification` DB:** `consumed_events` (same shape) for de-dupe, plus `notifications` (`id, payment_id, event_id, channel, status, sent_at`).

Seed accounts via a Flyway seed migration: at least two **JPY** accounts (one well-funded, one near-empty to demo insufficient funds) and one USD account. The demo must work with zero manual setup.

---

## 10. Ledger Transaction (the ACID core — Phase 3 is highest-stakes)

On `payment.created`, in **one transaction**:

1. Insert `consumed_events(eventId)` first — if it already exists (PK conflict), this is a redelivery: **skip and commit** (consumer idempotency).
2. `SELECT ... FOR UPDATE` the `from` account (pessimistic lock).
3. Validate: account exists, currency matches both accounts, `balance >= amount`.
   - **Business failure** (missing account, currency mismatch, insufficient funds): set payment `FAILED` + `failure_reason`, write an `outbox` row for `payment.failed`, **commit**. Normal outcome — do **not** throw, do **not** DLQ.
   - **Success:** write two `ledger_entries` (DEBIT from, CREDIT to), update both `accounts.balance`, set payment `COMPLETED`, write an `outbox` row for `payment.completed`.
4. Commit. Debit + credit + balance updates + status + consumed-event + outbox all succeed together or roll back together — a failed transaction reverts to a state consistent with "never happened."

**Invariant:** every `COMPLETED` payment produces exactly two ledger rows, equal and opposite.

---

## 11. Retry & Dead-Letter Strategy

Spring Kafka `DefaultErrorHandler` + `ExponentialBackOff` (start 1s, x2, max 3 attempts) + `DeadLetterPublishingRecoverer` → `<source-topic>.dlq`. Applied identically on every consumer in the system — `payment-processor`'s `payment.created` listener and `notification-service`'s `payment.completed`/`payment.failed` listener both use this exact bean shape, each routing to its own source topic's `.dlq`. Don't hand-roll retry.

**Classification is the point** — only *infra* errors reach this handler:
- **Retryable** (transient infra: deadlock, connection loss) → backoff retries → DLQ if exhausted.
- **Not retryable** (deserialization / poison message) → `addNotRetryableExceptions(...)` → straight to DLQ.
- **Business outcomes** (insufficient funds, etc.) never throw — they produce `payment.failed` and commit (§10). Never retry or DLQ them.

Document a manual DLQ replay path so "how do you recover a dead-lettered payment" has an answer.

---

## 12. Reconciliation

`GET /reconciliation` (on `payment-processor`) reports any of:
- a `COMPLETED` payment without exactly 2 matching `ledger_entries`;
- a consumed `payment.created` event with no `payments` row;
- global double-entry breakage: `SUM(DEBIT) != SUM(CREDIT)`;
- an account whose `balance` doesn't match its seeded balance +/- net ledger entries.

Empty list = healthy. The concrete "how would you detect a stuck or lost payment" interview story.

---

## 13. Guardrails — things Claude Code must NEVER do

- Never publish to Kafka and write the DB as if atomic — always go through the **outbox** (§8).
- Never use `double`/`float` for money; never ignore the currency exponent (JPY has 0 decimals, §4).
- Never let one service read or write another service's database (§2).
- Never hand-roll Kafka retry (§11).
- Never retry or DLQ a business failure (§10/§11).
- Never let `api-gateway` write the ledger.
- Never require AWS or Kubernetes to run the project (§1 plug-and-play contract).
- Never commit real credentials — Gitleaks + Trivy enforce from Phase 7.

---

## 14. Security & Observability Conventions

- Thin **JWT** auth (Spring Security) on `POST /payments`; `/auth/token` dev endpoint mints a demo token; Swagger Authorize pre-fill. Cuttable via a `demo` profile.
- Bean Validation (`@NotNull`, `@Positive` `BigDecimal`, `@Pattern` for ISO-4217, custom scale-by-currency validator) on every request DTO.
- Rate limiting at `api-gateway` via the Redis-counter pattern proven in distributed-auth-platform Day 3 (reuse the approach, not the code).
- Gitleaks + Trivy in CI from Phase 7.
- Actuator + Micrometer Prometheus registry on all 3 services; **Grafana** with a pre-provisioned payments dashboard (throughput, success/fail rate, p99 latency, DLQ depth).
- `correlationId` generated at `api-gateway`, carried in the Kafka header (§4), read into MDC by each consumer — one payment's logs traceable across all 3 services by one ID.
- Structured JSON logging via Spring Boot's built-in `logging.structured.format`.
- **Swagger/OpenAPI** on every service.

---

## 15. Testing Strategy

- Unit tests: idempotency check, ledger transaction, exception classification, outbox relay, **currency-scale validation (JPY 0-decimal, USD 2-decimal)**.
- Testcontainers (Postgres + Kafka + Redis) E2E per phase: `POST` → event flow → ledger state → notification. Include a **JPY happy path**, an **insufficient-funds** path, and a **duplicate Idempotency-Key** path.
- Contract test for the `POST /payments` schema.
- **Throughput / load-test artifact** (§18): a `k6` (or `vegeta`/`hey`) script firing concurrent payments, asserting idempotency held and printing throughput + p99. Report this as an honest local-laptop benchmark and a demonstration of load-testing methodology — do not frame it as enterprise-scale; a docker-compose stack on one machine will produce modest numbers, and overclaiming invites the exact scrutiny this doc is built to survive.
- Same discipline as Project 1: bring the stack up and drive real traffic before declaring a phase done.

---

## 16. Execution Plan & Definition of Done

Each phase = its own branch + PR + merge. Run phases back-to-back as the window allows. A phase is done only when its DoD holds against a **running** stack.

| Phase | Scope (§refs) | Definition of Done |
|---|---|---|
| 1 | Scaffold, docker-compose (all deps incl. Grafana), per-service Flyway + JPY/USD seed, `CLAUDE.md`→this doc, CI skeleton, Swagger wired (§0, §1, §2) | `docker compose up` starts everything healthy; migrations + seed run; `/actuator/health` = UP; `/swagger-ui` loads on each service. |
| 2 | Payment initiation + idempotency + amount-scale validation (§4, §6, §7, §9-gateway) | `POST` returns 201; JPY with decimals → 400; replay same key → 200 same body; different body → 409; concurrent dup → single record. |
| 3 | **Kafka flow + ACID ledger via outbox — mandatory Plan Mode, do not bundle** (§4, §5, §8, §10) | `payment.created` consumed once; happy path writes 2 balanced ledger rows + updates balances + emits `payment.completed`; redelivery does not double-post. |
| 4 | Retry, DLQ, reconciliation, insufficient-funds path (§10, §11, §12) | Insufficient funds → `payment.failed`, no DLQ; forced infra error → retried → DLQ; `GET /reconciliation` empty on a clean run. |
| 5 | Notification service (§2, §4) | Consumes completed/failed; duplicate `eventId` delivers once; audit row written. |
| 6 | Full E2E + contract tests (§15) | Testcontainers E2E green for JPY happy, insufficient-funds, and duplicate-key paths; contract test green. |
| 7 | Security hardening + JWT + CI (§13, §14) | JWT enforced (demo token works via Swagger); rate-limit 429 works; Gitleaks + Trivy pass in CI. |
| 8 | Observability + Grafana + load test + K8s deploy demo (§14, §15, §17) | One `correlationId` traces a payment across all 3 services; Grafana dashboard populated; load-test script runs and prints throughput/p99 with honest local-benchmark framing; `kind` deploy demo documented (optional to run). |
| 9 | Docs, Mermaid diagrams, `./demo.sh`, README PayPay pitch, final polish (§1, §18) | README states "simulator" + plug-and-play quickstart; architecture + sequence Mermaid diagrams; `./demo.sh` runs the 3 scenarios; DESIGN.md matches the code. |

**If the window gets tight, cut in this order:** (1) Grafana dashboard — already proven in Project 1, no new signal here; (2) full JWT flow — ship the permissive `demo` profile noted in §6 instead; (3) `api-gateway`'s outbox specifically (§8 note) — never cut `payment-processor`'s. Do not cut business/infra failure classification, JPY correctness, or the outbox in `payment-processor` — those are what make this project read as "built for PayPay" rather than a generic CRUD-with-Kafka demo.

---

## 17. Optional Extensions (documented as clean add-ons, not redesigns)

These map onto the existing architecture without changing it — mention them in the README as "next steps" even if unbuilt:
- **Refund flow:** a refund is a reverse payment (CREDIT <-> DEBIT swapped) referencing the original `paymentId`. Reuses the ledger + outbox unchanged.
- **Webhook delivery with retry:** the notification-service already dedupes + retries; swap the stub sink for an HTTP webhook with the same retry/DLQ discipline as §11.
- **Read-only admin views:** expose `GET` endpoints over payments/ledger/DLQ depth; Grafana already covers the metrics side.
- **AWS/K8s parity note (talking point, not a dependency):** the compose services map 1:1 to RDS (Postgres), ElastiCache (Redis), MSK (Kafka), EC2/EKS (services). State this in the README so the cloud-readiness story is explicit without requiring any cloud account.

---

## 18. Japan / PayPay Positioning

The technical work is only half the value — the framing is what lands the interview.

- **JPY correctness is the signature detail.** The zero-decimal handling (§4) plus JPY seed accounts makes a PayPay reviewer immediately register domain understanding. Show a JPY payment in the README's first example.
- **Answer the JD in the README, in its own words.** PayPay Backend Engineer asks for *"design large-scale systems to support high throughput"* and *"RESTful APIs, Pub/Sub systems."* Add a short "How this maps to the role" section citing: REST + OpenAPI (api-gateway), Pub/Sub (Kafka topics §5), throughput methodology + honest numbers (§15), idempotency + distributed transactions (§7, §8, §10).
- **Prove throughput methodology, don't oversell the number.** Put the load-test result (payments/sec, p99, "idempotency held across N duplicate keys") in a README table with a Grafana screenshot, labeled clearly as a local benchmark. This is the strongest artifact for the "large-scale" JD line as long as it's framed honestly.
- **60-second reviewer path.** README top: `git clone && docker compose up`, then `./demo.sh`, then the Swagger link and Grafana link. Make it impossible to miss how to run it.
- **Name the tech PayPay uses.** Java/Kotlin on JVM, Spring, Kafka, PostgreSQL, Redis — this stack already matches; say so briefly so the reviewer makes the leap for you.
