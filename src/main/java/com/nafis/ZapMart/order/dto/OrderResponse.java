package com.nafis.ZapMart.order.dto;

import com.nafis.ZapMart.order.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OrderResponse(
        Long id,
        Long userId,
        OrderStatus status,
        List<OrderItemResponse> items,
        BigDecimal totalPrice,
        Instant createdAt,
        Instant updatedAt
) {}