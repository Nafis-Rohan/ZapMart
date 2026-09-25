package com.nafis.ZapMart.payment.fake;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A stand-in for Stripe, for load tests only. It exists ONLY in the "loadtest" profile, so it can
 * never be reached in a normal run. It answers like a successful charge after a small delay and
 * counts every charge request it receives, so a load test can prove that N duplicate requests
 * produced exactly one charge request.
 */
@Profile("loadtest")
@RestController
@RequestMapping("/fake-stripe")
public class FakeStripeController {

    private final long delayMs;
    private final AtomicInteger chargeRequests = new AtomicInteger();
    private final Set<String> distinctIdempotencyKeys = ConcurrentHashMap.newKeySet();

    public FakeStripeController(@Value("${fakestripe.delay-ms:200}") long delayMs) {
        this.delayMs = delayMs;
    }

    // The Stripe Java SDK calls POST {api-base}/v1/payment_intents with a form body; we ignore the body.
    @PostMapping("/v1/payment_intents")
    public ResponseEntity<String> createPaymentIntent(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        pause();
        int number = chargeRequests.incrementAndGet();
        if (idempotencyKey != null) {
            distinctIdempotencyKeys.add(idempotencyKey);
        }
        String json = "{\"id\":\"pi_fake_" + number + "\",\"object\":\"payment_intent\","
                + "\"status\":\"succeeded\",\"currency\":\"usd\"}";
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(json);
    }

    @GetMapping("/stats")
    public Map<String, Integer> stats() {
        return Map.of(
                "chargeRequests", chargeRequests.get(),
                "distinctIdempotencyKeys", distinctIdempotencyKeys.size());
    }

    @PostMapping("/reset")
    public Map<String, String> reset() {
        chargeRequests.set(0);
        distinctIdempotencyKeys.clear();
        return Map.of("status", "reset");
    }

    private void pause() {
        if (delayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}