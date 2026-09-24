package com.nafis.ZapMart.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, Long> {

    Optional<IdempotencyKey> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);

    /**
     * Atomic claim. Returns 1 if this request now owns the key, 0 if a live row already exists.
     * A row that is already expired is overwritten in the same statement, so an old row that the
     * cleanup job has not deleted yet never blocks a new request.
     */
    @Modifying
    @Query(value = """
            INSERT INTO idempotency_keys
                (user_id, idempotency_key, request_hash, status, locked_until, expires_at, created_at, updated_at)
            VALUES
                (:userId, :key, :requestHash, 'IN_PROGRESS', :lockedUntil, :expiresAt, :now, :now)
            ON CONFLICT (user_id, idempotency_key) DO UPDATE SET
                request_hash    = EXCLUDED.request_hash,
                status          = 'IN_PROGRESS',
                locked_until    = EXCLUDED.locked_until,
                response_status = NULL,
                response_body   = NULL,
                expires_at      = EXCLUDED.expires_at,
                created_at      = EXCLUDED.created_at,
                updated_at      = EXCLUDED.updated_at
            WHERE idempotency_keys.expires_at < :now
            """, nativeQuery = true)
    int claim(@Param("userId") Long userId,
              @Param("key") String key,
              @Param("requestHash") String requestHash,
              @Param("lockedUntil") Instant lockedUntil,
              @Param("expiresAt") Instant expiresAt,
              @Param("now") Instant now);

    /**
     * Atomic reclaim of a stale lock. Returns 1 only for the single request that wins;
     * a concurrent reclaimer sees 0 because the new lock is no longer stale.
     */
    @Modifying
    @Query(value = """
            UPDATE idempotency_keys
               SET locked_until = :newLockedUntil,
                   updated_at   = :now
             WHERE id = :id
               AND status = 'IN_PROGRESS'
               AND locked_until < :now
            """, nativeQuery = true)
    int reclaimStaleLock(@Param("id") Long id,
                         @Param("newLockedUntil") Instant newLockedUntil,
                         @Param("now") Instant now);
}
