package com.nafis.ZapMart.cart.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record CartResponse(
        Long id,
        Long userId,
        List<CartItemResponse> items,
        BigDecimal totalPrice,
        Instant createdAt,
        Instant updatedAt
) {}