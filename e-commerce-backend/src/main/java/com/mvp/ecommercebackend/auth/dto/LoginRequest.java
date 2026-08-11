package com.mvp.ecommercebackend.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Login input. Note the absence of {@code @Email} and any length floor on the password: rejecting a
 * malformed credential with a 400 that a valid-but-wrong one answers with 401 would itself leak
 * information, and the point of login is to accept whatever the client has and answer uniformly.
 */
public record LoginRequest(

        @NotBlank(message = "must not be blank")
        @Size(max = 255, message = "must be at most 255 characters")
        String email,

        @NotBlank(message = "must not be blank")
        @Size(max = 72, message = "must be at most 72 characters")
        String password) {
}
