package com.nafis.ZapMart.idempotency.cache;

import com.nafis.ZapMart.idempotency.IdempotencyStatus;

public record CachedResponse(
        String requestHash,
        IdempotencyStatus status,
        int responseStatus,
        String responseBody
) {
}