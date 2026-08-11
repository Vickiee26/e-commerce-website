package com.mvp.ecommercebackend.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Every {@code @Size} matches the column width in {@code V1__init.sql}, so an overlong value is a
 * 400 rather than a 500 from the database.
 *
 * <p>The two default flags are {@code Boolean} rather than {@code boolean} so that omitting them is
 * distinguishable from sending {@code false}; both are treated as "not a default".
 */
public record CreateAddressRequest(

        @NotBlank(message = "must not be blank")
        @Size(max = 255, message = "must be at most 255 characters")
        String recipientName,

        @Size(max = 30, message = "must be at most 30 characters")
        String phone,

        @NotBlank(message = "must not be blank")
        @Size(max = 255, message = "must be at most 255 characters")
        String line1,

        @Size(max = 255, message = "must be at most 255 characters")
        String line2,

        @NotBlank(message = "must not be blank")
        @Size(max = 120, message = "must be at most 120 characters")
        String city,

        @Size(max = 120, message = "must be at most 120 characters")
        String state,

        @NotBlank(message = "must not be blank")
        @Size(max = 20, message = "must be at most 20 characters")
        String postalCode,

        @NotBlank(message = "must not be blank")
        @Pattern(regexp = "^[A-Za-z]{2}$", message = "must be a two-letter ISO country code")
        String country,

        Boolean defaultShipping,

        Boolean defaultBilling) {
}
