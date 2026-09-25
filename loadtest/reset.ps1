# Puts the load-test environment back to zero. Run it BEFORE every k6 stage.
# Keeps users and products; empties orders, carts, payments, idempotency keys, Redis and the fake Stripe counter.
#   run:  .\loadtest\reset.ps1
#   (if PowerShell blocks scripts:  powershell -ExecutionPolicy Bypass -File loadtest\reset.ps1)

Write-Host "1/3 Emptying test tables in database zapmart_test ..."
docker exec zapmart-postgres psql -U zapmart -d zapmart_test -c "TRUNCATE orders, order_items, payments, carts, cart_items, idempotency_keys RESTART IDENTITY CASCADE;"

Write-Host "2/3 Emptying Redis (it only holds cache data for this project) ..."
docker exec zapmart-redis redis-cli flushall

Write-Host "3/3 Resetting the fake Stripe counter (needs the app running in loadtest mode) ..."
try {
    Invoke-RestMethod -Method Post -Uri "http://localhost:9090/fake-stripe/reset" | Out-Null
    Write-Host "    done"
} catch {
    Write-Host "    skipped: the app is not running in loadtest mode (k6 resets it again itself)"
}

Write-Host "Reset finished."
