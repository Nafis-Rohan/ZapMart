package com.nafis.ZapMart.cart;

import com.nafis.ZapMart.cart.dto.CartItemRequest;
import com.nafis.ZapMart.cart.dto.CartItemQuantityRequest;
import com.nafis.ZapMart.cart.dto.CartItemResponse;
import com.nafis.ZapMart.cart.dto.CartResponse;
import com.nafis.ZapMart.common.exception.ResourceNotFoundException;
import com.nafis.ZapMart.product.Product;
import com.nafis.ZapMart.product.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class CartService {

    private final CartRepository cartRepository;
    private final ProductRepository productRepository;

    @Transactional(readOnly = true)
    public CartResponse getCart(Long userId) {
        return cartRepository.findByUserIdWithItems(userId)
                .map(this::toResponse)
                .orElseGet(() -> emptyCartResponse(userId));
    }

    @Transactional
    public CartResponse addItem(Long userId, CartItemRequest request) {
        Product product = productRepository.findById(request.productId())
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + request.productId()));

        Cart cart = getOrCreateCart(userId);

        findItem(cart, request.productId())
                .ifPresentOrElse(
                        existing -> existing.setQuantity(existing.getQuantity() + request.quantity()),
                        () -> {
                            CartItem item = new CartItem();
                            item.setCart(cart);
                            item.setProduct(product);
                            item.setQuantity(request.quantity());
                            cart.getItems().add(item);
                        }
                );

        return toResponse(cartRepository.save(cart));
    }

    @Transactional
    public CartResponse updateItemQuantity(Long userId, Long productId, CartItemQuantityRequest request) {
        Cart cart = findCartOrThrow(userId);
        CartItem item = findItemOrThrow(cart, productId);
        item.setQuantity(request.quantity());
        return toResponse(cartRepository.save(cart));
    }

    @Transactional
    public CartResponse removeItem(Long userId, Long productId) {
        Cart cart = findCartOrThrow(userId);
        CartItem item = findItemOrThrow(cart, productId);
        cart.getItems().remove(item);
        return toResponse(cartRepository.save(cart));
    }

    private Cart getOrCreateCart(Long userId) {
        return cartRepository.findByUserIdWithItems(userId)
                .orElseGet(() -> {
                    Cart cart = new Cart();
                    cart.setUserId(userId);
                    return cartRepository.save(cart);
                });
    }

    private Cart findCartOrThrow(Long userId) {
        return cartRepository.findByUserIdWithItems(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Cart not found for user: " + userId));
    }

    private Optional<CartItem> findItem(Cart cart, Long productId) {
        return cart.getItems().stream()
                .filter(item -> item.getProduct().getId().equals(productId))
                .findFirst();
    }

    private CartItem findItemOrThrow(Cart cart, Long productId) {
        return findItem(cart, productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not in cart: " + productId));
    }

    private CartResponse toResponse(Cart cart) {
        List<CartItemResponse> items = cart.getItems().stream()
                .map(this::toItemResponse)
                .toList();

        BigDecimal total = items.stream()
                .map(CartItemResponse::subtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new CartResponse(cart.getId(), cart.getUserId(), items, total, cart.getCreatedAt(), cart.getUpdatedAt());
    }

    private CartItemResponse toItemResponse(CartItem item) {
        Product product = item.getProduct();
        BigDecimal subtotal = product.getPrice().multiply(BigDecimal.valueOf(item.getQuantity()));
        return new CartItemResponse(product.getId(), product.getName(), product.getPrice(), item.getQuantity(), subtotal);
    }

    private CartResponse emptyCartResponse(Long userId) {
        return new CartResponse(null, userId, List.of(), BigDecimal.ZERO, null, null);
    }
}