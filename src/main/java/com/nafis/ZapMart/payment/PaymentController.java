package com.nafis.ZapMart.payment;

import com.nafis.ZapMart.payment.dto.PaymentRequest;
import com.nafis.ZapMart.payment.dto.PaymentResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders/{orderId}/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentResponse pay(@RequestHeader("X-User-Id") Long userId,
                                @PathVariable Long orderId,
                                @Valid @RequestBody PaymentRequest request) {
        return paymentService.pay(userId, orderId, request);
    }
}