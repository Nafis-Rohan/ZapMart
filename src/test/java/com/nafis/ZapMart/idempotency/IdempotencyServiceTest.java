package com.nafis.ZapMart.idempotency;

import com.nafis.ZapMart.idempotency.cache.CachedResponse;
import com.nafis.ZapMart.idempotency.cache.IdempotencyCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    private static final Long USER_ID = 1L;
    private static final String KEY = "abc-123";
    private static final String HASH = "hash-a";

    @Mock
    private IdempotencyKeyRepository repository;

    @Mock
    private IdempotencyCacheService cache;

    @Mock
    private PlatformTransactionManager transactionManager;

    private IdempotencyService service;

    @BeforeEach
    void setUp() {
        service = new IdempotencyService(repository, new IdempotencyProperties(), cache, transactionManager);
    }

    private IdempotencyKey row(Long id, String hash, IdempotencyStatus status, Instant lockedUntil) {
        IdempotencyKey row = new IdempotencyKey();
        row.setId(id);
        row.setUserId(USER_ID);
        row.setIdempotencyKey(KEY);
        row.setRequestHash(hash);
        row.setStatus(status);
        row.setLockedUntil(lockedUntil);
        row.setExpiresAt(Instant.now().plus(Duration.ofHours(1)));
        return row;
    }

    private void stubClaim(int result) {
        when(repository.claim(eq(USER_ID), eq(KEY), eq(HASH), any(Instant.class), any(Instant.class), any(Instant.class)))
                .thenReturn(result);
    }

    // ---------- Postgres path ----------

    @Test
    void newKeyIsClaimedAndProceeds() {
        stubClaim(1);
        when(repository.findByUserIdAndIdempotencyKey(USER_ID, KEY))
                .thenReturn(Optional.of(row(5L, HASH, IdempotencyStatus.IN_PROGRESS, Instant.now().plusSeconds(60))));

        ClaimResult result = service.claim(USER_ID, KEY, HASH);

        assertEquals(ClaimResult.Outcome.PROCEED, result.outcome());
        assertEquals(5L, result.keyId());
    }

    @Test
    void sameKeyWithDifferentHashIsMismatch() {
        stubClaim(0);
        when(repository.findByUserIdAndIdempotencyKey(USER_ID, KEY))
                .thenReturn(Optional.of(row(5L, "hash-other", IdempotencyStatus.COMPLETED, null)));

        ClaimResult result = service.claim(USER_ID, KEY, HASH);

        assertEquals(ClaimResult.Outcome.MISMATCH, result.outcome());
    }

    @Test
    void completedKeyReplaysSavedResponse() {
        stubClaim(0);
        IdempotencyKey completed = row(5L, HASH, IdempotencyStatus.COMPLETED, null);
        completed.setResponseStatus(201);
        completed.setResponseBody("{\"orderId\":42}");
        when(repository.findByUserIdAndIdempotencyKey(USER_ID, KEY)).thenReturn(Optional.of(completed));

        ClaimResult result = service.claim(USER_ID, KEY, HASH);

        assertEquals(ClaimResult.Outcome.REPLAY, result.outcome());
        assertEquals(201, result.responseStatus());
        assertEquals("{\"orderId\":42}", result.responseBody());
    }

    @Test
    void failedKeyReplaysSavedFailure() {
        stubClaim(0);
        IdempotencyKey failed = row(5L, HASH, IdempotencyStatus.FAILED, null);
        failed.setResponseStatus(400);
        failed.setResponseBody("{\"error\":\"Cart is empty\"}");
        when(repository.findByUserIdAndIdempotencyKey(USER_ID, KEY)).thenReturn(Optional.of(failed));

        ClaimResult result = service.claim(USER_ID, KEY, HASH);

        assertEquals(ClaimResult.Outcome.REPLAY, result.outcome());
        assertEquals(400, result.responseStatus());
    }

    @Test
    void runningRequestWithFreshLockIsInProgress() {
        stubClaim(0);
        when(repository.findByUserIdAndIdempotencyKey(USER_ID, KEY))
                .thenReturn(Optional.of(row(5L, HASH, IdempotencyStatus.IN_PROGRESS, Instant.now().plusSeconds(30))));

        ClaimResult result = service.claim(USER_ID, KEY, HASH);

        assertEquals(ClaimResult.Outcome.IN_PROGRESS, result.outcome());
        assertTrue(result.retryAfterSeconds() >= 1);
        verify(repository, never()).reclaimStaleLock(anyLong(), any(Instant.class), any(Instant.class));
    }

    @Test
    void staleLockIsReclaimedAndProceeds() {
        stubClaim(0);
        when(repository.findByUserIdAndIdempotencyKey(USER_ID, KEY))
                .thenReturn(Optional.of(row(5L, HASH, IdempotencyStatus.IN_PROGRESS, Instant.now().minusSeconds(10))));
        when(repository.reclaimStaleLock(eq(5L), any(Instant.class), any(Instant.class))).thenReturn(1);

        ClaimResult result = service.claim(USER_ID, KEY, HASH);

        assertEquals(ClaimResult.Outcome.PROCEED, result.outcome());
        assertEquals(5L, result.keyId());
    }

    @Test
    void staleLockLostToAnotherRequestIsInProgress() {
        stubClaim(0);
        when(repository.findByUserIdAndIdempotencyKey(USER_ID, KEY))
                .thenReturn(Optional.of(row(5L, HASH, IdempotencyStatus.IN_PROGRESS, Instant.now().minusSeconds(10))));
        when(repository.reclaimStaleLock(eq(5L), any(Instant.class), any(Instant.class))).thenReturn(0);

        ClaimResult result = service.claim(USER_ID, KEY, HASH);

        assertEquals(ClaimResult.Outcome.IN_PROGRESS, result.outcome());
    }

    @Test
    void rowDeletedByCleanupBetweenInsertAndReadIsRetriedOnce() {
        when(repository.claim(eq(USER_ID), eq(KEY), eq(HASH), any(Instant.class), any(Instant.class), any(Instant.class)))
                .thenReturn(0, 1);
        when(repository.findByUserIdAndIdempotencyKey(USER_ID, KEY))
                .thenReturn(Optional.empty(),
                        Optional.of(row(7L, HASH, IdempotencyStatus.IN_PROGRESS, Instant.now().plusSeconds(60))));

        ClaimResult result = service.claim(USER_ID, KEY, HASH);

        assertEquals(ClaimResult.Outcome.PROCEED, result.outcome());
        assertEquals(7L, result.keyId());
        verify(repository, times(2)).claim(eq(USER_ID), eq(KEY), eq(HASH),
                any(Instant.class), any(Instant.class), any(Instant.class));
    }

    // ---------- complete / fail ----------

    @Test
    void completeStoresResponseAndClearsLock() {
        IdempotencyKey running = row(5L, HASH, IdempotencyStatus.IN_PROGRESS, Instant.now().plusSeconds(60));
        when(repository.findById(5L)).thenReturn(Optional.of(running));
        when(repository.save(any(IdempotencyKey.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.complete(USER_ID, 5L, 201, "{\"orderId\":42}");

        ArgumentCaptor<IdempotencyKey> saved = ArgumentCaptor.forClass(IdempotencyKey.class);
        verify(repository).save(saved.capture());
        assertEquals(IdempotencyStatus.COMPLETED, saved.getValue().getStatus());
        assertEquals(201, saved.getValue().getResponseStatus());
        assertEquals("{\"orderId\":42}", saved.getValue().getResponseBody());
        assertNull(saved.getValue().getLockedUntil());
    }

    @Test
    void failStoresFailureResponseAndClearsLock() {
        IdempotencyKey running = row(5L, HASH, IdempotencyStatus.IN_PROGRESS, Instant.now().plusSeconds(60));
        when(repository.findById(5L)).thenReturn(Optional.of(running));
        when(repository.save(any(IdempotencyKey.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.fail(USER_ID, 5L, 400, "{\"error\":\"Cart is empty\"}");

        ArgumentCaptor<IdempotencyKey> saved = ArgumentCaptor.forClass(IdempotencyKey.class);
        verify(repository).save(saved.capture());
        assertEquals(IdempotencyStatus.FAILED, saved.getValue().getStatus());
        assertEquals(400, saved.getValue().getResponseStatus());
        assertNull(saved.getValue().getLockedUntil());
    }

    // ---------- Redis cache wiring ----------

    @Test
    void cacheHitWithSameHashReplaysWithoutTouchingPostgres() {
        when(cache.get(USER_ID, KEY))
                .thenReturn(Optional.of(new CachedResponse(HASH, IdempotencyStatus.COMPLETED, 201, "{\"orderId\":42}")));

        ClaimResult result = service.claim(USER_ID, KEY, HASH);

        assertEquals(ClaimResult.Outcome.REPLAY, result.outcome());
        assertEquals(201, result.responseStatus());
        assertEquals("{\"orderId\":42}", result.responseBody());
        verifyNoInteractions(repository);
    }

    @Test
    void cacheHitWithDifferentHashIsMismatchWithoutTouchingPostgres() {
        when(cache.get(USER_ID, KEY))
                .thenReturn(Optional.of(new CachedResponse("hash-other", IdempotencyStatus.COMPLETED, 201, "{}")));

        ClaimResult result = service.claim(USER_ID, KEY, HASH);

        assertEquals(ClaimResult.Outcome.MISMATCH, result.outcome());
        verifyNoInteractions(repository);
    }

    @Test
    void completeWritesFinishedResponseToCacheAfterPostgres() {
        IdempotencyKey running = row(5L, HASH, IdempotencyStatus.IN_PROGRESS, Instant.now().plusSeconds(60));
        when(repository.findById(5L)).thenReturn(Optional.of(running));
        when(repository.save(any(IdempotencyKey.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.complete(USER_ID, 5L, 201, "{\"orderId\":42}");

        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(cache).put(eq(USER_ID), eq(KEY), eq(HASH), eq(IdempotencyStatus.COMPLETED),
                eq(201), eq("{\"orderId\":42}"), ttl.capture());
        assertTrue(ttl.getValue().toMinutes() > 0);
    }

    @Test
    void failWritesFailureToCache() {
        IdempotencyKey running = row(5L, HASH, IdempotencyStatus.IN_PROGRESS, Instant.now().plusSeconds(60));
        when(repository.findById(5L)).thenReturn(Optional.of(running));
        when(repository.save(any(IdempotencyKey.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.fail(USER_ID, 5L, 400, "{\"error\":\"Cart is empty\"}");

        verify(cache).put(eq(USER_ID), eq(KEY), eq(HASH), eq(IdempotencyStatus.FAILED),
                eq(400), eq("{\"error\":\"Cart is empty\"}"), any(Duration.class));
    }

    @Test
    void finishedRowFoundInPostgresIsPutBackIntoCache() {
        stubClaim(0);
        IdempotencyKey completed = row(5L, HASH, IdempotencyStatus.COMPLETED, null);
        completed.setResponseStatus(201);
        completed.setResponseBody("{\"orderId\":42}");
        when(repository.findByUserIdAndIdempotencyKey(USER_ID, KEY)).thenReturn(Optional.of(completed));

        service.claim(USER_ID, KEY, HASH);

        verify(cache).put(eq(USER_ID), eq(KEY), eq(HASH), eq(IdempotencyStatus.COMPLETED),
                eq(201), eq("{\"orderId\":42}"), any(Duration.class));
    }

    @Test
    void inProgressRowIsNeverWrittenToCache() {
        stubClaim(0);
        when(repository.findByUserIdAndIdempotencyKey(USER_ID, KEY))
                .thenReturn(Optional.of(row(5L, HASH, IdempotencyStatus.IN_PROGRESS, Instant.now().plusSeconds(30))));

        service.claim(USER_ID, KEY, HASH);

        verify(cache, never()).put(anyLong(), anyString(), anyString(), any(IdempotencyStatus.class),
                anyInt(), any(), any(Duration.class));
    }
}