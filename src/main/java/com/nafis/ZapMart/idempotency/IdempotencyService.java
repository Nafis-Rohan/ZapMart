package com.nafis.ZapMart.idempotency;

import com.nafis.ZapMart.idempotency.cache.CachedResponse;
import com.nafis.ZapMart.idempotency.cache.IdempotencyCacheService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Service
public class IdempotencyService {

    private final IdempotencyKeyRepository repository;
    private final IdempotencyProperties properties;
    private final IdempotencyCacheService cache;
    private final TransactionTemplate requiresNew;

    /**
     * Postgres work runs in its own REQUIRES_NEW transaction so a claim is committed immediately,
     * even if the caller is inside a transaction. A TransactionTemplate (not @Transactional) is used
     * so the Redis cache check before the claim and the cache write after the commit happen outside
     * any transaction: a cache hit never touches the database, and Redis is never written before
     * Postgres has committed.
     */
    public IdempotencyService(IdempotencyKeyRepository repository,
                              IdempotencyProperties properties,
                              IdempotencyCacheService cache,
                              PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.properties = properties;
        this.cache = cache;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public ClaimResult claim(Long userId, String key, String requestHash) {
        Optional<CachedResponse> cached = cache.get(userId, key);
        if (cached.isPresent()) {
            CachedResponse hit = cached.get();
            if (!hit.requestHash().equals(requestHash)) {
                return ClaimResult.mismatch();
            }
            return ClaimResult.replay(hit.responseStatus(), hit.responseBody());
        }
        return requiresNew.execute(status -> claimInPostgres(userId, key, requestHash));
    }

    /** Stores the finished response so retries can replay it. */
    public void complete(Long userId, Long keyId, int responseStatus, String responseBody) {
        finish(userId, keyId, IdempotencyStatus.COMPLETED, responseStatus, responseBody);
    }

    /**
     * Stores a deterministic failure (e.g. 400 empty cart, card declined) so retries replay it.
     * Unexpected errors (crash, timeout) should NOT call this: the lock simply goes stale
     * and the next retry reclaims the key.
     */
    public void fail(Long userId, Long keyId, int responseStatus, String responseBody) {
        finish(userId, keyId, IdempotencyStatus.FAILED, responseStatus, responseBody);
    }

    private ClaimResult claimInPostgres(Long userId, String key, String requestHash) {
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

    private ClaimResult decide(IdempotencyKey row, String requestHash, Instant now, Instant newLockedUntil) {
        if (!row.getRequestHash().equals(requestHash)) {
            return ClaimResult.mismatch();
        }

        if (row.getStatus() != IdempotencyStatus.IN_PROGRESS) {
            // Finished row that was not in Redis (evicted, Redis was down, or written by another node):
            // put it back so the next retry takes the fast path.
            cacheFinished(row);
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

    private void finish(Long userId, Long keyId, IdempotencyStatus status, int responseStatus, String responseBody) {
        IdempotencyKey saved = requiresNew.execute(txStatus -> {
            IdempotencyKey row = repository.findById(keyId)
                    .orElseThrow(() -> new IllegalStateException("Idempotency key not found: " + keyId));
            row.setStatus(status);
            row.setResponseStatus(responseStatus);
            row.setResponseBody(responseBody);
            row.setLockedUntil(null);
            return repository.save(row);
        });
        // Only after Postgres has committed: Redis must never hold a response Postgres does not have.
        cacheFinished(userId, saved);
    }

    private void cacheFinished(IdempotencyKey row) {
        cacheFinished(row.getUserId(), row);
    }

    private void cacheFinished(Long userId, IdempotencyKey row) {
        Duration ttl = Duration.between(Instant.now(), row.getExpiresAt());
        cache.put(userId, row.getIdempotencyKey(), row.getRequestHash(), row.getStatus(),
                row.getResponseStatus(), row.getResponseBody(), ttl);
    }

    private IdempotencyKey findRequired(Long userId, String key) {
        return repository.findByUserIdAndIdempotencyKey(userId, key)
                .orElseThrow(() -> new IllegalStateException("Claimed key not found: " + key));
    }
}