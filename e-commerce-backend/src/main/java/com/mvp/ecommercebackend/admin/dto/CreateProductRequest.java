package com.mvp.ecommercebackend.admin.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * @param price bounded to match {@code products.price numeric(12,2)}: ten integer digits and two
 *              decimals. Without {@code @Digits} an over-precise value would reach Postgres and
 *              either round silently or fail the insert as a 500.
 */
public record CreateProductRequest(
        @NotBlank @Size(max = 255) String name,
        @Size(max = 2000) String description,
        @NotNull @DecimalMin(value = "0.00", message = "must not be negative")
        @Digits(integer = 10, fraction = 2) BigDecimal price,
        @NotNull UUID categoryId,
        @NotNull UUID categoryTypeId) {
}
