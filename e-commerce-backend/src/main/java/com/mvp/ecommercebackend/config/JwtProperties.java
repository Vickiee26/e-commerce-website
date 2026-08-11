package com.mvp.ecommercebackend.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * JWT settings bound from {@code app.jwt.*}.
 *
 * <p>The 32-character floor on {@code secret} is what makes startup fail fast on a weak key.
 * HS256 requires a key of at least 32 <em>bytes</em>; since every UTF-8 character occupies at
 * least one byte, 32 characters is a sufficient condition for that and is simpler to express.
 */
@Validated
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(

        @NotBlank
        @Size(min = 32, message = "app.jwt.secret must be at least 32 characters for HS256")
        String secret,

        @NotNull
        Duration accessTokenTtl,

        @NotNull
        Duration refreshTokenTtl,

        @NotBlank
        String issuer
) {
}
