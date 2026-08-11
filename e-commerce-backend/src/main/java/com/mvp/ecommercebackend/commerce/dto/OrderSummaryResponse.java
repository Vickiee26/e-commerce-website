package com.mvp.ecommercebackend.commerce.dto;

import com.mvp.ecommercebackend.commerce.entity.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A row in the order history. No lines: fetching a collection alongside a page forces Hibernate to
 * paginate in memory, and a history list shows totals rather than contents.
 */
public record OrderSummaryResponse(
        UUID id,
        String orderNumber,
        OrderStatus status,
        String currency,
        BigDecimal totalAmount,
        Instant placedAt) {
}
