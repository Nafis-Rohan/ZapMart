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
}