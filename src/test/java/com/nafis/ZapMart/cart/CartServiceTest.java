package com.nafis.ZapMart.cart;

import com.nafis.ZapMart.cart.dto.CartItemQuantityRequest;
import com.nafis.ZapMart.cart.dto.CartItemRequest;
import com.nafis.ZapMart.cart.dto.CartResponse;
import com.nafis.ZapMart.common.exception.ResourceNotFoundException;
import com.nafis.ZapMart.product.Product;
import com.nafis.ZapMart.product.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CartServiceTest {

    @Mock
    private CartRepository cartRepository;

    @Mock
    private ProductRepository productRepository;

    @InjectMocks
    private CartService cartService;

    private Product product(Long id, String name, String price) {
        Product p = new Product();
        p.setId(id);
        p.setName(name);
        p.setDescription("desc");
        p.setPrice(new BigDecimal(price));
        p.setStockQuantity(100);
        p.setCategory("Test");
        return p;
    }

    private Cart cart(Long id, Long userId) {
        Cart c = new Cart();
        c.setId(id);
        c.setUserId(userId);
        return c;
    }

    private void addItemTo(Cart cart, Product product, int quantity) {
        CartItem item = new CartItem();
        item.setCart(cart);
        item.setProduct(product);
        item.setQuantity(quantity);
        cart.getItems().add(item);
    }

    @Test
    void getCart_returnsEmptyCart_whenNoCartExists() {
        when(cartRepository.findByUserIdWithItems(1L)).thenReturn(Optional.empty());

        CartResponse response = cartService.getCart(1L);

        assertNull(response.id());
        assertEquals(1L, response.userId());
        assertTrue(response.items().isEmpty());
        assertEquals(BigDecimal.ZERO, response.totalPrice());
    }

    @Test
    void getCart_returnsCartWithItems_whenExists() {
        Cart cart = cart(10L, 1L);
        addItemTo(cart, product(5L, "Resistor", "2.50"), 3);
        when(cartRepository.findByUserIdWithItems(1L)).thenReturn(Optional.of(cart));

        CartResponse response = cartService.getCart(1L);

        assertEquals(1, response.items().size());
        assertEquals(new BigDecimal("7.50"), response.totalPrice());
    }

    @Test
    void addItem_createsNewCart_whenNoneExists() {
        Product product = product(5L, "Resistor", "2.50");
        when(productRepository.findById(5L)).thenReturn(Optional.of(product));
        when(cartRepository.findByUserIdWithItems(1L)).thenReturn(Optional.empty());
        when(cartRepository.save(any(Cart.class))).thenAnswer(inv -> inv.getArgument(0));

        CartResponse response = cartService.addItem(1L, new CartItemRequest(5L, 2));

        assertEquals(1, response.items().size());
        assertEquals(2, response.items().get(0).quantity());
    }

    @Test
    void addItem_bumpsQuantity_whenProductAlreadyInCart() {
        Cart cart = cart(10L, 1L);
        Product product = product(5L, "Resistor", "2.50");
        addItemTo(cart, product, 3);
        when(productRepository.findById(5L)).thenReturn(Optional.of(product));
        when(cartRepository.findByUserIdWithItems(1L)).thenReturn(Optional.of(cart));
        when(cartRepository.save(any(Cart.class))).thenAnswer(inv -> inv.getArgument(0));

        CartResponse response = cartService.addItem(1L, new CartItemRequest(5L, 2));

        assertEquals(1, response.items().size());
        assertEquals(5, response.items().get(0).quantity());
    }

    @Test
    void addItem_throws_whenProductNotFound() {
        when(productRepository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> cartService.addItem(1L, new CartItemRequest(999L, 1)));
        verify(cartRepository, never()).save(any());
    }

    @Test
    void updateItemQuantity_updatesQuantity_whenFound() {
        Cart cart = cart(10L, 1L);
        addItemTo(cart, product(5L, "Resistor", "2.50"), 3);
        when(cartRepository.findByUserIdWithItems(1L)).thenReturn(Optional.of(cart));
        when(cartRepository.save(any(Cart.class))).thenAnswer(inv -> inv.getArgument(0));

        CartResponse response = cartService.updateItemQuantity(1L, 5L, new CartItemQuantityRequest(10));

        assertEquals(10, response.items().get(0).quantity());
    }

    @Test
    void updateItemQuantity_throws_whenCartNotFound() {
        when(cartRepository.findByUserIdWithItems(1L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> cartService.updateItemQuantity(1L, 5L, new CartItemQuantityRequest(10)));
    }

    @Test
    void updateItemQuantity_throws_whenItemNotInCart() {
        Cart cart = cart(10L, 1L);
        when(cartRepository.findByUserIdWithItems(1L)).thenReturn(Optional.of(cart));

        assertThrows(ResourceNotFoundException.class,
                () -> cartService.updateItemQuantity(1L, 999L, new CartItemQuantityRequest(10)));
    }

    @Test
    void removeItem_removesItem_whenFound() {
        Cart cart = cart(10L, 1L);
        addItemTo(cart, product(5L, "Resistor", "2.50"), 3);
        when(cartRepository.findByUserIdWithItems(1L)).thenReturn(Optional.of(cart));
        when(cartRepository.save(any(Cart.class))).thenAnswer(inv -> inv.getArgument(0));

        CartResponse response = cartService.removeItem(1L, 5L);

        assertTrue(response.items().isEmpty());
    }

    @Test
    void removeItem_throws_whenItemNotInCart() {
        Cart cart = cart(10L, 1L);
        when(cartRepository.findByUserIdWithItems(1L)).thenReturn(Optional.of(cart));

        assertThrows(ResourceNotFoundException.class, () -> cartService.removeItem(1L, 999L));
    }
}