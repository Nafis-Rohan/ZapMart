package com.nafis.ZapMart.payment;

import com.nafis.ZapMart.idempotency.IdempotentExecutor;
import com.nafis.ZapMart.idempotency.RequestHashUtil;
import com.nafis.ZapMart.payment.dto.PaymentRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders/{orderId}/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;
    private final IdempotentExecutor idempotentExecutor;

    @PostMapping
    public ResponseEntity<String> pay(
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader(value = IdempotentExecutor.HEADER, required = false) String idempotencyKey,
            @PathVariable Long orderId,
            @Valid @RequestBody PaymentRequest request) {
        // The order id (in the path) and the payment method define what this request asks for.
        String requestHash = RequestHashUtil.hash(
                "POST", "/api/orders/" + orderId + "/payments", request.paymentMethodId());
        return idempotentExecutor.execute(userId, idempotencyKey, requestHash, HttpStatus.CREATED,
                () -> paymentService.pay(userId, orderId, request, stripeKey(userId, idempotencyKey)));
    }

    // Scoped by user so two users sending the same client key can never collide at Stripe, and
    // prefixed so it can never equal a key used for another kind of action.
    private String stripeKey(Long userId, String idempotencyKey) {
        return "pay-" + userId + "-" + idempotencyKey;
    }
}