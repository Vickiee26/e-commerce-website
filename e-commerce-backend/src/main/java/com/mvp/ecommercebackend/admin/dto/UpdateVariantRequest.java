package com.mvp.ecommercebackend.admin.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Partial: a null field is left unchanged.
 *
 * <p>There is deliberately no {@code stockQuantity}. Stock moves only through the delta endpoint, and
 * the absence of the field is the enforcement — no validation rule to forget.
 */
public record UpdateVariantRequest(
        @Size(max = 60) @Pattern(regexp = ".*\\S.*", message = "must not be blank") String color,
        @Size(max = 30) @Pattern(regexp = ".*\\S.*", message = "must not be blank") String size) {
}
