# Counts the real rows in the database after a k6 stage. This is the proof, k6's own numbers are the second opinion.
#   run:  .\loadtest\verify.ps1

$sql = "select 'orders' as what, count(*) as n from orders " +
       "union all select 'payments', count(*) from payments " +
       "union all select 'users with MORE THAN ONE order (must be 0 with idempotency on)', count(*) from (select user_id from orders group by user_id having count(*) > 1) t " +
       "union all select 'extra orders beyond one per user', coalesce(sum(c - 1), 0) from (select count(*) as c from orders group by user_id having count(*) > 1) t2 " +
       "union all select 'idempotency rows', count(*) from idempotency_keys;"

docker exec zapmart-postgres psql -U zapmart -d zapmart_test -c $sql

Write-Host "Redis keys (idem:*):"
docker exec zapmart-redis redis-cli --scan --pattern "idem:*" | Measure-Object -Line | Select-Object -ExpandProperty Lines

Write-Host "Fake Stripe stats:"
try {
    Invoke-RestMethod -Uri "http://localhost:9090/fake-stripe/stats" | ConvertTo-Json
} catch {
    Write-Host "  app is not running in loadtest mode"
}
