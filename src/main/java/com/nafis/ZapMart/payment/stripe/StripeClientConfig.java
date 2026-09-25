package com.nafis.ZapMart.payment.stripe;

import com.stripe.Stripe;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class StripeClientConfig {

    @Value("${stripe.secret-key}")
    private String secretKey;

    // Optional. Empty (the default) means the real Stripe. Only the "loadtest" profile sets it, to
    // point the Stripe library at the fake Stripe endpoint inside this app.
    @Value("${stripe.api-base:}")
    private String apiBase;

    @PostConstruct
    public void init() {
        Stripe.apiKey = secretKey;
        if (apiBase != null && !apiBase.isBlank()) {
            Stripe.overrideApiBase(apiBase);
        }
    }
}