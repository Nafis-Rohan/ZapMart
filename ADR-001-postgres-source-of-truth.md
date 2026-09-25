# ADR-001: Postgres is the source of truth for idempotency, Redis is only a cache

- Status: accepted
- Date: 2026-09-25
- Scope: checkout and payment idempotency (Phase 2)

## Context
Checkout and payment can be sent several times for one purchase (double click, client retry, network timeout). Without protection, each copy reaches the payment provider. In the load test with the layer off, 300 purchases sent 903 charge requests, and 2 of every 3 duplicate requests returned a 500.

We need one place that decides, atomically, which request is the winner for a given `(user, Idempotency-Key)`, and that stays correct after restarts and crashes.

## Options considered

### A. Redis only
- Pros: very fast, simple `SET NX` claim, built-in TTL.
- Cons: not durable by default. A restart, eviction or failover can lose a key, and a lost key makes a retry look new, so the customer could be charged twice. The idempotency record cannot commit together with the order or payment row.

### B. Postgres only
- Pros: durable, atomic claim through a unique constraint and `INSERT ... ON CONFLICT`, transactional with the business data, easy to inspect and clean up.
- Cons: every repeat request hits the database, including replays of finished purchases.

### C. Postgres as source of truth plus Redis as a read-through cache (chosen)
- Postgres decides who wins and stores the durable result.
- Redis holds a copy of COMPLETED responses so repeat lookups skip Postgres.

## Decision
Use option C.
1. **Claim**: an atomic `INSERT ... ON CONFLICT` on `idempotency_keys`. Exactly one request wins. Expired rows are recycled in the same statement, and stale IN_PROGRESS rows can be reclaimed after a crash.
2. **Lookup**: check Redis first (`idem:{userId}:{key}`). On a miss, run the Postgres claim logic.
3. **Completion**: commit COMPLETED plus the response to Postgres first, then write to Redis with a TTL equal to the row's `expires_at`.
4. **Redis never decides a winner.** If Redis is empty, wrong, or unavailable, the Postgres claim still gives the correct answer.
5. **Stripe key passthrough**: `pay-{userId}-{clientKey}` is sent as Stripe's own `Idempotency-Key`, covering the case where Stripe charged but the app crashed before saving.

## Consequences

Positive:
- Correctness does not depend on Redis staying up or keeping its data.
- Measured (300 purchases/min, 3 concurrent duplicates each): charge requests dropped from 903 to 300, duplicate-request server errors dropped from 67% to 0, and late replays were served from Redis in about 2 ms (p95 3 ms).

Negative and accepted:
- Two stores to run and keep consistent. The write order (Postgres, then Redis) and equal TTLs limit the risk. The worst case is a Redis miss, which is safe.
- Extra table that needs cleanup. An hourly scheduled job deletes expired rows in batches of 1000.
- Duplicates that arrive while the first request is running get `409` with `Retry-After` instead of waiting. The client must retry.

## Known gaps
- No automated integration test against a real Redis, including the "Redis down falls back to Postgres" case. The fallback is by design only.
- No measured "Postgres only" stage, so the exact gain from Redis over Postgres alone is not quantified.
- The Stripe webhook does not yet update payment or order state.
