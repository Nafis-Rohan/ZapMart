package com.nafis.ZapMart.order.dto;

import java.math.BigDecimal;

public record OrderItemResponse(
        Long productId,
        String productName,
        BigDecimal unitPrice,
        Integer quantity,
        BigDecimal subtotal
) {}