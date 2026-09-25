# Payment Idempotency Layer

Makes checkout and payment safe to retry: the same request sent many times, even at the same instant, runs once and charges once.

**Stack:** Java 17, Spring Boot, PostgreSQL, Redis, Stripe, k6

## The problem
Double clicks, client retries and network timeouts send duplicate checkout and payment requests. Without protection, each copy reaches the payment provider. Measured with the layer off (300 purchases/min, each sent 3 times at once): **903 charge requests for 301 purchases**, and 2 of every 3 duplicate requests returned a 500.

## How it works
1. Client sends an `Idempotency-Key` header (required; missing gives `400`).
2. The request is hashed (SHA-256 of explicit request values).
3. **Claim:** one atomic Postgres `INSERT ... ON CONFLICT` on `idempotency_keys`. Exactly one request wins.
4. Winner runs the real work, stores the response as COMPLETED in Postgres, then writes it to Redis.
5. Duplicate while the first is running: `409` + `Retry-After`.
6. Duplicate after completion: stored response is replayed, no second execution.
7. Same key, different request body: `422`.
8. Crashed winner: a stale lock can be reclaimed by a later request.
9. The key is also passed to Stripe as `pay-{userId}-{clientKey}`, so Stripe blocks a double charge even if the app crashed mid-request.

## Why Postgres and Redis
- **Postgres is the source of truth:** durable, atomic claim through a unique constraint, transactional with business data.
- **Redis is a read-through cache** (`idem:{userId}:{key}`, TTL matches the row's `expires_at`): repeat lookups skip Postgres. It never decides a winner, so if Redis is empty or down, Postgres still gives the correct answer.
- Postgres is committed first, then Redis, so a failed cache write is safe.

## Code map
| Class | Role |
|---|---|
| `IdempotentExecutor` | wraps checkout and payment; applies the layer |
| `IdempotencyService` | claim logic and status branching (COMPLETED / live lock / stale lock) |
| `IdempotencyKeyRepository` | atomic claim and expired-batch delete SQL |
| `cache/IdempotencyCacheService` | Redis read-through / write-through |
| `IdempotencyCleanupJob` | hourly batched delete of expired keys |
| `IdempotencyProperties` | settings: TTL, lock timeout, cleanup, on/off switches |

## Testing
- **Unit:** claim logic, cache, executor, cleanup job, request hash.
- **Integration:** the real claim / reclaim SQL against Postgres.
- **Load (k6):** each purchase sent 3 times concurrently against a fake Stripe (about 200 ms delay). See [`/loadtest`](../../../../../../../loadtest/README.md).

## Results (300 purchases/min, 3 concurrent duplicates each)
| | Idempotency off | Postgres + Redis |
|---|---|---|
| Charge requests | 903 (3 per purchase) | 300 (1 per purchase) |
| Duplicate-request outcome | 602 x 500 per endpoint | 600 x 409, 0 x 500 |
| Late retry | n/a | 300/300 replayed, avg 2 ms |

Duplicate orders were 0 in both runs: the database constraint already blocked them, so the win is fewer provider calls and no errors.

## Known limits
- No automated test against a real Redis, including the "Redis down" fallback (by design, not test-proven).
- No measured Postgres-only stage.
- The Stripe webhook verifies the signature but does not update payment or order state yet.
- Highest clean load-test rate: 300 purchases/min on a laptop.