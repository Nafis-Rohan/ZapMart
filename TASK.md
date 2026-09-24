# TASK.md — Work Broken Into Segments

Status legend: `[ ]` not started · `[~]` in progress · `[x]` done

Each segment = one focused work session. Don't mix segments in one code batch.
**Branching**: all of Phase 1 (Segments 0–4) lands on a single branch, `feature/phase-1`,
per `RULES.md` — not one branch per segment. One PR into `main` when Phase 1 is fully done.
Each segment still gets its own commit(s) within that branch, with clear commit messages.
Testing checkpoints (unit after each service/logic piece, integration after each full
vertical slice) are called out inline per `RULES.md` §1a — not listed as separate
checklist items below, but expect a reminder after each relevant piece of code.

---

## Segment 0 — Project Skeleton
- [x] GitHub repo `ZapMart` created, `main` branch protected (no direct commits)
- [x] Spring Boot project init (Web, Data JPA, Validation, Lombok, PostgreSQL driver, Redis, Flyway)
- [x] `docker-compose.yml` — Postgres (`ZapMart_DB`) + Redis + backend service
- [x] `application.properties`/`.yml` base config (profiles: local/docker), datasource → `ZapMart_DB`
- [x] `BaseEntity` (id, createdAt, updatedAt, `@MappedSuperclass`)
- [x] First Flyway migration (`V1__create_users_table.sql`) — users table + 2 seed users
- [x] Branch `feature/project-skeleton` → PR #1 → merged
- [x] Extra: `User`, `Role`, `UserRepository` (for fake `X-User-Id` admin check)

## Segment 1 — Product Catalog
- [x] `Product` entity + Flyway migration (`V2__create_products_table.sql`) — written, uncommitted
- [x] Product repository (simple JpaRepository with name search + category filter, paginated)
- [x] Product DTOs (`ProductRequest`, `ProductResponse` records) + exception handling (`ResourceNotFoundException`, `ForbiddenException`, `GlobalExceptionHandler`)
- [x] `AdminGuard` (fake `X-User-Id` header → admin check)
- [x] Product service (CRUD) — compiles; unit test pending
- [x] Product controller (list/search/get/create/update/delete) — compiles; manual + integration test pending

## Segment 2 — Cart
- [x] `Cart` + `CartItem` entities + Flyway migration (`V3__create_carts_table.sql`) — written and verified (Flyway applied, schema at version 3)
- [x] Cart repository (`CartRepository` — `findByUserId` + fetch-joined `findByUserIdWithItems` to avoid N+1)
- [x] Cart DTOs (`CartItemRequest`, `CartItemQuantityRequest`, `CartItemResponse`, `CartResponse`)
- [x] Cart service (add/remove/view item, quantity update) — compiles; unit test written and passing (10/10 tests)
- [x] Cart controller (`GET /api/cart`, `POST /api/cart/items`, `PUT /api/cart/items/{productId}`, `DELETE /api/cart/items/{productId}`) — manually tested end-to-end via Postman (create → view → add → bump quantity → update quantity → remove), all working. Integration test still deliberately skipped, per `MEMORY.md`.
- [ ] *(Deferred sub-task, do later)* Redis-backed guest cart path

## Segment 3 — Checkout & Orders
- [x] `Order` + `OrderItem` entities + migration (`V4__create_orders_table.sql`) — written and verified (Flyway applied cleanly, schema at version 4)
- [x] Checkout service — snapshot cart → order, compute totals — compiles; unit test written and passing (6/6 tests)
- [x] `OrderService` (list/get, separate from `CheckoutService`'s write path) — unit tested, 3/3 passing
- [x] Order controller (`POST /api/orders/checkout`, `GET /api/orders`, `GET /api/orders/{id}`) — manually tested end-to-end via Postman (empty-cart rejection, insufficient-stock rejection, successful checkout, cart cleared, list + get order), all working. Integration test deliberately skipped, per `MEMORY.md`.

## Segment 4 — Stripe Payment (no idempotency yet)
- [x] `Payment` entity + migration
- [x] Stripe client config (API key, webhook secret via env)
- [x] Payment service — create PaymentIntent, confirm, handle basic success/failure
- [x] Payment controller + Stripe webhook endpoint
- [x] Manual test: single successful end-to-end charge (all endpoints tested; PR #5 merged)

**→ Phase 1 COMPLETE (2026-09-24): merged to `main` via PR #5.**

---

## Segment 5 — Idempotency Schema & Core Claim Logic
- [x] `idempotency_keys` table + migration (`V6__create_idempotency_keys_table.sql`, `response_body` as TEXT)
- [x] `IdempotencyService.claim()` — atomic `INSERT ... ON CONFLICT` claim logic (`IdempotencyKeyRepository.claim`, expired rows recycled in the same statement)
- [x] Request hash utility (`RequestHashUtil`, SHA-256 of explicit request values) — unit tested, 6/6
- [x] Status branching: COMPLETED / IN_PROGRESS+locked / IN_PROGRESS+stale (reclaim) — `IdempotencyServiceTest` 10/10 passing
- [x] Integration test for the atomic claim / reclaim SQL — `IdempotencyKeyRepositoryIntegrationTest` 5/5 passing (real Postgres via the Docker container, separate database `zapmart_test`; Testcontainers not used)
- [x] `concepts.md` — interview-style Q&A of every Segment 5 decision

## Segment 6 — Redis Read-Through Cache
- [x] Redis client config — Spring Boot's auto-configured `StringRedisTemplate` (host/port in `application.yml`), no custom config class needed
- [x] Cache-first lookup before hitting Postgres claim logic (`IdempotencyCacheService` in `idempotency/cache/`, wired into `IdempotencyService.claim()`)
- [x] Write-through on completed response, TTL matched to `expires_at` (Postgres commit first, then Redis)
- [x] Unit tests: `IdempotencyCacheServiceTest` (9), `IdempotencyServiceTest` (16, includes cache cases)
- [ ] *(Skipped for now, tracked)* Integration test with a real Redis (round trip + "Redis down" fallback)

## Segment 7 — Wiring Into Checkout/Payment Flow
- [x] Service-level wrapper applying idempotency to checkout AND payment (`IdempotentExecutor`; header `Idempotency-Key` required)
- [x] Conflict response shape (`409` + `Retry-After`, `422` on hash mismatch, `400` on missing key)
- [x] Pass idempotency key through to Stripe's own `Idempotency-Key` (`pay-{userId}-{clientKey}`)
- [x] Unit tests: `IdempotentExecutorTest` (12), `PaymentServiceTest` updated (6)
- [x] `segment7.md` — organized summary of what changed and why
- [ ] Manual Postman check of retry / replay / 409 / 422 / 400 (see `segment7.md` §8)
- [ ] *(Skipped for now, tracked)* automated end-to-end test of the two endpoints

## Segment 8 — Cleanup & Proof
- [ ] Scheduled job sweeping expired keys
- [ ] Fake Stripe stub (profile-based, ~200 ms delay) for load tests
- [ ] k6 script firing concurrent duplicate requests (same key, `http.batch`), rate ladder
      1,500 → 2,000 → 3,000 req/min (stretch 4,000); run 3 stages: no idempotency,
      Postgres-only, Postgres + Redis; verify duplicates by counting orders/payments in Postgres
- [ ] Sequence diagram of the race condition + reclaim path
- [ ] Short ADR: why Postgres is source of truth, not Redis-only

**→ Phase 2 (main goal) complete when Segment 8's load test proves exactly one charge
under concurrent duplicate requests.**
