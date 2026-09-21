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
