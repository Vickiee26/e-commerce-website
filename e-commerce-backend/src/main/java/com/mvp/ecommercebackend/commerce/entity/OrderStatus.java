package com.mvp.ecommercebackend.commerce.entity;

/**
 * The order lifecycle.
 *
 * <p>Legal transitions are {@code PENDING_PAYMENT → PAID → SHIPPED → DELIVERED} plus
 * {@code PENDING_PAYMENT → CANCELLED}. A paid order cannot be cancelled and a shipment cannot be
 * reversed: both mean returning money, which needs its own record of who authorised it and how
 * much came back, and that is a later slice. {@code DELIVERED} and {@code CANCELLED} are terminal.
 */
public enum OrderStatus {

    PENDING_PAYMENT,
    PAID,
    SHIPPED,
    DELIVERED,
    CANCELLED
}
