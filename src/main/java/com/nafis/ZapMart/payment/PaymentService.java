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
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.param.PaymentIntentCreateParams;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private static final String CURRENCY = "usd";

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final ProductRepository productRepository;

    @Transactional
    public PaymentResponse pay(Long userId, Long orderId, PaymentRequest request) {
        Order order = orderRepository.findByIdAndUserIdWithItems(orderId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found"));

        if (order.getStatus() != OrderStatus.PENDING) {
            throw new BadRequestException("Order is not payable, current status: " + order.getStatus());
        }

        if (paymentRepository.findByOrderId(orderId).isPresent()) {
            throw new BadRequestException("A payment already exists for this order");
        }

        ChargeResult result = createAndConfirmIntent(order, request.paymentMethodId());
        PaymentStatus status = "succeeded".equals(result.status()) ? PaymentStatus.SUCCEEDED : PaymentStatus.FAILED;

        Payment payment = new Payment();
        payment.setOrderId(order.getId());
        payment.setUserId(userId);
        payment.setAmount(order.getTotalPrice());
        payment.setCurrency(CURRENCY);
        payment.setStatus(status);
        payment.setStripePaymentIntentId(result.stripePaymentIntentId());
        Payment saved = paymentRepository.save(payment);

        if (status == PaymentStatus.SUCCEEDED) {
            order.setStatus(OrderStatus.PAID);
            decrementStock(order);
        } else {
            order.setStatus(OrderStatus.FAILED);
        }
        orderRepository.save(order);

        return toResponse(saved);
    }

    private record ChargeResult(String status, String stripePaymentIntentId) {}

    private ChargeResult createAndConfirmIntent(Order order, String paymentMethodId) {
        try {
            PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                    .setAmount(order.getTotalPrice().multiply(BigDecimal.valueOf(100)).longValueExact())
                    .setCurrency(CURRENCY)
                    .setPaymentMethod(paymentMethodId)
                    .addPaymentMethodType("card")
                    .setConfirm(true)
                    .build();
            PaymentIntent intent = PaymentIntent.create(params);
            return new ChargeResult(intent.getStatus(), intent.getId());
        } catch (CardException e) {
            // Stripe throws (rather than returning a "failed" PaymentIntent) on a hard decline.
            // Treat it as a normal failed charge, not a request error, so it still gets recorded.
            String intentId = e.getStripeError() != null && e.getStripeError().getPaymentIntent() != null
                    ? e.getStripeError().getPaymentIntent().getId()
                    : null;
            return new ChargeResult("failed", intentId);
        } catch (StripeException e) {
            throw new BadRequestException("Stripe payment failed: " + e.getMessage());
        }
    }

    private void decrementStock(Order order) {
        for (OrderItem item : order.getItems()) {
            if (item.getProductId() == null) {
                continue;
            }
            productRepository.findById(item.getProductId()).ifPresent(product -> {
                product.setStockQuantity(product.getStockQuantity() - item.getQuantity());
                productRepository.save(product);
            });
        }
    }

    private PaymentResponse toResponse(Payment payment) {
        return new PaymentResponse(payment.getId(), payment.getOrderId(), payment.getUserId(),
                payment.getAmount(), payment.getCurrency(), payment.getStatus(),
                payment.getStripePaymentIntentId(), payment.getCreatedAt());
    }
}