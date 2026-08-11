package com.mvp.ecommercebackend.commerce.payment;

import java.math.BigDecimal;

/**
 * Takes money for an order.
 *
 * <p>An interface with one simulated implementation, so swapping in a real provider is a new class
 * and a bean definition rather than surgery on {@code OrderService}. The service knows only that a
 * charge is approved or declined; it never sees a card number, and a token is all that crosses this
 * boundary.
 */
public interface PaymentGateway {

    /**
     * @param orderNumber   the merchant reference to record against the charge; safe to log
     * @param amount        the amount to capture, in {@code currency}
     * @param currency      ISO 4217
     * @param paymentMethodToken an opaque token standing in for the customer's instrument. Never
     *                           logged, and never persisted.
     */
    PaymentResult charge(String orderNumber, BigDecimal amount, String currency,
                         String paymentMethodToken);
}
