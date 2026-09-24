package com.nafis.ZapMart.idempotency;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final IdempotencyKeyRepository repository;
    private final IdempotencyProperties properties;

    /**
     * REQUIRES_NEW so the claim is committed immediately, even if the caller is inside a
     * transaction. Otherwise a concurrent duplicate would not see the row until the caller finished.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW) //So even if Transaction A later fails, Transaction B's committed idempotency record remains.
    public ClaimResult claim(Long userId, String key, String requestHash) {
        Instant now = Instant.now();
        Instant lockedUntil = now.plusSeconds(properties.getLockSeconds());
        Instant expiresAt = now.plus(Duration.ofHours(properties.getTtlHours()));

        int claimed = repository.claim(userId, key, requestHash, lockedUntil, expiresAt, now);
        if (claimed == 1) {
            return ClaimResult.proceed(findRequired(userId, key).getId());
        }

        Optional<IdempotencyKey> existing = repository.findByUserIdAndIdempotencyKey(userId, key);
        if (existing.isEmpty()) {
            // Row was deleted by the cleanup job between our insert and our read: try once more.
            claimed = repository.claim(userId, key, requestHash, lockedUntil, expiresAt, now);
            if (claimed == 1) {
                return ClaimResult.proceed(findRequired(userId, key).getId());
            }
            existing = repository.findByUserIdAndIdempotencyKey(userId, key);
        }

        IdempotencyKey row = existing.orElseThrow(
                () -> new IllegalStateException("Idempotency key vanished during claim: " + key));
        return decide(row, requestHash, now, lockedUntil);
    }

    /** Stores the finished response so retries can replay it. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(Long keyId, int responseStatus, String responseBody) {
        finish(keyId, IdempotencyStatus.COMPLETED, responseStatus, responseBody);
    }

    /**
     * Stores a deterministic failure (e.g. 400 empty cart, card declined) so retries replay it.
     * Unexpected errors (crash, timeout) should NOT call this: the lock simply goes stale
     * and the next retry reclaims the key.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(Long keyId, int responseStatus, String responseBody) {
        finish(keyId, IdempotencyStatus.FAILED, responseStatus, responseBody);
    }

    private ClaimResult decide(IdempotencyKey row, String requestHash, Instant now, Instant newLockedUntil) {
        if (!row.getRequestHash().equals(requestHash)) {
            return ClaimResult.mismatch();
        }

        if (row.getStatus() != IdempotencyStatus.IN_PROGRESS) {
            return ClaimResult.replay(row.getResponseStatus(), row.getResponseBody());
        }

        if (row.getLockedUntil() != null && row.getLockedUntil().isAfter(now)) {
            long retryAfter = Math.max(1, Duration.between(now, row.getLockedUntil()).getSeconds());
            return ClaimResult.inProgress(retryAfter);
        }

        // Lock is stale: only one concurrent request can win this atomic update.
        int reclaimed = repository.reclaimStaleLock(row.getId(), newLockedUntil, now);
        if (reclaimed == 1) {
            return ClaimResult.proceed(row.getId());
        }
        return ClaimResult.inProgress(1);
    }

    private void finish(Long keyId, IdempotencyStatus status, int responseStatus, String responseBody) {
        IdempotencyKey row = repository.findById(keyId)
                .orElseThrow(() -> new IllegalStateException("Idempotency key not found: " + keyId));
        row.setStatus(status);
        row.setResponseStatus(responseStatus);
        row.setResponseBody(responseBody);
        row.setLockedUntil(null);
        repository.save(row);
    }

    private IdempotencyKey findRequired(Long userId, String key) {
        return repository.findByUserIdAndIdempotencyKey(userId, key)
                .orElseThrow(() -> new IllegalStateException("Claimed key not found: " + key));
    }
}
