package com.nafis.ZapMart.idempotency;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Housekeeping only: deletes expired idempotency rows so the table stays small. Correctness never
 * depends on it, because IdempotencyKeyRepository.claim() already recycles an expired row itself.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotencyCleanupJob {

    private final IdempotencyKeyRepository repository;
    private final IdempotencyProperties properties;

    // The first run waits (default 60s) so it never competes with startup or tests.
    @Scheduled(initialDelayString = "${idempotency.cleanup-initial-delay-ms:60000}",
            fixedDelayString = "${idempotency.cleanup-interval-ms:3600000}")
    public void run() {
        try {
            int removed = sweep(Instant.now());
            if (removed > 0) {
                log.info("Idempotency cleanup removed {} expired keys", removed);
            }
        } catch (RuntimeException e) {
            log.warn("Idempotency cleanup failed, will try again on the next run: {}", e.toString());
        }
    }

    /** Deletes expired rows in batches until a batch comes back short. Returns the total deleted. */
    int sweep(Instant now) {
        int batchSize = properties.getCleanupBatchSize();
        int total = 0;
        int deleted;
        do {
            deleted = repository.deleteExpiredBatch(now, batchSize);
            total += deleted;
        } while (deleted == batchSize);
        return total;
    }
}
