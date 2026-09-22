package com.nafis.ZapMart.order;

import com.nafis.ZapMart.common.exception.ResourceNotFoundException;
import com.nafis.ZapMart.order.dto.OrderItemResponse;
import com.nafis.ZapMart.order.dto.OrderResponse;
import com.nafis.ZapMart.order.dto.OrderSummaryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;

    @Transactional(readOnly = true)
    public List<OrderSummaryResponse> list(Long userId) {
        return orderRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(o -> new OrderSummaryResponse(o.getId(), o.getStatus(), o.getTotalPrice(), o.getCreatedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public OrderResponse get(Long userId, Long orderId) {
        Order order = orderRepository.findByIdAndUserIdWithItems(orderId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));

        List<OrderItemResponse> items = order.getItems().stream()
                .map(this::toItemResponse)
                .toList();

        return new OrderResponse(order.getId(), order.getUserId(), order.getStatus(),
                items, order.getTotalPrice(), order.getCreatedAt(), order.getUpdatedAt());
    }

    private OrderItemResponse toItemResponse(OrderItem item) {
        BigDecimal subtotal = item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity()));
        return new OrderItemResponse(item.getProductId(), item.getProductName(),
                item.getUnitPrice(), item.getQuantity(), subtotal);
    }
}