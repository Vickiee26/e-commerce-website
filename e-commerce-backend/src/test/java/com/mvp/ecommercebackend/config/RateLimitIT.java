package com.mvp.ecommercebackend.config;

import com.mvp.ecommercebackend.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The test profile disables rate limiting so that other suites are not throttled; this class turns
 * it back on with a small capacity. Buckets are held in the filter and survive database truncation,
 * so every test uses its own client IP.
 */
@TestPropertySource(properties = {
        "app.rate-limit.enabled=true",
        "app.rate-limit.capacity=3",
        "app.rate-limit.refill-period=1m"
})
class RateLimitIT extends AbstractIntegrationTest {

    private static final String PROBLEM_JSON = "application/problem+json";
    private static final int CAPACITY = 3;

    private org.springframework.test.web.servlet.ResultActions loginFrom(String ip)
            throws Exception {
        return mockMvc.perform(post("/auth/login")
                .header("X-Forwarded-For", ip)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"nobody@example.com","password":"WrongPassword1"}
                        """));
    }

    @Test
    void allowsUpToCapacityThenRejects() throws Exception {
        String ip = "198.51.100.1";

        for (int attempt = 0; attempt < CAPACITY; attempt++) {
            loginFrom(ip).andExpect(status().isUnauthorized());
        }

        loginFrom(ip).andExpect(status().isTooManyRequests());
    }

    @Test
    void answersAnExhaustedBudgetWithProblemJsonAndRetryAfter() throws Exception {
        String ip = "198.51.100.2";
        for (int attempt = 0; attempt < CAPACITY; attempt++) {
            loginFrom(ip);
        }

        loginFrom(ip)
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "60"))
                .andExpect(jsonPath("$.title").value("Too many requests"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.instance").value("/auth/login"));
    }

    @Test
    void budgetsAreIndependentPerClientIp() throws Exception {
        String exhausted = "198.51.100.3";
        for (int attempt = 0; attempt < CAPACITY; attempt++) {
            loginFrom(exhausted);
        }
        loginFrom(exhausted).andExpect(status().isTooManyRequests());

        loginFrom("198.51.100.4").andExpect(status().isUnauthorized());
    }

    @Test
    void budgetsAreIndependentPerEndpoint() throws Exception {
        String ip = "198.51.100.5";
        for (int attempt = 0; attempt < CAPACITY; attempt++) {
            loginFrom(ip);
        }
        loginFrom(ip).andExpect(status().isTooManyRequests());

        // Locking out login must not lock out registration.
        mockMvc.perform(post("/auth/register")
                        .header("X-Forwarded-For", ip)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"fresh@example.com","password":"Password1!x","fullName":"Fresh"}
                                """))
                .andExpect(status().isCreated());
    }

    @Test
    void limitsPasswordResetRequests() throws Exception {
        String ip = "198.51.100.6";

        for (int attempt = 0; attempt < CAPACITY; attempt++) {
            mockMvc.perform(post("/auth/password-reset/request")
                            .header("X-Forwarded-For", ip)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"nobody@example.com"}
                                    """))
                    .andExpect(status().isAccepted());
        }

        mockMvc.perform(post("/auth/password-reset/request")
                        .header("X-Forwarded-For", ip)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"nobody@example.com"}
                                """))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void countsSuccessfulLoginsAgainstTheBudgetToo() throws Exception {
        String ip = "198.51.100.7";
        testData.createCustomer("ada@example.com", "Password1!x");
        String body = """
                {"email":"ada@example.com","password":"Password1!x"}
                """;

        for (int attempt = 0; attempt < CAPACITY; attempt++) {
            mockMvc.perform(post("/auth/login")
                            .header("X-Forwarded-For", ip)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(post("/auth/login")
                        .header("X-Forwarded-For", ip)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void doesNotLimitPublicReadEndpoints() throws Exception {
        String ip = "198.51.100.8";
        String unknownProduct = UUID.randomUUID().toString();

        for (int attempt = 0; attempt < CAPACITY * 3; attempt++) {
            mockMvc.perform(get("/api/products/" + unknownProduct).header("X-Forwarded-For", ip))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    void usesTheFirstEntryOfAForwardedForChain() throws Exception {
        String client = "198.51.100.9";
        for (int attempt = 0; attempt < CAPACITY; attempt++) {
            loginFrom(client + ", 10.0.0.1, 10.0.0.2");
        }

        // Keyed on the client, not the proxy hops, so a bare header value hits the same bucket.
        loginFrom(client).andExpect(status().isTooManyRequests());
    }
}
