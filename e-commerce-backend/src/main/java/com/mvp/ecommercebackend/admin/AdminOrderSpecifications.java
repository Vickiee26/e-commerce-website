package com.mvp.ecommercebackend.admin;

import com.mvp.ecommercebackend.commerce.entity.Order;
import com.mvp.ecommercebackend.commerce.entity.OrderStatus;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/** The optional filters behind {@code GET /api/admin/orders}. */
final class AdminOrderSpecifications {

    private AdminOrderSpecifications() {
    }

    static Specification<Order> hasStatus(OrderStatus status) {
        return (root, query, builder) -> builder.equal(root.get("status"), status);
    }

    static Specification<Order> placedBy(UUID userId) {
        return (root, query, builder) -> builder.equal(root.get("user").get("id"), userId);
    }

    /**
     * Exact match, case-insensitive. An order number is quoted by a customer over the phone, so it
     * arrives however they typed it — but it is an identifier, not a search term, so no wildcards.
     */
    static Specification<Order> hasOrderNumber(String orderNumber) {
        String normalised = orderNumber.toLowerCase(Locale.ROOT);
        return (root, query, builder) ->
                builder.equal(builder.lower(root.get("orderNumber")), normalised);
    }

    /** Inclusive of {@code from}. */
    static Specification<Order> placedAtOrAfter(Instant from) {
        return (root, query, builder) -> builder.greaterThanOrEqualTo(root.get("placedAt"), from);
    }

    /**
     * Exclusive of {@code to}, so a caller can page through consecutive windows without
     * double-counting an order that lands exactly on a boundary.
     */
    static Specification<Order> placedBefore(Instant to) {
        return (root, query, builder) -> builder.lessThan(root.get("placedAt"), to);
    }
}
