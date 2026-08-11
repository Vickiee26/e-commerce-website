package com.mvp.ecommercebackend.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceptance criterion 5: starting without a valid {@code JWT_SECRET} fails at boot.
 *
 * <p>{@link JwtPropertiesTest} proves the constraints are on the record. This proves the consequence
 * — that a weak or absent secret stops the application rather than degrading it to a guessable
 * signing key. Those are different claims, and only this one is the acceptance criterion.
 *
 * <p>Driven with {@link ApplicationContextRunner} rather than by booting the {@code prod} profile,
 * because a real prod boot also demands database credentials and would fail for the wrong reason —
 * which would make the test pass while proving nothing.
 */
class JwtSecretBootstrapTest {

    private static final String VALID_SECRET = "0123456789abcdef0123456789abcdef";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(JwtPropertiesEnabler.class)
            .withPropertyValues(
                    "app.jwt.access-token-ttl=15m",
                    "app.jwt.refresh-token-ttl=30d",
                    "app.jwt.issuer=shopflow");

    @Test
    void startsWithAStrongSecret() {
        runner.withPropertyValues("app.jwt.secret=" + VALID_SECRET)
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void refusesToStartWithNoSecret() {
        runner.run(context -> assertThat(context).hasFailed());
    }

    @Test
    void refusesToStartWithASecretShorterThanThirtyTwoCharacters() {
        runner.withPropertyValues("app.jwt.secret=too-short-for-hs256")
                .run(context -> {
                    assertThat(context).hasFailed();
                    // The constraint message is on the BindValidationException cause, not on the
                    // ConfigurationPropertiesBindException that Spring surfaces, so the whole trace
                    // has to be searched rather than the top-level message.
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining(
                                    "app.jwt.secret must be at least 32 characters");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(JwtProperties.class)
    static class JwtPropertiesEnabler {
    }
}
