# Load test: duplicate checkouts and payments

Proves that N identical requests sent at the same instant create exactly one order and one charge.

## Files
- `checkout-duplicates.js` - the k6 script (one iteration = cart, 3 simultaneous checkouts, 3 simultaneous payments, same keys)
- `reset.ps1` - empties orders, carts, payments, idempotency keys, Redis and the fake Stripe counter
- `verify.ps1` - counts the real rows in Postgres after a run

## One-time setup
- Docker running: `zapmart-postgres` (host port 5433) and `zapmart-redis` (host port 6380)
- Test database created once: `docker exec -it zapmart-postgres psql -U zapmart -d ZapMart_DB -c "CREATE DATABASE zapmart_test;"`
- k6 on the PATH for this terminal: `$env:Path += ";C:\Program Files\k6"`

## Start the app in load-test mode
Spring profile `loadtest` (fake Stripe + database `zapmart_test`), for example the environment variable
`SPRING_PROFILES_ACTIVE=loadtest`, plus the stage switch below.

## The three stages (same script, only the app setting changes)

| Stage | App environment variables (besides the profile) | Expected |
|---|---|---|
| 1 no idempotency | `IDEMPOTENCY_ENABLED=false` | duplicate orders and charges appear |
| 2 Postgres only | `IDEMPOTENCY_CACHEENABLED=false` (no underscore between CACHE and ENABLED) | 0 duplicates, `Redis keys` must show 0 |
| 3 Postgres + Redis | none | 0 duplicates, faster replays |

For each stage: restart the app with the settings, then

```
.\loadtest\reset.ps1
k6 run -e RATE=1500 -e DURATION=2m -e STAGE=1-no-idempotency loadtest/checkout-duplicates.js
.\loadtest\verify.ps1
```

`RATE` is purchase attempts per minute (each with 3 duplicate requests). Ladder: 1500, then 2000, then 3000.
Use a different `STAGE` name per run (for example `2-postgres-only`, `3-postgres-redis`); k6 writes
`loadtest/results-<STAGE>.json`.

## Reading the result
- `DUPLICATE ORDERS` and `DUPLICATE PAYMENTS` must be 0 in stages 2 and 3.
- `Fake Stripe charge calls` should equal `Payments created`.
- `dropped iterations` above 0 means the machine could not keep up with that rate: report the rate that ran cleanly.
