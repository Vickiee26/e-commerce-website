package com.mvp.ecommercebackend.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Rate-limit settings bound from {@code app.rate-limit.*}.
 *
 * <p>{@code enabled} exists so integration tests other than the rate-limit test can switch the
 * filter off; shared in-memory buckets would otherwise leak state between tests.
 */
@Validated
@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(

        boolean enabled,

        @Min(1)
        int capacity,

        @NotNull
        Duration refillPeriod
) {
}
