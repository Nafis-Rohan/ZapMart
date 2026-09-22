package com.nafis.ZapMart.order;

import com.nafis.ZapMart.cart.Cart;
import com.nafis.ZapMart.cart.CartItem;
import com.nafis.ZapMart.cart.CartRepository;
import com.nafis.ZapMart.common.exception.BadRequestException;
import com.nafis.ZapMart.order.dto.OrderItemResponse;
import com.nafis.ZapMart.order.dto.OrderResponse;
import com.nafis.ZapMart.product.Product;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CheckoutService {

    private final CartRepository cartRepository;
    private final OrderRepository orderRepository;

    @Transactional
    public OrderResponse checkout(Long userId) {
        Cart cart = cartRepository.findByUserIdWithItems(userId)
                .filter(c -> !c.getItems().isEmpty())
                .orElseThrow(() -> new BadRequestException("Cart is empty"));

        for (CartItem cartItem : cart.getItems()) {
            Product product = cartItem.getProduct();
            if (cartItem.getQuantity() > product.getStockQuantity()) {
                throw new BadRequestException("Insufficient stock for product: " + product.getName());
            }
        }

        Order order = new Order();
        order.setUserId(userId);
        order.setStatus(OrderStatus.PENDING);

        BigDecimal total = BigDecimal.ZERO;
        for (CartItem cartItem : cart.getItems()) {
            Product product = cartItem.getProduct();

            OrderItem orderItem = new OrderItem();
            orderItem.setOrder(order);
            orderItem.setProductId(product.getId());
            orderItem.setProductName(product.getName());
            orderItem.setUnitPrice(product.getPrice());
            orderItem.setQuantity(cartItem.getQuantity());
            order.getItems().add(orderItem);

            total = total.add(product.getPrice().multiply(BigDecimal.valueOf(cartItem.getQuantity())));
        }
        order.setTotalPrice(total);

        Order saved = orderRepository.save(order);

        cart.getItems().clear();
        cartRepository.save(cart);

        return toResponse(saved);
    }

    private OrderResponse toResponse(Order order) {
        List<OrderItemResponse> items = order.getItems().stream()
                .map(this::toItemResponse)
                .toList();

        return new OrderResponse(order.getId(), order.getUserId(), order.getStatus(),
                items, order.getTotalPrice(), order.getCreatedAt(), order.getUpdatedAt());
    }

    private OrderItemResponse toItemResponse(OrderItem item) {
        BigDecimal subtotal = item.getUnitPrice().multiply(BigDecimal.valueOf(item.getQuantity()));
        return new OrderItemResponse(item.getProductId(), item.getProductName(),
                item.getUnitPrice(), item.getQuantity(), subtotal);
    }
}
