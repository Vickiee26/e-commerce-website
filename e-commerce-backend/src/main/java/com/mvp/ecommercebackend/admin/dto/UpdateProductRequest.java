package com.mvp.ecommercebackend.admin.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Partial: a null field is left unchanged.
 *
 * <p>If either {@code categoryId} or {@code categoryTypeId} is supplied, the pair is validated
 * together; an omitted one falls back to the product's current value. A type that does not
 * belong to the resulting category is rejected.
 */
public record UpdateProductRequest(
        @Size(max = 255) @Pattern(regexp = ".*\\S.*", message = "must not be blank") String name,
        @Size(max = 2000) String description,
        @DecimalMin(value = "0.00", message = "must not be negative")
        @Digits(integer = 10, fraction = 2) BigDecimal price,
        UUID categoryId,
        UUID categoryTypeId) {
}
