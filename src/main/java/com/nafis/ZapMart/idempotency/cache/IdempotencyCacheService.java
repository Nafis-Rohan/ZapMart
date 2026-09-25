package com.nafis.ZapMart.idempotency.cache;

import com.nafis.ZapMart.idempotency.IdempotencyProperties;
import com.nafis.ZapMart.idempotency.IdempotencyStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Redis fast-path cache for FINISHED idempotency responses only. Postgres stays the source of
 * truth, so every method here swallows Redis errors: a Redis outage makes things slower, never wrong.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotencyCacheService {

    private static final String KEY_PREFIX = "idem:";
    private static final String F_HASH = "requestHash";
    private static final String F_STATUS = "status";
    private static final String F_RESPONSE_STATUS = "responseStatus";
    private static final String F_BODY = "body";

    private final StringRedisTemplate redis;
    private final IdempotencyProperties properties;

    public Optional<CachedResponse> get(Long userId, String key) {
        if (!properties.isCacheEnabled()) {
            return Optional.empty();
        }
        String redisKey = redisKey(userId, key);
        try {
            Map<Object, Object> entries = redis.opsForHash().entries(redisKey);
            if (entries.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new CachedResponse(
                    (String) entries.get(F_HASH),
                    IdempotencyStatus.valueOf((String) entries.get(F_STATUS)),
                    Integer.parseInt((String) entries.get(F_RESPONSE_STATUS)),
                    (String) entries.get(F_BODY)));
        } catch (RuntimeException e) {
            log.warn("Idempotency cache read failed for {}, falling back to Postgres: {}", redisKey, e.toString());
            evict(userId, key);
            return Optional.empty();
        }
    }

    /** Stores a finished response. ttl should match the Postgres row's remaining lifetime. */
    public void put(Long userId, String key, String requestHash, IdempotencyStatus status,
                    int responseStatus, String responseBody, Duration ttl) {
        if (!properties.isCacheEnabled() || ttl == null || ttl.isZero() || ttl.isNegative()) {
            return;
        }
        String redisKey = redisKey(userId, key);
        Map<String, String> fields = new HashMap<>();
        fields.put(F_HASH, requestHash);
        fields.put(F_STATUS, status.name());
        fields.put(F_RESPONSE_STATUS, String.valueOf(responseStatus));
        fields.put(F_BODY, responseBody == null ? "" : responseBody);
        try {
            redis.opsForHash().putAll(redisKey, fields);
            redis.expire(redisKey, ttl);
        } catch (RuntimeException e) {
            log.warn("Idempotency cache write failed for {}: {}", redisKey, e.toString());
            evict(userId, key);
        }
    }

    public void evict(Long userId, String key) {
        try {
            redis.delete(redisKey(userId, key));
        } catch (RuntimeException e) {
            log.warn("Idempotency cache evict failed for {}: {}", redisKey(userId, key), e.toString());
        }
    }

    private String redisKey(Long userId, String key) {
        return KEY_PREFIX + userId + ":" + key;
    }
}