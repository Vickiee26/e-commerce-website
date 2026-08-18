package com.mvp.ecommercebackend.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param trackingReference the carrier's identifier. Required: an order marked shipped with no way to
 *                          trace the parcel gives a customer service agent nothing to work with.
 *                          Capped at 100 to match {@code orders.tracking_reference varchar(100)}.
 */
public record ShipOrderRequest(@NotBlank @Size(max = 100) String trackingReference) {
}
