package com.nafis.ZapMart.idempotency;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

    private static final Long USER_ID = 1L;
    private static final String KEY = "abc-123";
    private static final String HASH = "hash-a";

    @Mock
    private IdempotencyKeyRepository repository;

    private IdempotencyService service;

    @BeforeEach
    void setUp() {
        service = new IdempotencyService(repository, new IdempotencyProperties());
    }

    private IdempotencyKey row(Long id, String hash, IdempotencyStatus status, Instant lockedUntil) {
        IdempotencyKey row = new IdempotencyKey();
        row.setId(id);
        row.setUserId(USER_ID);
        row.setIdempotencyKey(KEY);
        row.setRequestHash(hash);
        row.setStatus(status);
        row.setLockedUntil(lockedUntil);
        return row;
    }

    private void stubClaim(int result) {
        when(repository.claim(eq(USER_ID), eq(KEY), eq(HASH), any(Instant.class), any(Instant.class), any(Instant.class)))
                .thenReturn(result);
    }

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

    @Test
    void completeStoresResponseAndClearsLock() {
        IdempotencyKey running = row(5L, HASH, IdempotencyStatus.IN_PROGRESS, Instant.now().plusSeconds(60));
        when(repository.findById(5L)).thenReturn(Optional.of(running));

        service.complete(5L, 201, "{\"orderId\":42}");

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

        service.fail(5L, 400, "{\"error\":\"Cart is empty\"}");

        ArgumentCaptor<IdempotencyKey> saved = ArgumentCaptor.forClass(IdempotencyKey.class);
        verify(repository).save(saved.capture());
        assertEquals(IdempotencyStatus.FAILED, saved.getValue().getStatus());
        assertEquals(400, saved.getValue().getResponseStatus());
        assertNull(saved.getValue().getLockedUntil());
    }
}
