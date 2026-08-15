package com.mvp.ecommercebackend.admin.dto;

import com.mvp.ecommercebackend.commerce.dto.OrderItemResponse;
import com.mvp.ecommercebackend.commerce.dto.ShippingAddressResponse;
import com.mvp.ecommercebackend.commerce.entity.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The administrative order detail view.
 *
 * <p>Wider than the customer's {@code OrderResponse}: it names the customer and carries the
 * fulfilment fields. Those two things must not migrate onto the customer shape — {@code userEmail}
 * because it belongs to someone else's session, and the fulfilment fields because deciding what a
 * customer sees of shipping is its own slice.
 *
 * <p>{@code OrderItemResponse} and {@code ShippingAddressResponse} are reused rather than copied: an
 * order line looks the same to everyone, and a second identical record would drift.
 */
public record AdminOrderResponse(
        UUID id,
        String orderNumber,
        OrderStatus status,
        UUID userId,
        String userEmail,
        String currency,
        BigDecimal subtotalAmount,
        BigDecimal totalAmount,
        String paymentReference,
        String trackingReference,
        ShippingAddressResponse shippingAddress,
        List<OrderItemResponse> items,
        Instant placedAt,
        Instant paidAt,
        Instant shippedAt,
        Instant deliveredAt,
        Instant cancelledAt) {
}
