package com.nafis.ZapMart.idempotency;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "idempotency")
@Getter
@Setter
public class IdempotencyProperties {

    /** How long a key is remembered (Stripe-standard: 24h). */
    private long ttlHours = 24;

    /** How long an IN_PROGRESS request owns the key before it can be reclaimed as stale. */
    private long lockSeconds = 60;

    /**
     * Master switch. When false the layer is bypassed and checkout/payment run unprotected. Exists ONLY
     * so the load test can measure the "before" stage; leave it true everywhere else.
     */
    private boolean enabled = true;

    /** When false the Redis cache is skipped and Postgres alone answers (the "Postgres only" load-test stage). */
    private boolean cacheEnabled = true;

    /** How many expired rows the cleanup job deletes per database round trip. */
    private int cleanupBatchSize = 1000;
}