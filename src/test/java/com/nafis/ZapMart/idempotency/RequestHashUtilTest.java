package com.nafis.ZapMart.idempotency;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestHashUtilTest {

    @Test
    void sameInputsProduceSameHash() {
        String first = RequestHashUtil.hash("POST", "/api/orders/42/pay", "pm_card_visa");
        String second = RequestHashUtil.hash("POST", "/api/orders/42/pay", "pm_card_visa");

        assertEquals(first, second);
    }

    @Test
    void differentOrderIdProducesDifferentHash() {
        String orderA = RequestHashUtil.hash("POST", "/api/orders/42/pay", "pm_card_visa");
        String orderB = RequestHashUtil.hash("POST", "/api/orders/99/pay", "pm_card_visa");

        assertNotEquals(orderA, orderB);
    }

    @Test
    void differentPaymentMethodProducesDifferentHash() {
        String visa = RequestHashUtil.hash("POST", "/api/orders/42/pay", "pm_card_visa");
        String declined = RequestHashUtil.hash("POST", "/api/orders/42/pay", "pm_card_visa_chargeDeclined");

        assertNotEquals(visa, declined);
    }

    @Test
    void partBoundariesMatter() {
        String first = RequestHashUtil.hash("ab", "c");
        String second = RequestHashUtil.hash("a", "bc");

        assertNotEquals(first, second);
    }

    @Test
    void hashIs64LowercaseHexCharacters() {
        String hash = RequestHashUtil.hash("POST", "/api/orders/checkout");

        assertEquals(64, hash.length());
        assertTrue(hash.matches("[0-9a-f]{64}"));
    }

    @Test
    void nullPartIsTreatedAsEmpty() {
        String withNull = RequestHashUtil.hash("POST", null);
        String withEmpty = RequestHashUtil.hash("POST", "");

        assertEquals(withNull, withEmpty);
    }
}