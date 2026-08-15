package com.mvp.ecommercebackend.admin.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Partial: a null field is left unchanged.
 *
 * <p>{@code isPrimary} is a {@code Boolean} rather than a {@code boolean} precisely so that "not
 * mentioned" and "set to false" stay distinguishable — a primitive would silently demote the primary
 * image on every unrelated rename.
 */
public record UpdateResourceRequest(
        @Size(max = 255) String name,
        @Size(max = 1000) @Pattern(regexp = ".*\\S.*", message = "must not be blank") String url,
        @Size(max = 30) String type,
        Boolean isPrimary) {
}
