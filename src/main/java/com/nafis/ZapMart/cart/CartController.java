package com.nafis.ZapMart.cart;

import com.nafis.ZapMart.cart.dto.CartItemQuantityRequest;
import com.nafis.ZapMart.cart.dto.CartItemRequest;
import com.nafis.ZapMart.cart.dto.CartResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/cart")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;

    @GetMapping
    public CartResponse getCart(@RequestHeader("X-User-Id") Long userId) {
        return cartService.getCart(userId);
    }

    @PostMapping("/items")
    public CartResponse addItem(@RequestHeader("X-User-Id") Long userId,
                                 @Valid @RequestBody CartItemRequest request) {
        return cartService.addItem(userId, request);
    }

    @PutMapping("/items/{productId}")
    public CartResponse updateItemQuantity(@RequestHeader("X-User-Id") Long userId,
                                            @PathVariable Long productId,
                                            @Valid @RequestBody CartItemQuantityRequest request) {
        return cartService.updateItemQuantity(userId, productId, request);
    }

    @DeleteMapping("/items/{productId}")
    public CartResponse removeItem(@RequestHeader("X-User-Id") Long userId,
                                    @PathVariable Long productId) {
        return cartService.removeItem(userId, productId);
    }
}