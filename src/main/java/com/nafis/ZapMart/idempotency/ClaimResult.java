package com.nafis.ZapMart.idempotency;

public record ClaimResult(
        Outcome outcome,
        Long keyId,
        Integer responseStatus,
        String responseBody,
        Long retryAfterSeconds
) {

    public enum Outcome {
        /** This request owns the key: do the work, then call complete() or fail(). */
        PROCEED,
        /** Same request already finished: return the saved response, do no work. */
        REPLAY,
        /** Same request is running right now: answer 409 with Retry-After. */
        IN_PROGRESS,
        /** Same key, different request content: answer 422. */
        MISMATCH
    }

    public static ClaimResult proceed(Long keyId) {
        return new ClaimResult(Outcome.PROCEED, keyId, null, null, null);
    }

    public static ClaimResult replay(Integer responseStatus, String responseBody) {
        return new ClaimResult(Outcome.REPLAY, null, responseStatus, responseBody, null);
    }

    public static ClaimResult inProgress(long retryAfterSeconds) {
        return new ClaimResult(Outcome.IN_PROGRESS, null, null, null, retryAfterSeconds);
    }

    public static ClaimResult mismatch() {
        return new ClaimResult(Outcome.MISMATCH, null, null, null, null);
    }
}