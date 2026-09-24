package com.nafis.ZapMart.payment;

import com.nafis.ZapMart.common.exception.BadRequestException;
import com.nafis.ZapMart.common.exception.ResourceNotFoundException;
import com.nafis.ZapMart.order.Order;
import com.nafis.ZapMart.order.OrderItem;
import com.nafis.ZapMart.order.OrderRepository;
import com.nafis.ZapMart.order.OrderStatus;
import com.nafis.ZapMart.payment.dto.PaymentRequest;
import com.nafis.ZapMart.payment.dto.PaymentResponse;
import com.nafis.ZapMart.product.Product;
import com.nafis.ZapMart.product.ProductRepository;
import com.stripe.exception.CardException;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    private static final String STRIPE_KEY = "pay-1-client-key-1";

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private ProductRepository productRepository;

    @InjectMocks
    private PaymentService paymentService;

    private Order order(Long id, Long userId, OrderStatus status, String totalPrice) {
        Order o = new Order();
        o.setId(id);
        o.setUserId(userId);
        o.setStatus(status);
        o.setTotalPrice(new BigDecimal(totalPrice));
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

    private Product product(Long id, int stock) {
        Product p = new Product();
        p.setId(id);
        p.setName("Resistor");
        p.setPrice(new BigDecimal("2.50"));
        p.setStockQuantity(stock);
        p.setCategory("Test");
        return p;
    }

    @Test
    void pay_throws_whenOrderNotFound() {
        when(orderRepository.findByIdAndUserIdWithItems(10L, 1L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class,
                () -> paymentService.pay(1L, 10L, new PaymentRequest("pm_card_visa"), STRIPE_KEY));
    }

    @Test
    void pay_throws_whenOrderNotPending() {
        Order order = order(10L, 1L, OrderStatus.PAID, "10.00");
        when(orderRepository.findByIdAndUserIdWithItems(10L, 1L)).thenReturn(Optional.of(order));

        assertThrows(BadRequestException.class,
                () -> paymentService.pay(1L, 10L, new PaymentRequest("pm_card_visa"), STRIPE_KEY));
    }

    @Test
    void pay_throws_whenPaymentAlreadyExists() {
        Order order = order(10L, 1L, OrderStatus.PENDING, "10.00");
        when(orderRepository.findByIdAndUserIdWithItems(10L, 1L)).thenReturn(Optional.of(order));
        when(paymentRepository.findByOrderId(10L)).thenReturn(Optional.of(new Payment()));

        assertThrows(BadRequestException.class,
                () -> paymentService.pay(1L, 10L, new PaymentRequest("pm_card_visa"), STRIPE_KEY));
    }

    @Test
    void pay_success_marksOrderPaid_decrementsStock_savesSucceededPayment() {
        Order order = order(10L, 1L, OrderStatus.PENDING, "5.00");
        addItemTo(order, 5L, "Resistor", "2.50", 2);
        Product product = product(5L, 100);

        when(orderRepository.findByIdAndUserIdWithItems(10L, 1L)).thenReturn(Optional.of(order));
        when(paymentRepository.findByOrderId(10L)).thenReturn(Optional.empty());
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(productRepository.findById(5L)).thenReturn(Optional.of(product));

        PaymentIntent intent = mock(PaymentIntent.class);
        when(intent.getStatus()).thenReturn("succeeded");
        when(intent.getId()).thenReturn("pi_123");

        try (MockedStatic<PaymentIntent> mocked = mockStatic(PaymentIntent.class)) {
            mocked.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class), any(RequestOptions.class))).thenReturn(intent);

            PaymentResponse response = paymentService.pay(1L, 10L, new PaymentRequest("pm_card_visa"), STRIPE_KEY);

            assertEquals(PaymentStatus.SUCCEEDED, response.status());
            assertEquals("pi_123", response.stripePaymentIntentId());
        }

        assertEquals(OrderStatus.PAID, order.getStatus());
        assertEquals(98, product.getStockQuantity());
    }

    @Test
    void pay_sendsTheIdempotencyKeyToStripe() {
        Order order = order(10L, 1L, OrderStatus.PENDING, "5.00");
        when(orderRepository.findByIdAndUserIdWithItems(10L, 1L)).thenReturn(Optional.of(order));
        when(paymentRepository.findByOrderId(10L)).thenReturn(Optional.empty());
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

        PaymentIntent intent = mock(PaymentIntent.class);
        when(intent.getStatus()).thenReturn("requires_payment_method");
        when(intent.getId()).thenReturn("pi_789");

        try (MockedStatic<PaymentIntent> mocked = mockStatic(PaymentIntent.class)) {
            mocked.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class), any(RequestOptions.class)))
                    .thenReturn(intent);

            paymentService.pay(1L, 10L, new PaymentRequest("pm_card_visa"), STRIPE_KEY);

            ArgumentCaptor<RequestOptions> options = ArgumentCaptor.forClass(RequestOptions.class);
            mocked.verify(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class), options.capture()));
            assertEquals(STRIPE_KEY, options.getValue().getIdempotencyKey());
        }
    }

    @Test
    void pay_declined_marksOrderFailed_doesNotDecrementStock() {
        Order order = order(10L, 1L, OrderStatus.PENDING, "5.00");
        addItemTo(order, 5L, "Resistor", "2.50", 2);

        when(orderRepository.findByIdAndUserIdWithItems(10L, 1L)).thenReturn(Optional.of(order));
        when(paymentRepository.findByOrderId(10L)).thenReturn(Optional.empty());
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

        PaymentIntent intent = mock(PaymentIntent.class);
        when(intent.getStatus()).thenReturn("requires_payment_method");
        when(intent.getId()).thenReturn("pi_456");

        try (MockedStatic<PaymentIntent> mocked = mockStatic(PaymentIntent.class)) {
            mocked.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class), any(RequestOptions.class))).thenReturn(intent);

            PaymentResponse response = paymentService.pay(1L, 10L, new PaymentRequest("pm_card_visa_chargeDeclined"), STRIPE_KEY);

            assertEquals(PaymentStatus.FAILED, response.status());
        }

        assertEquals(OrderStatus.FAILED, order.getStatus());
        verifyNoInteractions(productRepository);
    }

    @Test
    void pay_hardDecline_viaCardException_marksOrderFailed_doesNotDecrementStock() {
        // Stripe throws CardException (not a "failed"-status PaymentIntent) on a hard decline,
        // e.g. the pm_card_visa_chargeDeclined test token.
        Order order = order(10L, 1L, OrderStatus.PENDING, "5.00");
        addItemTo(order, 5L, "Resistor", "2.50", 2);

        when(orderRepository.findByIdAndUserIdWithItems(10L, 1L)).thenReturn(Optional.of(order));
        when(paymentRepository.findByOrderId(10L)).thenReturn(Optional.empty());
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

        CardException declined = new CardException(
                "Your card was declined.", "req_123", "card_declined", null, "generic_decline", null, 402, null);

        try (MockedStatic<PaymentIntent> mocked = mockStatic(PaymentIntent.class)) {
            mocked.when(() -> PaymentIntent.create(any(PaymentIntentCreateParams.class), any(RequestOptions.class))).thenThrow(declined);

            PaymentResponse response = paymentService.pay(1L, 10L, new PaymentRequest("pm_card_visa_chargeDeclined"), STRIPE_KEY);

            assertEquals(PaymentStatus.FAILED, response.status());
        }

        assertEquals(OrderStatus.FAILED, order.getStatus());
        verifyNoInteractions(productRepository);
    }
}
