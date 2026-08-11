package com.mvp.ecommercebackend.commerce.dto;

import com.mvp.ecommercebackend.commerce.entity.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One order in full.
 *
 * @param subtotalAmount sum of the line totals
 * @param totalAmount    what was or will be charged. Equal to the subtotal in this slice; shipping,
 *                       tax and coupons are out of scope, and the two fields exist separately so
 *                       adding them later does not change the meaning of either.
 * @param paidAt         null until the order is paid
 * @param cancelledAt    null unless the order was cancelled
 */
public record OrderResponse(
        UUID id,
        String orderNumber,
        OrderStatus status,
        String currency,
        BigDecimal subtotalAmount,
        BigDecimal totalAmount,
        ShippingAddressResponse shippingAddress,
        List<OrderItemResponse> items,
        Instant placedAt,
        Instant paidAt,
        Instant cancelledAt) {
}
