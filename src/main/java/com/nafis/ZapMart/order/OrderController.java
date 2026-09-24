package com.nafis.ZapMart.order;

import com.nafis.ZapMart.idempotency.IdempotentExecutor;
import com.nafis.ZapMart.idempotency.RequestHashUtil;
import com.nafis.ZapMart.order.dto.OrderResponse;
import com.nafis.ZapMart.order.dto.OrderSummaryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final CheckoutService checkoutService;
    private final OrderService orderService;
    private final IdempotentExecutor idempotentExecutor;

    @PostMapping("/checkout")
    public ResponseEntity<String> checkout(
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader(value = IdempotentExecutor.HEADER, required = false) String idempotencyKey) {
        String requestHash = RequestHashUtil.hash("POST", "/api/orders/checkout");
        return idempotentExecutor.execute(userId, idempotencyKey, requestHash, HttpStatus.CREATED,
                () -> checkoutService.checkout(userId));
    }

    @GetMapping
    public List<OrderSummaryResponse> list(@RequestHeader("X-User-Id") Long userId) {
        return orderService.list(userId);
    }

    @GetMapping("/{id}")
    public OrderResponse get(@RequestHeader("X-User-Id") Long userId, @PathVariable Long id) {
        return orderService.get(userId, id);
    }
}