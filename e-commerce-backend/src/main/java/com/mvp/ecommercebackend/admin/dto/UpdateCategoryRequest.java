package com.mvp.ecommercebackend.admin.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A partial update: a null field is left alone, so a client can change the description without
 * resending the name.
 *
 * <p>{@code code} is absent deliberately — see {@link CreateCategoryRequest}. The {@code @Pattern}
 * rejects a supplied-but-blank name; Bean Validation skips {@code @Pattern} for null.
 */
public record UpdateCategoryRequest(
        @Size(max = 255) @Pattern(regexp = ".*\\S.*", message = "must not be blank") String name,
        @Size(max = 2000) String description) {
}
