# PLAN.md — Phase Roadmap

Not a fixed contract — this changes if reality demands it. Changes get logged in
`MEMORY.md`, not silently made here.

**Current main plan (detailed in PRD/RULES/TASK/ARCHITECTURE/MEMORY): Phase 1 → Phase 2.**
Phases 3–5 below are directional only; they get their own doc set when we reach them.

---

## Phase 1 — Base E-commerce (no idempotency, no JWT)
Electronics-components store backend. Products, cart, checkout, Stripe payment.
Entities tracked via Flyway migrations from the start. Fake `X-User-Id` header stands
in for auth. Feature-based package structure, SOLID, DRY, Lombok, N+1-aware queries.
**Done when**: a single checkout successfully charges via Stripe end-to-end, manually verified.

## Phase 2 — Payment Idempotency Layer *(current main focus)*
The resume centerpiece. Postgres-backed idempotency key table as source of truth,
Redis as read-through cache, atomic claim + stale-lock reclaim, request-hash mismatch
detection, Stripe-side idempotency key passthrough, scheduled cleanup.
**Done when**: a concurrency load test proves exactly one charge results from N duplicate
concurrent requests, with a sequence diagram + ADR + k6 results as artifacts.

## Phase 3 — Orders & Recommendations *(direction only, not yet detailed)*
Formal `Order` lifecycle/state machine (pending → paid → shipped → etc.), handling
webhook retries without double-transitioning. Simple recommendation engine based on
order co-occurrence (no ML) — "customers who bought X also bought Y."

## Phase 4 — bKash Integration *(direction only, not yet detailed)*
Second payment provider via the Strategy pattern (shared `PaymentStrategy` interface,
Stripe and bKash as interchangeable implementations). Ngrok for local webhook testing
against bKash sandbox.

## Phase 5 — Real Auth + Frontend *(direction only, not yet detailed)*
- Replace the fake `X-User-Id` header with real Spring Security + JWT: authentication,
  RBAC (admin vs. customer), rate limiting. Since every user-scoped table already has
  `user_id`, this should be a filter-chain swap, not a data model rewrite.
- React frontend consuming the finished API.
