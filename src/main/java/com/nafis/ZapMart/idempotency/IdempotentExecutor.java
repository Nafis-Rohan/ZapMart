package com.nafis.ZapMart.idempotency;
//              HTTP Request
//                  ↓
//          IdempotentExecutor
//                  ↓
//          validate Idempotency-Key
//                  ↓
//          IdempotencyService.claim()
//                   ↓
//        ┌──────────┼───────────┐
//        ↓          ↓           ↓
//    NEW/PROCEED  REPLAY    IN_PROGRESS
//        ↓          ↓           ↓
//    run action   old result    409
//        ↓
//        ┌─────┴───────────────┐
//        ↓                     ↓
//    SUCCESS             known business error
//        ↓                     ↓
//    complete()             fail()
//        ↓                     ↓
//    save response       save error response
//        ↓                     ↓
//    return response     return response





import com.nafis.ZapMart.common.exception.BadRequestException;
import com.nafis.ZapMart.common.exception.ForbiddenException;
import com.nafis.ZapMart.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Wraps one controller action with the idempotency layer, so checkout and payment share the same
 * logic instead of copying it. The first run and any replay return the exact same status and JSON.
 */
@Component
@RequiredArgsConstructor
public class IdempotentExecutor {

    public static final String HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";
    private static final int MAX_KEY_LENGTH = 255;

    private final IdempotencyService idempotencyService;
    private final JsonMapper jsonMapper;
    private final IdempotencyProperties properties;

    public ResponseEntity<String> execute(Long userId, String key, String requestHash,
                                          HttpStatus successStatus, Supplier<Object> action) {
        if (!properties.isEnabled()) {
            return runWithoutIdempotency(successStatus, action);
        }

        validateKey(key);

        ClaimResult claim = idempotencyService.claim(userId, key, requestHash);
        return switch (claim.outcome()) {
            case PROCEED -> run(userId, claim.keyId(), successStatus, action);
            case REPLAY -> json(claim.responseStatus())
                    .header(REPLAYED_HEADER, "true")
                    .body(claim.responseBody());
            case IN_PROGRESS -> {
                String body = errorBody("A request with this Idempotency-Key is still being processed");
                yield ResponseEntity.status(HttpStatus.CONFLICT)
                        .header(HttpHeaders.RETRY_AFTER, String.valueOf(claim.retryAfterSeconds()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body);
            }
            case MISMATCH -> {
                String body = errorBody("This Idempotency-Key was already used with a different request");
                yield ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body);
            }
        };
    }

    private ResponseEntity<String> run(Long userId, Long keyId, HttpStatus successStatus, Supplier<Object> action) {
        try {
            String body = jsonMapper.writeValueAsString(action.get());
            idempotencyService.complete(userId, keyId, successStatus.value(), body);
            return json(successStatus.value()).body(body);
        } catch (BadRequestException | ResourceNotFoundException | ForbiddenException e) {
            // Deterministic business failure: store it so a retry gets the same answer.
            // Any other exception (crash, timeout) is NOT caught, so nothing is stored and the
            // lock goes stale, letting a later retry reclaim the key.
            int status = failureStatus(e);
            String body = errorBody(e.getMessage());
            idempotencyService.fail(userId, keyId, status, body);
            return json(status).body(body);
        }
    }

    // Load-test "before" stage only (idempotency.enabled=false): behave like the old unprotected endpoint,
    // no key needed, nothing claimed or stored, business errors answered exactly as before.
    private ResponseEntity<String> runWithoutIdempotency(HttpStatus successStatus, Supplier<Object> action) {
        try {
            return json(successStatus.value()).body(jsonMapper.writeValueAsString(action.get()));
        } catch (BadRequestException | ResourceNotFoundException | ForbiddenException e) {
            return json(failureStatus(e)).body(errorBody(e.getMessage()));
        }
    }

    // Mirrors GlobalExceptionHandler, so a stored failure looks exactly like a normal one.
    private int failureStatus(RuntimeException e) {
        if (e instanceof ResourceNotFoundException) {
            return HttpStatus.NOT_FOUND.value();
        }
        if (e instanceof ForbiddenException) {
            return HttpStatus.FORBIDDEN.value();
        }
        return HttpStatus.BAD_REQUEST.value();
    }

    private void validateKey(String key) {
        if (key == null || key.isBlank()) {
            throw new BadRequestException(HEADER + " header is required");
        }
        if (key.length() > MAX_KEY_LENGTH) {
            throw new BadRequestException(HEADER + " must be at most " + MAX_KEY_LENGTH + " characters");
        }
    }

    private String errorBody(String message) {
        return jsonMapper.writeValueAsString(Map.of("error", message));
    }

    private ResponseEntity.BodyBuilder json(int status) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON);
    }
}