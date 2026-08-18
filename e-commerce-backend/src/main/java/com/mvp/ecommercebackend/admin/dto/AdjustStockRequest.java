package com.mvp.ecommercebackend.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A signed stock movement.
 *
 * @param delta  non-zero. Zero would write an audit row asserting a change that did not happen.
 * @param reason required: this is the only record of *why* stock moved, and an unexplained
 *               adjustment cannot be told apart from a mistake
 */
public record AdjustStockRequest(
        @NotNull Integer delta,
        @NotBlank @Size(max = 500) String reason) {

    @jakarta.validation.constraints.AssertTrue(message = "must not be zero")
    public boolean isDeltaNonZero() {
        return delta == null || delta != 0;
    }
}
