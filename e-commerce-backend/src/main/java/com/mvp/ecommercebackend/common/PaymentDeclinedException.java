package com.mvp.ecommercebackend.common;

/**
 * The gateway refused the charge. Answered as 402 Payment Required, and the order is left
 * {@code PENDING_PAYMENT} so the customer can try another instrument.
 */
public class PaymentDeclinedException extends RuntimeException {

    public PaymentDeclinedException(String message) {
        super(message);
    }
}
