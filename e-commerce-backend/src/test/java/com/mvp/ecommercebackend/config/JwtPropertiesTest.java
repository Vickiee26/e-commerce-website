package com.mvp.ecommercebackend.config;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class JwtPropertiesTest {

    private static final Validator VALIDATOR;

    static {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        VALIDATOR = factory.getValidator();
    }

    private JwtProperties withSecret(String secret) {
        return new JwtProperties(secret, Duration.ofMinutes(15), Duration.ofDays(30), "shopflow");
    }

    @Test
    void acceptsSecretOfAtLeastThirtyTwoCharacters() {
        JwtProperties properties = withSecret("0123456789abcdef0123456789abcdef");

        assertThat(VALIDATOR.validate(properties)).isEmpty();
    }

    @Test
    void rejectsSecretShorterThanThirtyTwoCharacters() {
        JwtProperties properties = withSecret("too-short-for-hs256");

        assertThat(VALIDATOR.validate(properties))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("secret");
    }

    @Test
    void rejectsBlankSecret() {
        assertThat(VALIDATOR.validate(withSecret("   "))).isNotEmpty();
    }

    @Test
    void rejectsMissingIssuer() {
        JwtProperties properties = new JwtProperties(
                "0123456789abcdef0123456789abcdef", Duration.ofMinutes(15), Duration.ofDays(30), "");

        assertThat(VALIDATOR.validate(properties))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("issuer");
    }

    @Test
    void rejectsMissingTtls() {
        JwtProperties properties = new JwtProperties(
                "0123456789abcdef0123456789abcdef", null, null, "shopflow");

        assertThat(VALIDATOR.validate(properties))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactlyInAnyOrder("accessTokenTtl", "refreshTokenTtl");
    }
}
