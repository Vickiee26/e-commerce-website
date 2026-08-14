package com.mvp.ecommercebackend.admin.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Partial, like {@link UpdateCategoryRequest}. A type cannot be moved to another category. */
public record UpdateCategoryTypeRequest(
        @Size(max = 255) @Pattern(regexp = ".*\\S.*", message = "must not be blank") String name,
        @Size(max = 2000) String description) {
}
