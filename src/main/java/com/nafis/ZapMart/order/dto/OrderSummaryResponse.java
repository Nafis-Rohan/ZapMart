package com.nafis.ZapMart.order.dto;

import com.nafis.ZapMart.order.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record OrderSummaryResponse(
        Long id,
        OrderStatus status,
        BigDecimal totalPrice,
        Instant createdAt
) {}