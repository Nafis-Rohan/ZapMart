# ARCHITECTURE.md — Structure, Flow, Tech Stack

## Tech Stack
- **Project**: ZapMart
- **Language/Framework**: Java 17, Spring Boot 4.1.1 (Web, Data JPA, Validation), Maven
- **Database**: PostgreSQL, database name **`ZapMart_DB`** — durable source of truth for all entities, including idempotency keys
- **Cache**: Redis — fast-path read cache for idempotency lookups (and later, guest carts)
- **Migrations**: Flyway — every schema change is a versioned migration, no manual DDL
- **Payments**: Stripe Java SDK (Phase 1–2), bKash via strategy pattern (Phase 4, later)
- **Boilerplate reduction**: Lombok (`@Getter`, `@Setter`, `@Builder`, `@NoArgsConstructor`, etc.)
- **Containerization**: Docker Compose — Postgres, Redis, and the backend service together
- **Auth**: deferred — fake `X-User-Id` header until Phase 5 (Spring Security + JWT)

## Directory Structure (Feature-Based)

```
src/main/java/com/nafis/ZapMart/
├── common/
│   ├── BaseEntity.java              # id, createdAt, updatedAt — shared by all entities
│   ├── exception/                   # global exception handling, custom exceptions
│   └── security/                    # fake-user header resolver (real JWT plugs in here later)
│
├── product/
│   ├── Product.java
│   ├── ProductRepository.java
│   ├── ProductService.java
│   └── ProductController.java
│
├── cart/
│   ├── Cart.java
│   ├── CartItem.java
│   ├── CartRepository.java
│   ├── CartService.java
│   └── CartController.java
│
├── order/
│   ├── Order.java
│   ├── OrderItem.java
│   ├── OrderRepository.java
│   ├── OrderService.java
│   └── OrderController.java
│
├── payment/
│   ├── Payment.java
│   ├── PaymentRepository.java
│   ├── PaymentService.java
│   ├── PaymentController.java
│   ├── stripe/
│   │   └── StripeClientConfig.java
│   └── webhook/
│       └── StripeWebhookController.java
│
└── idempotency/
    ├── IdempotencyKey.java              # entity mapped to idempotency_keys table
    ├── IdempotencyKeyRepository.java
    ├── IdempotencyService.java          # claim / reclaim / status branching
    ├── RequestHashUtil.java             # SHA-256 of normalized request body
    ├── IdempotencyCacheService.java     # Redis read-through layer
    └── IdempotencyCleanupJob.java       # scheduled sweep of expired keys

src/main/resources/
├── db/migration/                        # Flyway: V1__init.sql, V2__products.sql, ...
└── application.yml
```

Rule of thumb: a new feature = a new top-level package with its own entity, repository,
service, controller — not new folders under `controllers/`, `services/`, etc.

## Request Flow — Checkout with Idempotency (Phase 2 target state)

```
Client
  │  POST /checkout   Header: Idempotency-Key: <uuid>, X-User-Id: <id>
  ▼
CheckoutController
  ▼
IdempotencyService.claim(userId, key, requestHash)
  │
  ├─ Redis cache hit (COMPLETED)? ──────────────► return cached response (fast path)
  │
  ├─ Postgres INSERT ... ON CONFLICT DO NOTHING
  │     ├─ 0 rows affected → key already claimed
  │     │     ├─ status COMPLETED           → return stored response
  │     │     ├─ status IN_PROGRESS + fresh lock → 409 Conflict (Retry-After)
  │     │     └─ status IN_PROGRESS + stale lock → reclaim (update locked_until), proceed
  │     └─ 1 row affected → this request owns the key, proceed
  ▼
CheckoutService → OrderService (create order) → PaymentService (Stripe charge,
                                                 same Idempotency-Key passed to Stripe)
  ▼
On success: write response + status=COMPLETED to Postgres, then to Redis (TTL = expires_at)
On failure: status=FAILED (or leave reclaimable depending on failure type)
  ▼
Response returned to client
```

## Why Postgres Is the Source of Truth (not Redis-only)
Redis gives speed but no durability guarantee under eviction/restart. A payment dedupe
mechanism that can silently forget a key is worse than having none — it would let a
duplicate charge through under memory pressure. Postgres's unique constraint on
`(user_id, idempotency_key)` is the actual correctness guarantee; Redis just avoids
hitting Postgres on the 99% of requests that are repeat reads of an already-completed key.
