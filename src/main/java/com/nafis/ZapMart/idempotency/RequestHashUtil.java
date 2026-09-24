package com.nafis.ZapMart.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class RequestHashUtil {

    private RequestHashUtil() {
    }

    /**
     * SHA-256 (hex) of the values that define what a request asks for, e.g.
     * ("POST", "/api/orders/42/pay", "pm_card_visa"). Never include the idempotency key itself.
     * Each part is length-prefixed so ("ab", "c") and ("a", "bc") produce different hashes.
     */
    public static String hash(String... parts) {
        StringBuilder canonical = new StringBuilder();
        for (String part : parts) {
            String value = part == null ? "" : part;
            canonical.append(value.length()).append(':').append(value).append('|');
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}