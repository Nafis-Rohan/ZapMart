package com.nafis.ZapMart.order;

import com.nafis.ZapMart.order.dto.OrderResponse;
import com.nafis.ZapMart.order.dto.OrderSummaryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
@RequiredArgsConstructor
public class OrderController {

    private final CheckoutService checkoutService;
    private final OrderService orderService;

    @PostMapping("/checkout")
    @ResponseStatus(HttpStatus.CREATED)
    public OrderResponse checkout(@RequestHeader("X-User-Id") Long userId) {
        return checkoutService.checkout(userId);
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
