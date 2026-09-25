package com.nafis.ZapMart.idempotency;

import com.nafis.ZapMart.common.exception.BadRequestException;
import com.nafis.ZapMart.common.exception.ForbiddenException;
import com.nafis.ZapMart.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdempotentExecutorTest {

    private static final Long USER_ID = 1L;
    private static final String KEY = "abc-123";
    private static final String HASH = "hash-a";

    @Mock
    private IdempotencyService idempotencyService;

    private IdempotencyProperties properties;

    private IdempotentExecutor executor;

    @BeforeEach
    void setUp() {
        properties = new IdempotencyProperties();
        executor = new IdempotentExecutor(idempotencyService, JsonMapper.builder().build(), properties);
    }

    // ---------- new key ----------

    @Test
    void newKeyRunsActionStoresResultAndReturnsIt() {
        when(idempotencyService.claim(USER_ID, KEY, HASH)).thenReturn(ClaimResult.proceed(5L));

        ResponseEntity<String> response = executor.execute(USER_ID, KEY, HASH, HttpStatus.CREATED,
                () -> Map.of("orderId", 42));

        assertEquals(201, response.getStatusCode().value());
        assertEquals("{\"orderId\":42}", response.getBody());
        assertEquals(MediaType.APPLICATION_JSON, response.getHeaders().getContentType());
        assertNull(response.getHeaders().getFirst(IdempotentExecutor.REPLAYED_HEADER));
        verify(idempotencyService).complete(USER_ID, 5L, 201, "{\"orderId\":42}");
    }

    // ---------- replay / conflict / mismatch ----------

    @Test
    void replayReturnsSavedResponseWithoutRunningAction() {
        when(idempotencyService.claim(USER_ID, KEY, HASH))
                .thenReturn(ClaimResult.replay(201, "{\"orderId\":42}"));
        AtomicInteger calls = new AtomicInteger();

        ResponseEntity<String> response = executor.execute(USER_ID, KEY, HASH, HttpStatus.CREATED, () -> {
            calls.incrementAndGet();
            return Map.of("orderId", 99);
        });

        assertEquals(0, calls.get());
        assertEquals(201, response.getStatusCode().value());
        assertEquals("{\"orderId\":42}", response.getBody());
        assertEquals("true", response.getHeaders().getFirst(IdempotentExecutor.REPLAYED_HEADER));
        verify(idempotencyService, never()).complete(anyLong(), anyLong(), anyInt(), anyString());
    }

    @Test
    void replayOfSavedFailureKeepsItsStatus() {
        when(idempotencyService.claim(USER_ID, KEY, HASH))
                .thenReturn(ClaimResult.replay(400, "{\"error\":\"Cart is empty\"}"));

        ResponseEntity<String> response = executor.execute(USER_ID, KEY, HASH, HttpStatus.CREATED,
                () -> Map.of("orderId", 99));

        assertEquals(400, response.getStatusCode().value());
        assertEquals("{\"error\":\"Cart is empty\"}", response.getBody());
    }

    @Test
    void requestStillRunningReturns409WithRetryAfter() {
        when(idempotencyService.claim(USER_ID, KEY, HASH)).thenReturn(ClaimResult.inProgress(5));
        AtomicInteger calls = new AtomicInteger();

        ResponseEntity<String> response = executor.execute(USER_ID, KEY, HASH, HttpStatus.CREATED, () -> {
            calls.incrementAndGet();
            return Map.of();
        });

        assertEquals(0, calls.get());
        assertEquals(409, response.getStatusCode().value());
        assertEquals("5", response.getHeaders().getFirst("Retry-After"));
        assertTrue(response.getBody().contains("still being processed"));
    }

    @Test
    void sameKeyDifferentRequestReturns422() {
        when(idempotencyService.claim(USER_ID, KEY, HASH)).thenReturn(ClaimResult.mismatch());
        AtomicInteger calls = new AtomicInteger();

        ResponseEntity<String> response = executor.execute(USER_ID, KEY, HASH, HttpStatus.CREATED, () -> {
            calls.incrementAndGet();
            return Map.of();
        });

        assertEquals(0, calls.get());
        assertEquals(422, response.getStatusCode().value());
        assertTrue(response.getBody().contains("different request"));
    }

    // ---------- key validation ----------

    @Test
    void missingKeyIsRejectedWithoutTouchingTheNotebook() {
        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> executor.execute(USER_ID, null, HASH, HttpStatus.CREATED, () -> Map.of()));

        assertTrue(ex.getMessage().contains("required"));
        verifyNoInteractions(idempotencyService);
    }

    @Test
    void blankKeyIsRejected() {
        assertThrows(BadRequestException.class,
                () -> executor.execute(USER_ID, "   ", HASH, HttpStatus.CREATED, () -> Map.of()));

        verifyNoInteractions(idempotencyService);
    }

    @Test
    void tooLongKeyIsRejected() {
        String tooLong = "k".repeat(256);

        assertThrows(BadRequestException.class,
                () -> executor.execute(USER_ID, tooLong, HASH, HttpStatus.CREATED, () -> Map.of()));

        verifyNoInteractions(idempotencyService);
    }

    // ---------- layer switched off (load-test "before" stage) ----------

    @Test
    void whenDisabledActionRunsWithoutAnyKeyAndNothingIsClaimedOrStored() {
        properties.setEnabled(false);

        ResponseEntity<String> response = executor.execute(USER_ID, null, HASH, HttpStatus.CREATED,
                () -> Map.of("orderId", 42));

        assertEquals(201, response.getStatusCode().value());
        assertEquals("{\"orderId\":42}", response.getBody());
        verifyNoInteractions(idempotencyService);
    }

    @Test
    void whenDisabledBusinessErrorsAreAnsweredAsBeforeButNotStored() {
        properties.setEnabled(false);

        ResponseEntity<String> response = executor.execute(USER_ID, KEY, HASH, HttpStatus.CREATED, () -> {
            throw new BadRequestException("Cart is empty");
        });

        assertEquals(400, response.getStatusCode().value());
        assertEquals("{\"error\":\"Cart is empty\"}", response.getBody());
        verifyNoInteractions(idempotencyService);
    }

    @Test
    void whenDisabledUnexpectedErrorsStillPropagate() {
        properties.setEnabled(false);

        assertThrows(IllegalStateException.class, () ->
                executor.execute(USER_ID, KEY, HASH, HttpStatus.CREATED, () -> {
                    throw new IllegalStateException("boom");
                }));
    }

    // ---------- failures ----------

    @Test
    void badRequestIsStoredAsFailureAndReturnedAs400() {
        when(idempotencyService.claim(USER_ID, KEY, HASH)).thenReturn(ClaimResult.proceed(5L));

        ResponseEntity<String> response = executor.execute(USER_ID, KEY, HASH, HttpStatus.CREATED, () -> {
            throw new BadRequestException("Cart is empty");
        });

        assertEquals(400, response.getStatusCode().value());
        assertEquals("{\"error\":\"Cart is empty\"}", response.getBody());
        verify(idempotencyService).fail(USER_ID, 5L, 400, "{\"error\":\"Cart is empty\"}");
        verify(idempotencyService, never()).complete(anyLong(), anyLong(), anyInt(), anyString());
    }

    @Test
    void notFoundIsStoredAsFailureAndReturnedAs404() {
        when(idempotencyService.claim(USER_ID, KEY, HASH)).thenReturn(ClaimResult.proceed(5L));

        ResponseEntity<String> response = executor.execute(USER_ID, KEY, HASH, HttpStatus.CREATED, () -> {
            throw new ResourceNotFoundException("Order not found");
        });

        assertEquals(404, response.getStatusCode().value());
        verify(idempotencyService).fail(USER_ID, 5L, 404, "{\"error\":\"Order not found\"}");
    }

    @Test
    void forbiddenIsStoredAsFailureAndReturnedAs403() {
        when(idempotencyService.claim(USER_ID, KEY, HASH)).thenReturn(ClaimResult.proceed(5L));

        ResponseEntity<String> response = executor.execute(USER_ID, KEY, HASH, HttpStatus.CREATED, () -> {
            throw new ForbiddenException("Not allowed");
        });

        assertEquals(403, response.getStatusCode().value());
        verify(idempotencyService).fail(USER_ID, 5L, 403, "{\"error\":\"Not allowed\"}");
    }

    @Test
    void unexpectedErrorStoresNothingSoTheLockCanGoStale() {
        when(idempotencyService.claim(USER_ID, KEY, HASH)).thenReturn(ClaimResult.proceed(5L));

        assertThrows(IllegalStateException.class, () ->
                executor.execute(USER_ID, KEY, HASH, HttpStatus.CREATED, () -> {
                    throw new IllegalStateException("boom");
                }));

        verify(idempotencyService, never()).complete(anyLong(), anyLong(), anyInt(), anyString());
        verify(idempotencyService, never()).fail(anyLong(), anyLong(), anyInt(), any());
    }
}