# Sequence diagrams: idempotency race and reclaim path

Diagrams use Mermaid (renders on GitHub and in most Markdown viewers).

## 1. The race: 3 identical requests at the same instant

R1, R2, R3 carry the same `Idempotency-Key`. Postgres picks exactly one winner with one atomic statement.

```mermaid
sequenceDiagram
    autonumber
    participant C as Client (3 identical requests)
    participant A as App (IdempotentExecutor)
    participant R as Redis (cache)
    participant P as Postgres (idempotency_keys)
    participant S as Stripe

    C->>A: R1, R2, R3 (same key)
    A->>R: GET idem:{user}:{key}
    R-->>A: miss
    par R1
        A->>P: INSERT ... ON CONFLICT (claim)
        P-->>A: claimed (winner)
    and R2
        A->>P: INSERT ... ON CONFLICT (claim)
        P-->>A: conflict, row IN_PROGRESS, lock is live
    and R3
        A->>P: INSERT ... ON CONFLICT (claim)
        P-->>A: conflict, row IN_PROGRESS, lock is live
    end
    A-->>C: R2, R3 get 409 + Retry-After
    A->>S: create PaymentIntent (Idempotency-Key: pay-{user}-{key})
    S-->>A: succeeded
    A->>P: mark COMPLETED + store response (commit first)
    A->>R: SET idem:{user}:{key} (TTL = expires_at)
    A-->>C: R1 gets 201

    Note over C,R: Later retry (client did not see the response)
    C->>A: same key, same body
    A->>R: GET idem:{user}:{key}
    R-->>A: hit (COMPLETED)
    A-->>C: stored response replayed, no second charge
```

Key points:
- Only one request runs the real work. The unique constraint decides the winner, not application code.
- Losers get `409` with `Retry-After` instead of waiting, so they do not hold a thread or a DB connection.
- Postgres commits before Redis is written. If the Redis write fails, the next request just falls back to Postgres.

## 2. Reclaim path: the winner crashed

The first request claimed the key and then the server died. The row is stuck IN_PROGRESS with an old lock.

```mermaid
sequenceDiagram
    autonumber
    participant C as Client (retry)
    participant A as App
    participant R as Redis
    participant P as Postgres
    participant S as Stripe

    Note over P: Row is IN_PROGRESS, lock older than the stale timeout
    C->>A: retry, same key
    A->>R: GET idem:{user}:{key}
    R-->>A: miss (never completed)
    A->>P: INSERT ... ON CONFLICT (claim)
    P-->>A: conflict, IN_PROGRESS but stale, reclaimed by this request
    A->>S: create PaymentIntent (same Stripe key pay-{user}-{key})
    S-->>A: original charge returned if it already happened, else a new one
    A->>P: mark COMPLETED + store response
    A->>R: SET idem:{user}:{key}
    A-->>C: 201
```

Key points:
- Without reclaim, a crash would block that key until it expires.
- The same Stripe key is reused, so if the charge already happened before the crash, Stripe returns it instead of charging again.
- Expired rows are recycled in the same claim statement.

## 3. Mismatch and missing key

| Situation | Result |
|---|---|
| No `Idempotency-Key` header | `400` |
| Same key, different request hash | `422` |
| Same key, first request still running (live lock) | `409` + `Retry-After` |
| Same key, first request finished | stored response replayed |
| Same key, first request crashed (stale lock) | reclaimed and re-run |