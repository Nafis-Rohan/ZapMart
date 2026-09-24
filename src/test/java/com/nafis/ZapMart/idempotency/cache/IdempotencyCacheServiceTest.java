package com.nafis.ZapMart.idempotency.cache;

import com.nafis.ZapMart.idempotency.IdempotencyStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdempotencyCacheServiceTest {

    private static final Long USER_ID = 1L;
    private static final String KEY = "abc-123";
    private static final String REDIS_KEY = "idem:1:abc-123";

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private HashOperations<String, Object, Object> hashOps;

    private IdempotencyCacheService service;

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForHash()).thenReturn(hashOps);
        service = new IdempotencyCacheService(redis);
    }

    private Map<Object, Object> entry(String status) {
        Map<Object, Object> entries = new HashMap<>();
        entries.put("requestHash", "hash-a");
        entries.put("status", status);
        entries.put("responseStatus", "201");
        entries.put("body", "{\"orderId\":42}");
        return entries;
    }

    @Test
    void missReturnsEmpty() {
        when(hashOps.entries(REDIS_KEY)).thenReturn(new HashMap<>());

        Optional<CachedResponse> result = service.get(USER_ID, KEY);

        assertTrue(result.isEmpty());
    }

    @Test
    void hitReturnsCachedResponse() {
        when(hashOps.entries(REDIS_KEY)).thenReturn(entry("COMPLETED"));

        Optional<CachedResponse> result = service.get(USER_ID, KEY);

        assertTrue(result.isPresent());
        assertEquals("hash-a", result.get().requestHash());
        assertEquals(IdempotencyStatus.COMPLETED, result.get().status());
        assertEquals(201, result.get().responseStatus());
        assertEquals("{\"orderId\":42}", result.get().responseBody());
    }

    @Test
    void putWritesAllFieldsAndSetsTtl() {
        Duration ttl = Duration.ofHours(23);

        service.put(USER_ID, KEY, "hash-a", IdempotencyStatus.COMPLETED, 201, "{\"orderId\":42}", ttl);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> fields = ArgumentCaptor.forClass(Map.class);
        verify(hashOps).putAll(org.mockito.ArgumentMatchers.eq(REDIS_KEY), fields.capture());
        assertEquals("hash-a", fields.getValue().get("requestHash"));
        assertEquals("COMPLETED", fields.getValue().get("status"));
        assertEquals("201", fields.getValue().get("responseStatus"));
        assertEquals("{\"orderId\":42}", fields.getValue().get("body"));
        verify(redis).expire(REDIS_KEY, ttl);
    }

    @Test
    void putStoresEmptyStringForNullBody() {
        service.put(USER_ID, KEY, "hash-a", IdempotencyStatus.FAILED, 400, null, Duration.ofHours(1));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> fields = ArgumentCaptor.forClass(Map.class);
        verify(hashOps).putAll(org.mockito.ArgumentMatchers.eq(REDIS_KEY), fields.capture());
        assertEquals("", fields.getValue().get("body"));
    }

    @Test
    void putSkipsWriteWhenTtlIsZeroOrNegative() {
        service.put(USER_ID, KEY, "hash-a", IdempotencyStatus.COMPLETED, 201, "{}", Duration.ZERO);
        service.put(USER_ID, KEY, "hash-a", IdempotencyStatus.COMPLETED, 201, "{}", Duration.ofSeconds(-5));

        verify(hashOps, never()).putAll(anyString(), anyMap());
        verify(redis, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    void redisErrorOnGetFallsBackToEmptyAndEvicts() {
        when(hashOps.entries(REDIS_KEY)).thenThrow(new RuntimeException("redis down"));

        Optional<CachedResponse> result = service.get(USER_ID, KEY);

        assertTrue(result.isEmpty());
        verify(redis).delete(REDIS_KEY);
    }

    @Test
    void corruptCachedStatusIsTreatedAsMissAndEvicted() {
        when(hashOps.entries(REDIS_KEY)).thenReturn(entry("NOT_A_STATUS"));

        Optional<CachedResponse> result = service.get(USER_ID, KEY);

        assertTrue(result.isEmpty());
        verify(redis).delete(REDIS_KEY);
    }

    @Test
    void redisErrorOnPutDoesNotThrowAndEvictsPartialKey() {
        doThrow(new RuntimeException("redis down")).when(hashOps).putAll(anyString(), anyMap());

        assertDoesNotThrow(() ->
                service.put(USER_ID, KEY, "hash-a", IdempotencyStatus.COMPLETED, 201, "{}", Duration.ofHours(1)));

        verify(redis).delete(REDIS_KEY);
    }

    @Test
    void redisErrorOnEvictDoesNotThrow() {
        when(redis.delete(REDIS_KEY)).thenThrow(new RuntimeException("redis down"));

        assertDoesNotThrow(() -> service.evict(USER_ID, KEY));
    }
}