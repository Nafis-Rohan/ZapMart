package com.nafis.ZapMart.idempotency;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the real SQL against a real Postgres (the docker-compose one, separate database
 * `zapmart_test`, so dev data is never touched). Not transactional on purpose: the concurrency
 * tests need real commits from several connections at once.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:5433/zapmart_test",
        "stripe.secret-key=test-not-used",
        "stripe.webhook-secret=test-not-used"
})
class IdempotencyKeyRepositoryIntegrationTest {

    private static final Long USER_ID = 1L;
    private static final String KEY = "abc-123";
    private static final int THREADS = 8;

    @Autowired
    private IdempotencyKeyRepository repository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        repository.deleteAll();
    }

    private int claim(String hash, Instant lockedUntil, Instant expiresAt) {
        Instant now = Instant.now();
        return tx.execute(status -> repository.claim(USER_ID, KEY, hash, lockedUntil, expiresAt, now));
    }

    private int reclaim(Long id, Instant newLockedUntil) {
        Instant now = Instant.now();
        return tx.execute(status -> repository.reclaimStaleLock(id, newLockedUntil, now));
    }

    /** Runs the task on THREADS threads released at the same instant; returns the sum of results. */
    private int runConcurrently(Supplier<Integer> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return task.get();
            }));
        }
        ready.await();
        go.countDown();
        int sum = 0;
        for (Future<Integer> future : futures) {
            sum += future.get();
        }
        pool.shutdown();
        return sum;
    }

    @Test
    void concurrentClaimsOfSameKeyHaveExactlyOneWinner() throws Exception {
        int winners = runConcurrently(() ->
                claim("hash-a", Instant.now().plusSeconds(60), Instant.now().plusSeconds(3600)));

        assertEquals(1, winners);
        assertEquals(1, repository.count());
    }

    @Test
    void liveRowIsNotOverwrittenByLaterClaim() {
        assertEquals(1, claim("hash-first", Instant.now().plusSeconds(60), Instant.now().plusSeconds(3600)));

        int second = claim("hash-second", Instant.now().plusSeconds(60), Instant.now().plusSeconds(3600));

        assertEquals(0, second);
        IdempotencyKey row = repository.findByUserIdAndIdempotencyKey(USER_ID, KEY).orElseThrow();
        assertEquals("hash-first", row.getRequestHash());
    }

    @Test
    void expiredRowIsRecycledByNextClaim() {
        Instant past = Instant.now().minusSeconds(3600);
        assertEquals(1, claim("hash-old", past, past));

        int recycled = claim("hash-new", Instant.now().plusSeconds(60), Instant.now().plusSeconds(3600));

        assertEquals(1, recycled);
        assertEquals(1, repository.count());
        IdempotencyKey row = repository.findByUserIdAndIdempotencyKey(USER_ID, KEY).orElseThrow();
        assertEquals("hash-new", row.getRequestHash());
        assertEquals(IdempotencyStatus.IN_PROGRESS, row.getStatus());
        assertNull(row.getResponseBody());
        assertTrue(row.getExpiresAt().isAfter(Instant.now()));
    }

    @Test
    void concurrentReclaimOfStaleLockHasExactlyOneWinner() throws Exception {
        Instant past = Instant.now().minusSeconds(60);
        assertEquals(1, claim("hash-a", past, Instant.now().plusSeconds(3600)));
        Long id = repository.findByUserIdAndIdempotencyKey(USER_ID, KEY).orElseThrow().getId();

        int winners = runConcurrently(() -> reclaim(id, Instant.now().plusSeconds(60)));

        assertEquals(1, winners);
    }

    @Test
    void freshLockCannotBeReclaimed() {
        assertEquals(1, claim("hash-a", Instant.now().plusSeconds(60), Instant.now().plusSeconds(3600)));
        Long id = repository.findByUserIdAndIdempotencyKey(USER_ID, KEY).orElseThrow().getId();

        assertEquals(0, reclaim(id, Instant.now().plusSeconds(60)));
    }
}