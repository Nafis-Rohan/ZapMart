package com.nafis.ZapMart.idempotency;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdempotencyCleanupJobTest {

    private static final int BATCH = 10;

    @Mock
    private IdempotencyKeyRepository repository;

    private IdempotencyCleanupJob job;

    @BeforeEach
    void setUp() {
        IdempotencyProperties properties = new IdempotencyProperties();
        properties.setCleanupBatchSize(BATCH);
        job = new IdempotencyCleanupJob(repository, properties);
    }

    @Test
    void nothingExpiredDeletesNothingAndStopsAfterOneCall() {
        when(repository.deleteExpiredBatch(any(Instant.class), eq(BATCH))).thenReturn(0);

        int total = job.sweep(Instant.now());

        assertEquals(0, total);
        verify(repository, times(1)).deleteExpiredBatch(any(Instant.class), eq(BATCH));
    }

    @Test
    void shortBatchStopsTheLoop() {
        when(repository.deleteExpiredBatch(any(Instant.class), eq(BATCH))).thenReturn(4);

        int total = job.sweep(Instant.now());

        assertEquals(4, total);
        verify(repository, times(1)).deleteExpiredBatch(any(Instant.class), eq(BATCH));
    }

    @Test
    void fullBatchesKeepGoingUntilAShortOneAndAddUp() {
        when(repository.deleteExpiredBatch(any(Instant.class), eq(BATCH))).thenReturn(10, 10, 3);

        int total = job.sweep(Instant.now());

        assertEquals(23, total);
        verify(repository, times(3)).deleteExpiredBatch(any(Instant.class), eq(BATCH));
    }

    @Test
    void exactlyOneFullBatchThenEmptyStopsOnTheEmptyOne() {
        when(repository.deleteExpiredBatch(any(Instant.class), eq(BATCH))).thenReturn(10, 0);

        int total = job.sweep(Instant.now());

        assertEquals(10, total);
        verify(repository, times(2)).deleteExpiredBatch(any(Instant.class), eq(BATCH));
    }

    @Test
    void databaseErrorInScheduledRunIsSwallowedSoTheScheduleKeepsGoing() {
        when(repository.deleteExpiredBatch(any(Instant.class), anyInt()))
                .thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> job.run());
    }
}