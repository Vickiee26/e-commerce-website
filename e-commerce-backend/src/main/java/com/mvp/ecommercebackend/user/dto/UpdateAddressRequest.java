package com.mvp.ecommercebackend.user.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A partial update: every field is optional, and {@code null} means "leave unchanged".
 *
 * <p>The mandatory fields use {@code @Pattern} rather than {@code @NotBlank} because both ignore
 * null but only the pattern rejects an all-whitespace value while still allowing omission. The
 * optional fields accept an empty string, which clears them.
 */
public record UpdateAddressRequest(

        @Size(max = 255, message = "must be at most 255 characters")
        @Pattern(regexp = ".*\\S.*", message = "must not be blank")
        String recipientName,

        @Size(max = 30, message = "must be at most 30 characters")
        String phone,

        @Size(max = 255, message = "must be at most 255 characters")
        @Pattern(regexp = ".*\\S.*", message = "must not be blank")
        String line1,

        @Size(max = 255, message = "must be at most 255 characters")
        String line2,

        @Size(max = 120, message = "must be at most 120 characters")
        @Pattern(regexp = ".*\\S.*", message = "must not be blank")
        String city,

        @Size(max = 120, message = "must be at most 120 characters")
        String state,

        @Size(max = 20, message = "must be at most 20 characters")
        @Pattern(regexp = ".*\\S.*", message = "must not be blank")
        String postalCode,

        @Pattern(regexp = "^[A-Za-z]{2}$", message = "must be a two-letter ISO country code")
        String country,

        Boolean defaultShipping,

        Boolean defaultBilling) {
}
