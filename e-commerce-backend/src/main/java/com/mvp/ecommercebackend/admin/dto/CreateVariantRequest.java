package com.mvp.ecommercebackend.admin.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param stockQuantity an opening balance only, defaulting to zero. Every later change goes through
 *                      {@code POST /api/admin/variants/{id}/stock}, which holds a row lock; an
 *                      absolute setter here would let a stale read undo a concurrent sale.
 */
public record CreateVariantRequest(
        @NotBlank @Size(max = 60) String color,
        @NotBlank @Size(max = 30) String size,
        @Min(value = 0, message = "must not be negative") Integer stockQuantity) {

    public int openingBalance() {
        return stockQuantity == null ? 0 : stockQuantity;
    }
}
