package com.mvp.ecommercebackend.user.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A partial update. Both fields are optional: {@code null} or absent means "leave unchanged", and an
 * empty string for {@code phone} clears it.
 *
 * <p>{@code @Pattern} rather than {@code @NotBlank} on {@code fullName}, because both ignore null
 * but only the pattern rejects a value made entirely of whitespace while still allowing the field
 * to be omitted.
 */
public record UpdateProfileRequest(

        @Size(max = 255, message = "must be at most 255 characters")
        @Pattern(regexp = ".*\\S.*", message = "must not be blank")
        String fullName,

        @Size(max = 30, message = "must be at most 30 characters")
        String phone) {
}
