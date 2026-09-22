package com.nafis.ZapMart.cart.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record CartItemQuantityRequest(
        @NotNull @Min(1) Integer quantity
) {}