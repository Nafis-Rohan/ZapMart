package com.nafis.ZapMart.product.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record ProductRequest(
        @NotBlank String name,
        String description,
        @NotNull @DecimalMin("0.0") BigDecimal price,
        @NotNull @Min(0) Integer stockQuantity,
        @NotBlank String category
) {}







//import jakarta.validation.constraints.DecimalMin;
//import jakarta.validation.constraints.Min;
//import jakarta.validation.constraints.NotBlank;
//import jakarta.validation.constraints.NotNull;
//import lombok.Getter;
//import lombok.Setter;
//
//import java.math.BigDecimal;
//
//@Getter
//@Setter
//public class ProductRequest {
//
//    @NotBlank
//    private String name;
//
//    private String description;
//
//    @NotNull
//    @DecimalMin("0.0")
//    private BigDecimal price;
//
//    @NotNull
//    @Min(0)
//    private Integer stockQuantity;
//
//    @NotBlank
//    private String category;
//}
