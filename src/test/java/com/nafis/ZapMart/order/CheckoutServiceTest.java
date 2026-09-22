package com.nafis.ZapMart.order;

import com.nafis.ZapMart.cart.Cart;
import com.nafis.ZapMart.cart.CartItem;
import com.nafis.ZapMart.cart.CartRepository;
import com.nafis.ZapMart.common.exception.BadRequestException;
import com.nafis.ZapMart.order.dto.OrderResponse;
import com.nafis.ZapMart.product.Product;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CheckoutServiceTest {

    @Mock
    private CartRepository cartRepository;

    @Mock
    private OrderRepository orderRepository;

    @InjectMocks
    private CheckoutService checkoutService;

    private Product product(Long id, String name, String price, int stock) {
        Product p = new Product();
        p.setId(id);
        p.setName(name);
        p.setDescription("desc");
        p.setPrice(new BigDecimal(price));
        p.setStockQuantity(stock);
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
    void checkout_throws_whenCartNotFound() {
        when(cartRepository.findByUserIdWithItems(1L)).thenReturn(Optional.empty());

        assertThrows(BadRequestException.class, () -> checkoutService.checkout(1L));
    }

    @Test
    void checkout_throws_whenCartIsEmpty() {
        Cart cart = cart(10L, 1L);
        when(cartRepository.findByUserIdWithItems(1L)).thenReturn(Optional.of(cart));

        assertThrows(BadRequestException.class, () -> checkoutService.checkout(1L));
    }

    @Test
    void checkout_throws_whenInsufficientStock() {
        Cart cart = cart(10L, 1L);
        addItemTo(cart, product(5L, "Resistor", "2.50", 1), 5);
        when(cartRepository.findByUserIdWithItems(1L)).thenReturn(Optional.of(cart));

        assertThrows(BadRequestException.class, () -> checkoutService.checkout(1L));
    }

    @Test
    void checkout_createsOrder_withCorrectTotalAndItems() {
        Cart cart = cart(10L, 1L);
        addItemTo(cart, product(5L, "Resistor", "2.50", 100), 3);
        addItemTo(cart, product(6L, "Capacitor", "1.00", 100), 2);
        when(cartRepository.findByUserIdWithItems(1L)).thenReturn(Optional.of(cart));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        OrderResponse response = checkoutService.checkout(1L);

        assertEquals(1L, response.userId());
        assertEquals(OrderStatus.PENDING, response.status());
        assertEquals(2, response.items().size());
        assertEquals(new BigDecimal("9.50"), response.totalPrice());
    }

    @Test
    void checkout_snapshotsProductNameAndPrice() {
        Cart cart = cart(10L, 1L);
        addItemTo(cart, product(5L, "Resistor", "2.50", 100), 1);
        when(cartRepository.findByUserIdWithItems(1L)).thenReturn(Optional.of(cart));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        OrderResponse response = checkoutService.checkout(1L);

        assertEquals("Resistor", response.items().get(0).productName());
        assertEquals(new BigDecimal("2.50"), response.items().get(0).unitPrice());
    }

    @Test
    void checkout_clearsCart_afterSuccess() {
        Cart cart = cart(10L, 1L);
        addItemTo(cart, product(5L, "Resistor", "2.50", 100), 1);
        when(cartRepository.findByUserIdWithItems(1L)).thenReturn(Optional.of(cart));
        when(orderRepository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        checkoutService.checkout(1L);

        assertTrue(cart.getItems().isEmpty());
    }
}