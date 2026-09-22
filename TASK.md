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
- [ ] `Order` + `OrderItem` entities + migration
- [ ] Checkout service — snapshot cart → order, compute totals
- [ ] Order controller (create checkout, view order)

## Segment 4 — Stripe Payment (no idempotency yet)
- [ ] `Payment` entity + migration
- [ ] Stripe client config (API key, webhook secret via env)
- [ ] Payment service — create PaymentIntent, confirm, handle basic success/failure
- [ ] Payment controller + Stripe webhook endpoint
- [ ] Manual test: single successful end-to-end charge

**→ Phase 1 complete when Segment 4 passes a manual happy-path checkout-to-charge test.**

---

## Segment 5 — Idempotency Schema & Core Claim Logic
- [ ] `idempotency_keys` table + migration (per PRD schema)
- [ ] `IdempotencyService.claim()` — atomic `INSERT ... ON CONFLICT` claim logic
- [ ] Request hash utility (SHA-256 of normalized request body)
- [ ] Status branching: COMPLETED / IN_PROGRESS+locked / IN_PROGRESS+stale (reclaim)

## Segment 6 — Redis Read-Through Cache
- [ ] Redis client config
- [ ] Cache-first lookup before hitting Postgres claim logic
- [ ] Write-through on completed response, TTL matched to `expires_at`

## Segment 7 — Wiring Into Checkout/Payment Flow
- [ ] Interceptor/filter or service-level wrapper applying idempotency to checkout endpoint
- [ ] Conflict response shape (`409` + `Retry-After`, `422` on hash mismatch)
- [ ] Pass idempotency key through to Stripe's own `Idempotency-Key`

## Segment 8 — Cleanup & Proof
- [ ] Scheduled job sweeping expired keys
- [ ] k6 (or equivalent) script firing concurrent duplicate requests
- [ ] Sequence diagram of the race condition + reclaim path
- [ ] Short ADR: why Postgres is source of truth, not Redis-only

**→ Phase 2 (main goal) complete when Segment 8's load test proves exactly one charge
under concurrent duplicate requests.**
