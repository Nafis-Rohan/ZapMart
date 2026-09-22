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
