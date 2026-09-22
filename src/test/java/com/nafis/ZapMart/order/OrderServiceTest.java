package com.nafis.ZapMart.order;

import com.nafis.ZapMart.common.exception.ResourceNotFoundException;
import com.nafis.ZapMart.order.dto.OrderResponse;
import com.nafis.ZapMart.order.dto.OrderSummaryResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @InjectMocks
    private OrderService orderService;

    private Order order(Long id, Long userId, String total) {
        Order o = new Order();
        o.setId(id);
        o.setUserId(userId);
        o.setStatus(OrderStatus.PENDING);
        o.setTotalPrice(new BigDecimal(total));
        return o;
    }

    private void addItemTo(Order order, Long productId, String name, String price, int quantity) {
        OrderItem item = new OrderItem();
        item.setOrder(order);
        item.setProductId(productId);
        item.setProductName(name);
        item.setUnitPrice(new BigDecimal(price));
        item.setQuantity(quantity);
        order.getItems().add(item);
    }

    @Test
    void list_returnsSummaries_forUser() {
        when(orderRepository.findByUserIdOrderByCreatedAtDesc(1L))
                .thenReturn(List.of(order(10L, 1L, "9.50"), order(11L, 1L, "5.00")));

        List<OrderSummaryResponse> result = orderService.list(1L);

        assertEquals(2, result.size());
        assertEquals(new BigDecimal("9.50"), result.get(0).totalPrice());
    }

    @Test
    void get_returnsOrder_whenFound() {
        Order order = order(10L, 1L, "5.00");
        addItemTo(order, 5L, "Resistor", "2.50", 2);
        when(orderRepository.findByIdAndUserIdWithItems(10L, 1L)).thenReturn(Optional.of(order));

        OrderResponse response = orderService.get(1L, 10L);

        assertEquals(1, response.items().size());
        assertEquals(new BigDecimal("5.00"), response.items().get(0).subtotal());
    }

    @Test
    void get_throws_whenNotFound() {
        when(orderRepository.findByIdAndUserIdWithItems(999L, 1L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> orderService.get(1L, 999L));
    }
}
