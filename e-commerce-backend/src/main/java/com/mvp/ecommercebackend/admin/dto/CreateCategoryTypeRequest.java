package com.mvp.ecommercebackend.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** The parent category comes from the path, not the body. */
public record CreateCategoryTypeRequest(
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Size(max = 100)
        @Pattern(regexp = "[a-z0-9]+(-[a-z0-9]+)*",
                message = "must be lower-case letters, digits and single hyphens") String code,
        @Size(max = 2000) String description) {
}
