package com.mvp.ecommercebackend.commerce.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * @param quantity the line's new total, replacing whatever was there. Removing a line is a DELETE,
 *                 so zero is rejected rather than treated as one.
 */
public record UpdateCartItemRequest(
        @NotNull(message = "must not be null")
        @Min(value = 1, message = "must be at least 1")
        @Max(value = 99, message = "must not exceed 99") Integer quantity) {
}
