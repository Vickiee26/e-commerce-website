package com.mvp.ecommercebackend.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Registration input. There is deliberately no role, status, or verification field: those are set
 * by the server, so no request can escalate itself.
 *
 * <p>The password upper bound is 72 because BCrypt hashes at most 72 bytes and silently discards
 * the remainder — accepting more would let two different passwords open the same account.
 */
public record RegisterRequest(

        @NotBlank(message = "must not be blank")
        @Email(message = "must be a valid email")
        @Size(max = 255, message = "must be at most 255 characters")
        String email,

        @NotBlank(message = "must not be blank")
        @Size(min = 10, max = 72, message = "must be between 10 and 72 characters")
        String password,

        @NotBlank(message = "must not be blank")
        @Size(max = 255, message = "must be at most 255 characters")
        String fullName,

        @Size(max = 30, message = "must be at most 30 characters")
        String phone) {
}
