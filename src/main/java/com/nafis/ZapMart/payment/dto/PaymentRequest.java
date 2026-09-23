package com.nafis.ZapMart.payment.dto;

import jakarta.validation.constraints.NotBlank;

public record PaymentRequest(
        @NotBlank String paymentMethodId
) {}