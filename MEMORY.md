# MEMORY.md — Context, Decisions, Bugs, Changes

This file is the running log. Append to it as the project moves — don't rewrite history,
just add new dated entries below.

## Key Decisions (standing, until revisited)
- **Project name**: ZapMart. **Database name**: `ZapMart_DB`.
- **Init setup**: Maven, Java 17, Spring Boot 4.1.1, Jar packaging, Properties config.
- **Git workflow**: GitHub repo `ZapMart`, `main` protected (PR required, no mandatory
  approvals since solo). **Branch granularity: one branch per phase** (`feature/phase-1`
  covers all of Phase 1's segments as commits, single PR at the end) — changed from the
  original per-segment-branch plan.
- **Testing**: unit tests (JUnit 5 + Mockito) after each service/logic piece, integration
  tests (`@SpringBootTest`, Testcontainers) after each full vertical slice — called out
  explicitly as a checkpoint, not left implicit. See `RULES.md` §1a.
- **Stack**: Spring Boot, PostgreSQL, Redis, Docker Compose. Flyway for all schema changes.
- **Directory structure**: feature-based packages (`product/`, `cart/`, `payment/`,
  `idempotency/`), not layer-based.
- **Auth deferred**: no JWT/Spring Security until after Phase 4. Fake identity via
  `X-User-Id` header. Every user-scoped table gets `user_id` from day one so Phase 5
  auth is a filter swap, not a schema migration.
- **Phase 1 scope**: base e-commerce (products, cart, checkout, Stripe payment) with
  **no idempotency** — that's deliberately Phase 2's job, kept separate so it can be
  built and shown as its own thing.
- **Cart storage**: Postgres-backed for logged-in users; Redis-only path for guest
  carts is a deferred sub-task, not blocking Phase 1.
- **Idempotency design** (the centerpiece):
  - Postgres `idempotency_keys` table = source of truth; Redis = fast-path cache only.
  - Atomic claim via `INSERT ... ON CONFLICT DO NOTHING`, not check-then-act.
  - Request hash (SHA-256 of body) stored alongside key — mismatched payload on same
    key returns `422`, not a false-positive dedupe.
  - `locked_until` field enables stale-lock reclaim if a request dies mid-processing.
  - Idempotency key passed through to Stripe's own `Idempotency-Key` header for
    end-to-end dedupe, not just at this service's boundary.
  - 24h TTL on keys (Stripe-standard), swept by a scheduled cleanup job.
- **Delivery rules**: code shipped 2–3 files at a time (up to 4 if small), each with
  exact directory path and a short rationale. See `RULES.md`.

## Bugs / Issues Log
- **2026-09-21 — Postgres "password authentication failed" despite correct docker-compose credentials.**
  Root cause: a native PostgreSQL install on Windows was already running as a background
  service on port 5432, silently competing with Docker's Postgres container for the same
  port. App connections were landing on the native install (which has no `zapmart` user),
  not the Docker container.
  Fix: mapped Docker's Postgres to a different host port instead of touching the native
  install — `docker-compose.yml` port mapping is `"5433:5432"`, and `application.yml`
  datasource URL is `jdbc:postgresql://localhost:5433/ZapMart_DB`.
  **Standing fact: local Postgres for this project always runs on port 5433, not 5432.**
  Remember this for any future `.env` file, README setup instructions, or CI config.

- **2026-09-22 — Maven couldn't resolve `spring-boot-starter-parent` (IntelliJ: "Parent ...
  has problems"); IntelliJ also flagged the global `settings.xml` as having a syntax error.**
  Root cause: `C:\Users\nafis\.m2\settings.xml` (global, used by every Maven project on this
  machine — not part of this repo) held a publish credential for a different project, under
  `<server><id>central</id>`. That id collides with Maven's own built-in "central" repository
  (the one it downloads dependencies from), so Maven tried authenticating downloads with
  those publish credentials and failed. It was also missing the standard settings.xml XML
  namespace, which is what triggered IntelliJ's separate "syntax error" flag.
  **Fix (temporary, by choice): renamed it to `settings.xml.bak`**, so Maven ignores it
  entirely — meaning ZapMart (and any other project) now resolves dependencies with no
  custom global settings. **Standing fact: to publish that other project again, rename
  `settings.xml.bak` back to `settings.xml` first, then rename it away again afterward** —
  don't leave it active as `settings.xml` while working on ZapMart.

## Changes Log
- **2026-09-22 — Base package is `com.nafis.ZapMart`**, not `com.app` as originally written
  in `ARCHITECTURE.md`. Doc updated to match the code.
- **2026-09-22 — Added RULES.md §0**: no auto mode; Claude asks before every action and works in small steps.
- **2026-09-22 — SHORTCUT: product search.** The repository stays a simple `JpaRepository`
  (by choice). `ProductService.list` uses `name` OR `category`, not both — if both are sent,
  only `name` applies. Revisit if combined filtering is needed.
- **2026-09-22 — Admin check** lives in `AdminGuard` (`common/security`), called from the
  controller with the `X-User-Id` header, not inside the service.
- **2026-09-22 — `Product.price`** now declares `precision = 10, scale = 2` to match the `NUMERIC(10,2)` migration.
- **2026-09-22 — App now runs on port 9090, not 8080.** Cause: Windows/Docker Desktop's
  WSL2 networking periodically reserves ("excludes") port ranges for its own use — 8080
  fell inside one (`8020–8119`), so Tomcat couldn't bind even though nothing was actually
  running on it. Moved `server.port` to `9090` in `application.yml` to sidestep it
  permanently, rather than fighting the OS reservation each time it recurs.
- **2026-09-22 — Segment 2 (Cart) started.** `Cart`/`CartItem` entities + `V3__create_carts_table.sql`
  migration written and verified (Flyway applied cleanly, schema at version 3). `carts.user_id`
  is `UNIQUE` (one cart per user); `cart_items` has `UNIQUE(cart_id, product_id)` (no duplicate
  product rows — quantity updates instead, enforced in the service layer later); `product_id`
  is `ON DELETE RESTRICT` (can't delete a product that's in a cart).
- **2026-09-22 — SHORTCUT: `CartService.addItem` does not validate against `Product.stockQuantity`.**
  A user can add more of a product than is actually in stock; nothing in `TASK.md`'s Segment 2
  scope calls for stock enforcement at the cart level. Deliberately deferred to checkout/payment
  (Segments 3–4), where stock actually needs to be locked/decremented. Revisit if we want
  earlier "not enough stock" feedback in the cart itself.
- **2026-09-22 — `CartService.getCart` does not persist an empty cart.** A user with no items
  yet gets an in-memory empty `CartResponse` (`id: null`); a `Cart` row is only created in the
  DB the first time `addItem` is called. Avoids junk empty-cart rows for users who never add
  anything.
- **2026-09-22 — `CartRepository`, Cart DTOs (`CartItemRequest`, `CartItemQuantityRequest`,
  `CartItemResponse`, `CartResponse`), `CartService`, and `CartServiceTest` written.**
  `CartServiceTest` — **10/10 tests passing.**
- **2026-09-22 — `CartController` written** (`GET /api/cart`, `POST /api/cart/items`,
  `PUT /api/cart/items/{productId}`, `DELETE /api/cart/items/{productId}`). No `AdminGuard` —
  cart endpoints are "my own cart," identity via `X-User-Id` only, same as read paths elsewhere.
  `PUT`/`DELETE` return the updated `CartResponse` (200) instead of `204 No Content`, so the
  client gets the fresh cart state without a second `GET`. This completes Segment 2's core
  vertical slice (entities → repository → service → controller); manual/integration test
  still pending, same as Product in Segment 1.
- **2026-09-22 — Integration test for Cart skipped for now**, same call as Product in
  Segment 1 — deliberate, not forgotten. Revisit as a batch later (per `RULES.md` §1a,
  this is a tracked shortcut, not silently dropped scope).
- **2026-09-22 — Cart manually tested end-to-end via Postman, all passing.** Flow verified:
  create product (admin) → view empty cart → add item → add same item again (quantity bumps
  4, not a duplicate row) → update quantity directly (10) → remove item (cart empties).
  **Segment 2 (Cart) is complete.**
- **2026-09-23 — Segment 3 (Checkout & Orders) started.** `Order`/`OrderItem` entities +
  `V4__create_orders_table.sql` written. Key design choice: `OrderItem` is a full snapshot
  (`productName`/`unitPrice` copied in at checkout, not a live link to `Product`) — unlike
  `CartItem`, which reads current product data. `order_items.product_id` is `ON DELETE
  SET NULL` (order history survives product deletion), unlike `cart_items.product_id`'s
  `ON DELETE RESTRICT`. `OrderStatus` is a small enum (`PENDING`/`PAID`/`FAILED`/`CANCELLED`),
  not a full state machine — that's explicitly Phase 3 scope per `PLAN.md`. `Order.userId`
  has no `UNIQUE` constraint (a user has many orders, unlike one cart per user).
- **2026-09-23 — `OrderRepository`, `BadRequestException` (+ `GlobalExceptionHandler` wiring),
  Order DTOs (`OrderItemResponse`, `OrderResponse`), `CheckoutService`, and
  `CheckoutServiceTest` written.** Two decisions confirmed with the user first: checkout
  **validates** stock (throws `BadRequestException` if requested qty > available) but does
  **not decrement** it — that happens on payment success in Segment 4, so an abandoned
  unpaid order doesn't hold inventory hostage. Checkout **clears the cart** on success.
  `CheckoutServiceTest` — **6/6 tests passing.**
- **2026-09-23 — `OrderService` (+ `OrderSummaryResponse` DTO) and `OrderServiceTest` written.**
  Kept separate from `CheckoutService` (single responsibility: one writes orders, the other
  reads them). `list()` returns lightweight `OrderSummaryResponse` (no item breakdown) instead
  of the full `OrderResponse` — avoids an N+1 lazy-load per order when listing a user's whole
  order history; `get(id)` still returns full item detail via the fetch-joined repository query.
  `OrderServiceTest` — **3/3 tests passing.**
- **2026-09-23 — `OrderController` written** (`POST /api/orders/checkout`, `GET /api/orders`,
  `GET /api/orders/{id}`). Delegates to `CheckoutService` (write) and `OrderService` (read)
  behind one controller. This completes Segment 3's core vertical slice; manual/integration
  test still pending, same as Product and Cart before it.
- **2026-09-23 — Integration test for Checkout/Orders skipped for now**, same call as
  Product and Cart — deliberate, tracked, not forgotten.
- **2026-09-23 — Checkout/Orders manually tested end-to-end via Postman, all passing.**
  Flow verified: checkout on empty cart → 400; add 2 (stock 3), bump to 5, checkout → 400
  insufficient stock; fix quantity to 2, checkout → 201 with full order; cart confirmed
  empty afterward; list orders shows summary; get by id shows full item detail.
  **Segment 3 (Checkout & Orders) is complete.**
- **2026-09-23 — Segment 4 (Stripe Payment) started.** `Payment` entity + `PaymentStatus`
  enum (`PENDING`/`SUCCEEDED`/`FAILED` only — Stripe's PaymentIntent already tracks the
  finer-grained states like `requires_action`/`processing` on its side; no need to duplicate
  that locally yet, revisit if Phase 3's order lifecycle work needs more granularity) +
  `V5__create_payments_table.sql` written and verified (app started, Flyway applied
  cleanly, schema at version 5). Key design choices confirmed with the user first:
  **one payment per order** (`payments.order_id` is `UNIQUE`) — no retry-attempts model,
  consistent with "no idempotency yet" scope for Phase 1; Phase 2's `idempotency_keys`
  table (separate, tracks the *request*, not the payment) owns retry/dedupe semantics.
  `payments.user_id` stored directly on the entity (not just reachable via order), per
  `RULES.md` §4 — every user-scoped table gets `user_id` from day one. `order_id` is
  `ON DELETE RESTRICT` (payments are financial records, never cascade-delete them).
- **2026-09-23 — Stripe SDK wired in.** Added `com.stripe:stripe-java:29.2.0` to `pom.xml`
  (latest stable, checked against Maven Central). `stripe.secret-key` / `stripe.webhook-secret`
  added to `application.yml`, both pulled from env vars (`STRIPE_SECRET_KEY`,
  `STRIPE_WEBHOOK_SECRET`) — never hardcoded. `StripeClientConfig` (`payment/stripe/`)
  sets `Stripe.apiKey` once on startup via `@PostConstruct`, so no other class touches the
  raw key. User set `STRIPE_SECRET_KEY` via IntelliJ run-config env vars, using a Stripe
  **sandbox/test-mode** key from their "Aventiq Tecnology sandbox" account (via `stripe-cli`,
  installed with `npm install -g @stripe/cli`, authorized with `stripe login`).
  `STRIPE_WEBHOOK_SECRET` left blank for now — to be filled in once the webhook controller
  exists, via `stripe listen --forward-to localhost:9090/api/payments/webhook`.
- **2026-09-23 — `PaymentRepository`, `PaymentRequest`/`PaymentResponse` DTOs, and
  `PaymentService` written.** `PaymentService.pay(userId, orderId, request)`: validates the
  order belongs to the caller and is still `PENDING`, blocks a second payment attempt on the
  same order (service-level check ahead of the DB unique constraint, so it fails with a clean
  `400` instead of a raw SQL error), creates **and synchronously confirms** a Stripe
  PaymentIntent using a client-supplied `paymentMethodId` (Stripe's test tokens, e.g.
  `pm_card_visa` / `pm_card_visa_chargeDeclined` — no frontend/Stripe.js yet, that's Phase 5).
  On success: `Payment` saved `SUCCEEDED`, order → `PAID`, stock decremented per order item
  (stock decrement deliberately happens here, not at checkout, per Segment 3's design). On
  decline: `Payment` saved `FAILED`, order → `FAILED`, stock untouched.
  **SHORTCUT (flagged, not silent): a failed payment permanently blocks that order from ever
  being paid** — since `payments.order_id` is `UNIQUE`, there's no retry path once one
  `Payment` row exists for an order. Acceptable for Phase 1 (manual test is the happy path
  only); revisit if failed-payment retry is needed before Phase 2's idempotency layer lands.
  Stripe's `PaymentIntentCreateParams.Builder` uses repeated-field builders (`addPaymentMethodType(String)`),
  not a `List` setter — caught via compile error, fixed.
  `PaymentServiceTest` written (static-mocks `PaymentIntent.create` via Mockito's
  `mockStatic`, no `mockito-inline` needed — inline mock maker is Mockito 5's default) —
  **5/5 tests passing.**
- **2026-09-24 — Load-test plan decided (for Segment 8).**
  - **Rate ladder:** run k6 at **1,500 → 2,000 → 3,000 req/min**, stepping up only if the
    previous step is clean. Stretch goal: 4,000/min (~67 req/s) if the machine allows. The
    resume number is whatever rate was actually run, never a higher one.
  - **Fake Stripe** (stub behind a profile, ~200 ms delay) for load tests — real Stripe test
    mode caps around 25 req/s and would return `429`s, spoiling the counts.
  - **Three-stage comparison:** (1) no idempotency → measure duplicate orders, (2) Postgres-only
    idempotency, (3) Postgres + Redis cache. Stage 2 must already be fully correct; Redis only
    improves latency/DB load.
  - **Duplicates must be truly concurrent** (k6 `http.batch`, same `Idempotency-Key`); proof is
    counted in Postgres (`orders`/`payments` grouped by user), not from HTTP responses.
  - **Duplicate-rate figure comes from our own measured run** (simulated retry share), not a
    copied number. Resume wording: Postgres-backed idempotency keys with a Redis fast-path
    cache — Redis is not the correctness source.
  - **Possible tuning:** Hikari pool (default 10) may need raising to ~20–30 at these rates.
- **2026-09-24 — Scope note: idempotency must cover checkout, not only payment.** The
  duplicate-order race is at checkout (two concurrent requests both read the full cart before
  either clears it). `payments.order_id UNIQUE` already blocks duplicate charges per order.
  Segment 7 wires idempotency into both.
- **2026-09-24 — Crash-window handling (design confirmed).** If the server crashes after Stripe
  charges but before we save: (1) Stripe webhook `payment_intent.succeeded` tells us later,
  (2) passing our key to Stripe's `Idempotency-Key` makes a retry return the original charge,
  (3) stale-lock reclaim lets the retry finish. **Known Phase 1 gap:** until the webhook exists,
  `PaymentService.pay()` has this crash window.
- **2026-09-24 — Phase 1 COMPLETE.** `PaymentController` and `StripeWebhookController` are
  done, all endpoints manually tested, `feature/phase-1` merged to `main` via PR #5. (Earlier
  entries today wrongly listed these as pending — docs were stale, code was ahead.) Phase 2
  (idempotency layer) starts at Segment 5. Stale local/remote feature branches still to be
  deleted per `RULES.md` §6.
- **2026-09-25 — Phase 2 started on branch `feature/phase-2`** (earlier edits were briefly made on
  `main` by mistake; nothing was committed or pushed, moved to the branch before any commit).
  Working mode: Claude writes code only; the user runs the app/tests/Flyway.
- **2026-09-25 — Segment 5 core written.** `V6__create_idempotency_keys_table.sql`,
  `IdempotencyKey` + `IdempotencyStatus`, `IdempotencyKeyRepository`, `RequestHashUtil`,
  `IdempotencyProperties`, `ClaimResult`, `IdempotencyService`. Decisions:
  - **The key belongs to a client request (one attempt), not to an order/payment.** Client
    generates it (`Idempotency-Key` header); server never generates client keys. No `order_id`
    column (option A). Payment hash covers method + path (order id) + `paymentMethodId`; the key
    itself is never part of the hash — key = identity, hash = content, stored in the same row.
  - **Hash built from explicit values, not raw JSON** (length-prefixed parts) — no JSON library
    dependency (Spring Boot 4 moved to a new Jackson package) and no formatting sensitivity.
  - **`claim` is one atomic `INSERT ... ON CONFLICT DO UPDATE ... WHERE expires_at < now`**, so an
    expired-but-not-yet-deleted row is recycled and never blocks a new request; the cleanup job
    (Segment 8) is housekeeping only, correctness does not depend on its timing.
  - **`claim`/`complete`/`fail` use `REQUIRES_NEW`** so the claim commits immediately and
    concurrent duplicates can see it even if the caller is inside a transaction.
  - **Deterministic failures (400, declined card) are stored as `FAILED` and replayed**; crashes
    and timeouts store nothing, the lock goes stale and the retry reclaims the key.
  - **TTL and lock time are configurable** (`idempotency.ttl-hours: 24`, `lock-seconds: 60`).
  - **Known limit:** after 24h a key is forgotten; long-term double-charge protection comes from
    `payments.order_id UNIQUE` and order status, not from this table.
  - `RequestHashUtilTest` 6/6 and `IdempotencyServiceTest` 10/10 written; service test passing
    (10/10 confirmed by the user). Repository SQL is mocked in unit tests, so an integration
    test (Testcontainers) is being written to prove the real atomic behaviour.
- **2026-09-25 — Segment 5 COMPLETE.** `IdempotencyKeyRepositoryIntegrationTest` 5/5 passing:
  8 concurrent claims of one key give exactly one winner, a live row is never overwritten, an
  expired row is recycled, only one of 8 concurrent reclaims of a stale lock wins, a fresh lock
  cannot be reclaimed. Unit tests: `RequestHashUtilTest` 6/6, `IdempotencyServiceTest` 10/10.
  - **Integration test approach (Option 2, not Testcontainers):** it runs as a `@SpringBootTest`
    against the Docker Postgres (`zapmart-postgres`, port 5433) but in a **separate database
    `zapmart_test`**, so dev data in `ZapMart_DB` is never touched. **Standing setup step, once per
    machine:** `docker exec -it zapmart-postgres psql -U zapmart -d ZapMart_DB -c "CREATE DATABASE zapmart_test;"`.
    The class name ends in `Test`, so `mvn test` also runs it and needs that database; revisit
    (Testcontainers or a separate `*IT` suffix) if CI is added.
  - **Windows note:** in PowerShell never type `<container>` literally, use the real name
    (`zapmart-postgres`); `<` is a reserved operator there.
  - Added `concepts.md` at the repo root: interview-style Q&A for Segment 5, to be extended per segment.
- **2026-09-25 — Segment 6 (Redis read-through cache) written.** `CachedResponse` +
  `IdempotencyCacheService` in `idempotency/cache/`, wired into `IdempotencyService`.
  - **Redis stores only FINISHED responses** (`COMPLETED`/`FAILED`), never `IN_PROGRESS`. One Redis
    hash per key (`idem:{userId}:{key}`) with fields requestHash/status/responseStatus/body, plain
    strings (no JSON library). The hash is kept so a cache hit can still return `422` on mismatch.
  - **Redis TTL = remaining lifetime of the Postgres row** (`expires_at`).
  - **Redis never affects correctness:** every cache method catches Redis errors, logs a warning
    and falls back to Postgres; a failed or partial write evicts the key so nothing lives without
    an expiry; a corrupt entry is treated as a miss and evicted.
  - **Order of operations:** cache check, then Postgres claim. Finished responses are written to
    Redis only AFTER the Postgres transaction commits. A finished row found in Postgres but not in
    Redis is put back (self-heal).
  - **`IdempotencyService` now uses a `TransactionTemplate` (REQUIRES_NEW) instead of
    `@Transactional`**, so a cache hit never opens a DB transaction and Redis is never written
    before Postgres commits. `complete`/`fail` now take a `userId` (needed for the cache key).
  - **SHORTCUT (flagged, tracked in TASK.md): no integration test against a real Redis.** The cache
    is covered by unit tests with mocks only, so real Redis behaviour (serialization, TTL, outage
    fallback) is unproven until the Segment 8 k6 run or a later Redis integration test.
- **2026-09-25 — Segment 7 (wiring into checkout/payment) written.** Full write-up in `segment7.md`.
  - `IdempotentExecutor` (`idempotency/`) wraps a controller action: validate key, claim, run,
    store (`complete`/`fail`), replay, `409`+`Retry-After`, `422`. Chosen over a filter/interceptor
    because it works on the finished result and business exceptions and is easy to unit test.
  - **`Idempotency-Key` header is REQUIRED** on `POST /api/orders/checkout` and
    `POST /api/orders/{orderId}/payments` (`400` if missing/blank/over 255 chars). Existing
    Postman calls for these two endpoints need the header from now on.
  - Controllers now return `ResponseEntity<String>` so the first response and replays are
    byte-identical. Replays add header `Idempotent-Replayed: true`.
  - Only known business errors (BadRequest/NotFound/Forbidden) are stored as `FAILED` and
    replayed; unexpected errors store nothing (lock goes stale, retry reclaims).
  - **Stripe passthrough:** `PaymentService.pay(..., stripeIdempotencyKey)` sends the key via
    `RequestOptions`; key is `"pay-" + userId + "-" + clientKey`. Closes the "Stripe charged but we
    crashed" gap together with stale-lock reclaim and the webhook.
  - Tests: `IdempotentExecutorTest` 12, `PaymentServiceTest` 6 (new: key reaches Stripe).
  - **SHORTCUTS (flagged):** no automated end-to-end test of the two endpoints (manual Postman
    check instead) and no real-Redis integration test. Executor repeats the status mapping of
    `GlobalExceptionHandler` for stored failures (commented). Checkout hash has no body, so a key
    reused for a different cart is treated as a retry.
  - `concepts.md` now has Segment 6 and Segment 7 sections. Note: `.gitignore` ignores
    `concepts.md`; `segment7.md` is NOT ignored (decide if it should be committed).
