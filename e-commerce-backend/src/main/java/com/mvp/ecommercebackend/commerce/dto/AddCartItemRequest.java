package com.mvp.ecommercebackend.commerce.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * @param quantity units to add to the line, not the resulting total. Capped so a typo cannot ask
 *                 for a quantity no shop would fulfil.
 */
public record AddCartItemRequest(
        @NotNull(message = "must not be null") UUID variantId,
        @NotNull(message = "must not be null")
        @Min(value = 1, message = "must be at least 1")
        @Max(value = 99, message = "must not exceed 99") Integer quantity) {
}
