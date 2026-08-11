package com.mvp.ecommercebackend.commerce.entity;

/**
 * The order lifecycle for this slice.
 *
 * <p>Legal transitions are {@code PENDING_PAYMENT → PAID} and {@code PENDING_PAYMENT → CANCELLED}.
 * A paid order cannot be cancelled: taking money back is a refund, which needs its own record of
 * who authorised it and how much was returned, and that is a later slice. {@code PAID} and
 * {@code CANCELLED} are both terminal here.
 */
public enum OrderStatus {

    PENDING_PAYMENT,
    PAID,
    CANCELLED
}
