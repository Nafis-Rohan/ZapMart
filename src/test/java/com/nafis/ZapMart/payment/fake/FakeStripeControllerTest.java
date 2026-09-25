package com.nafis.ZapMart.payment.fake;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FakeStripeControllerTest {

    private FakeStripeController controller;

    @BeforeEach
    void setUp() {
        controller = new FakeStripeController(0);
    }

    @Test
    void chargeReturnsSucceededPaymentIntentAsJson() {
        ResponseEntity<String> response = controller.createPaymentIntent("pay-1-a");

        assertEquals(200, response.getStatusCode().value());
        assertEquals(MediaType.APPLICATION_JSON, response.getHeaders().getContentType());
        assertTrue(response.getBody().contains("\"status\":\"succeeded\""));
        assertTrue(response.getBody().contains("\"id\":\"pi_fake_1\""));
    }

    @Test
    void everyChargeGetsItsOwnId() {
        String first = controller.createPaymentIntent("k1").getBody();
        String second = controller.createPaymentIntent("k2").getBody();

        assertNotEquals(first, second);
    }

    @Test
    void statsCountEveryRequestAndDistinctKeys() {
        controller.createPaymentIntent("k1");
        controller.createPaymentIntent("k1");
        controller.createPaymentIntent("k2");

        Map<String, Integer> stats = controller.stats();

        assertEquals(3, stats.get("chargeRequests"));
        assertEquals(2, stats.get("distinctIdempotencyKeys"));
    }

    @Test
    void chargeWithoutAKeyIsStillCounted() {
        controller.createPaymentIntent(null);

        Map<String, Integer> stats = controller.stats();

        assertEquals(1, stats.get("chargeRequests"));
        assertEquals(0, stats.get("distinctIdempotencyKeys"));
    }

    @Test
    void resetSetsEverythingBackToZero() {
        controller.createPaymentIntent("k1");

        controller.reset();
        Map<String, Integer> stats = controller.stats();

        assertEquals(0, stats.get("chargeRequests"));
        assertEquals(0, stats.get("distinctIdempotencyKeys"));
    }
}