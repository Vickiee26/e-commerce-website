package com.mvp.ecommercebackend.common;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    private static final String PROBLEM_JSON = "application/problem+json";

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void mapsBeanValidationFailureToFourHundredWithFieldErrors() throws Exception {
        mockMvc.perform(post("/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("Request contains invalid fields"))
                .andExpect(jsonPath("$.instance").value("/test/validate"))
                .andExpect(jsonPath("$.errors[?(@.field == 'email')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'name')]").exists());
    }

    @Test
    void mapsMalformedJsonToFourHundred() throws Exception {
        mockMvc.perform(post("/test/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Malformed request"));
    }

    @Test
    void mapsUnparseablePathVariableToFourHundred() throws Exception {
        mockMvc.perform(get("/test/products/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Malformed request"));
    }

    @Test
    void mapsInvalidCredentialsToFourHundredAndOne() throws Exception {
        mockMvc.perform(get("/test/invalid-credentials"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Unauthorized"));
    }

    @Test
    void mapsInvalidTokenToFourHundredAndOne() throws Exception {
        mockMvc.perform(get("/test/invalid-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Unauthorized"));
    }

    @Test
    void mapsAccessDeniedToFourHundredAndThree() throws Exception {
        mockMvc.perform(get("/test/access-denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Forbidden"));
    }

    @Test
    void mapsResourceNotFoundToFourHundredAndFour() throws Exception {
        mockMvc.perform(get("/test/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Not found"))
                .andExpect(jsonPath("$.detail").value("Product Not Found!"));
    }

    @Test
    void mapsDuplicateResourceToFourHundredAndNine() throws Exception {
        mockMvc.perform(get("/test/duplicate"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Conflict"));
    }

    @Test
    void mapsRateLimitToFourHundredAndTwentyNine() throws Exception {
        mockMvc.perform(get("/test/rate-limited"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.title").value("Too many requests"));
    }

    @Test
    void hidesInternalDetailBehindACorrelationIdOnFiveHundred() throws Exception {
        mockMvc.perform(get("/test/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Internal server error"))
                .andExpect(jsonPath("$.correlationId").exists())
                .andExpect(jsonPath("$.detail", containsString("Reference:")))
                // The message and any stack frame must never reach the client.
                .andExpect(jsonPath("$.detail", not(containsString("jdbc password"))))
                .andExpect(content().string(not(containsString("ThrowingController"))));
    }

    record Payload(@Email @NotBlank String email, @NotBlank String name) {
    }

    @RestController
    static class ThrowingController {

        @PostMapping("/test/validate")
        String validate(@Valid @RequestBody Payload payload) {
            return "ok";
        }

        @GetMapping("/test/products/{id}")
        String product(@PathVariable UUID id) {
            return id.toString();
        }

        @GetMapping("/test/invalid-credentials")
        String invalidCredentials() {
            throw new InvalidCredentialsException("Email or password is incorrect");
        }

        @GetMapping("/test/invalid-token")
        String invalidToken() {
            throw new InvalidTokenException("Refresh token is not valid");
        }

        @GetMapping("/test/access-denied")
        String accessDenied() {
            throw new AccessDeniedException("nope");
        }

        @GetMapping("/test/not-found")
        String notFound() {
            throw new ResourceNotFoundException("Product Not Found!");
        }

        @GetMapping("/test/duplicate")
        String duplicate() {
            throw new DuplicateResourceException("Email is already registered");
        }

        @GetMapping("/test/rate-limited")
        String rateLimited() {
            throw new RateLimitExceededException("Too many attempts");
        }

        @GetMapping("/test/boom")
        String boom() {
            throw new IllegalStateException("connection failed using jdbc password hunter2");
        }
    }
}
