# PRD — ZapMart (E-commerce + Payment Idempotency Layer)

## 1. Overview
**ZapMart** — an electronics-components e-commerce backend (Spring Boot + PostgreSQL
[`ZapMart_DB`] + Redis, Dockerized),
built as a vehicle to design and ship a production-grade **Payment Idempotency Layer**.
The e-commerce app is the host; the idempotency layer is the resume centerpiece.

## 2. Goal
- Primary: build and demonstrate a professional-grade idempotency layer that prevents
  duplicate payment processing under concurrent retries, client double-clicks, and
  network-level retries — backed by Postgres (source of truth) + Redis (fast-path cache).
- Secondary: a working, logically complete e-commerce backend (auth-deferred) that gives
  the idempotency layer a real payment flow to protect, instead of a synthetic demo.

## 3. Scope (this plan covers up to the idempotency layer — Phase 1 & 2 only)
Phases 3–5 (orders/recommendations, bKash, React frontend) are noted in `PLAN.md` for
direction but are **not** detailed in PRD/TASK/ARCHITECTURE yet — they'll get their own
docs when we reach them.

## 4. Core Features — Phase 1 (Base E-commerce)
- Product catalog: create/list/search/update/delete products (admin-only, faked auth for now)
- Shopping cart: add/remove/view items (Postgres-backed for logged-in path; Redis for guest path — deferred to Phase 2 per cart discussion)
- Checkout: create an order from cart contents
- Payment: Stripe integration, single successful charge path, **no idempotency yet**
- Fake-user identity: `X-User-Id` header stands in for JWT until Phase 5 equivalent
- Flyway-tracked schema from the first migration

## 5. Core Features — Phase 2 (Payment Idempotency Layer)
- Idempotency key contract: client sends `Idempotency-Key` header on checkout/payment calls
- Postgres `idempotency_keys` table as source of truth (atomic claim via `INSERT ... ON CONFLICT`)
- Redis as read-through cache for completed responses (sub-ms repeat-request path)
- Request hash validation — same key + different payload → `422`, not a false dedupe
- Stale lock reclaim (`locked_until`) — self-heals when a request dies mid-processing
- Idempotency key passthrough into Stripe's own `Idempotency-Key` (end-to-end dedupe)
- Scheduled cleanup of expired keys (24h TTL, Stripe-standard window)
- Load test proving dedupe under real concurrency + numbers for the resume writeup

## 6. Non-Goals (for now)
- JWT auth, RBAC, rate limiting — deliberately deferred to after Phase 4
- bKash integration — Phase 4, not touched in this doc set
- Frontend — Phase 5, not touched in this doc set

## 7. Success Criteria
- N concurrent identical checkout requests → exactly one Stripe charge, others served
  from cache/claim logic, none silently dropped or duplicated
- Redis outage mid-flow does not break correctness (Postgres fallback works)
- README-level artifacts: sequence diagram of the race condition, k6 load test results, ADR
