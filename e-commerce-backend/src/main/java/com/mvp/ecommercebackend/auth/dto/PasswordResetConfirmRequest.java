package com.mvp.ecommercebackend.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PasswordResetConfirmRequest(

        @NotBlank(message = "must not be blank")
        @Size(max = 200, message = "must be at most 200 characters")
        String token,

        @NotBlank(message = "must not be blank")
        @Size(min = 10, max = 72, message = "must be between 10 and 72 characters")
        String newPassword) {
}
