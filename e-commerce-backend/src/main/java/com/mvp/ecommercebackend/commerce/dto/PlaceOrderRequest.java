package com.mvp.ecommercebackend.commerce.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * @param shippingAddressId one of the caller's own addresses. Its contents are copied onto the
 *                          order, so editing it afterwards does not change where the order says it
 *                          went. An id belonging to anyone else answers 404.
 */
public record PlaceOrderRequest(
        @NotNull(message = "must not be null") UUID shippingAddressId) {
}
