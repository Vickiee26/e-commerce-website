package com.mvp.ecommercebackend.admin.dto;

import com.mvp.ecommercebackend.commerce.entity.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One row of the administrative order listing. */
public record AdminOrderSummaryResponse(
        UUID id,
        String orderNumber,
        OrderStatus status,
        UUID userId,
        String userEmail,
        String currency,
        BigDecimal totalAmount,
        Instant placedAt,
        Instant paidAt,
        Instant shippedAt,
        Instant deliveredAt,
        Instant cancelledAt,
        String trackingReference) {
}
