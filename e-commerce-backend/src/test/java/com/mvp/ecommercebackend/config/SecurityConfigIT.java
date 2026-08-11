package com.mvp.ecommercebackend.config;

import com.mvp.ecommercebackend.auth.AuthenticatedUser;
import com.mvp.ecommercebackend.auth.TokenService;
import com.mvp.ecommercebackend.auth.entity.User;
import com.mvp.ecommercebackend.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SecurityConfigIT extends AbstractIntegrationTest {

    private static final String PROBLEM_JSON = "application/problem+json";

    @Autowired
    private TokenService tokenService;

    @Autowired
    private JwtProperties jwtProperties;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String bearer(User user) {
        return "Bearer " + tokenService.generateAccessToken(user);
    }

    @Test
    void allowsAnonymousAccessToPublicGetEndpoints() throws Exception {
        mockMvc.perform(get("/api/products/probe"))
                .andExpect(status().isOk())
                .andExpect(content().string("public"));
    }

    @Test
    void allowsAnonymousAccessToTheOpenApiDocument() throws Exception {
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
    }

    @Test
    void allowsAnonymousAccessToTheHealthEndpoint() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void rejectsAProtectedEndpointWithNoTokenAsProblemJson() throws Exception {
        mockMvc.perform(get("/api/probe/authenticated"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.instance").value("/api/probe/authenticated"));
    }

    @Test
    void rejectsAMalformedToken() throws Exception {
        mockMvc.perform(get("/api/probe/authenticated")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));
    }

    @Test
    void rejectsAnAuthorizationHeaderThatIsNotBearer() throws Exception {
        mockMvc.perform(get("/api/probe/authenticated")
                        .header(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNz"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsATamperedToken() throws Exception {
        User user = testData.createCustomer("person@example.com", "Password1!");
        String token = tokenService.generateAccessToken(user);
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("A") ? "B" : "A");

        mockMvc.perform(get("/api/probe/authenticated")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tampered))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));
    }

    @Test
    void rejectsAnExpiredToken() throws Exception {
        User user = testData.createCustomer("person@example.com", "Password1!");
        TokenService pastIssuer = new TokenService(jwtProperties,
                Clock.fixed(Instant.now().minus(Duration.ofHours(2)), ZoneOffset.UTC));
        String stale = pastIssuer.generateAccessToken(user);

        mockMvc.perform(get("/api/probe/authenticated")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + stale))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));
    }

    @Test
    void acceptsAValidTokenAndExposesThePrincipal() throws Exception {
        User user = testData.createCustomer("person@example.com", "Password1!");

        mockMvc.perform(get("/api/probe/authenticated").header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk())
                .andExpect(content().string("person@example.com"));
    }

    @Test
    void deniesAdminRoutesToCustomersAsProblemJson() throws Exception {
        User customer = testData.createCustomer("person@example.com", "Password1!");

        mockMvc.perform(get("/api/admin/probe").header(HttpHeaders.AUTHORIZATION, bearer(customer)))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Forbidden"))
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void allowsAdminRoutesToAdmins() throws Exception {
        User admin = testData.createAdmin("admin@example.com", "Password1!");

        mockMvc.perform(get("/api/admin/probe").header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(content().string("admin"));
    }

    @Test
    void enforcesMethodSecurityEvenOnAPathThatIsOnlyAuthenticated() throws Exception {
        User customer = testData.createCustomer("person@example.com", "Password1!");

        mockMvc.perform(get("/api/probe/admin-only").header(HttpHeaders.AUTHORIZATION, bearer(customer)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("Forbidden"));
    }

    @Test
    void createsNoHttpSession() throws Exception {
        User user = testData.createCustomer("person@example.com", "Password1!");

        assertThat(mockMvc.perform(get("/api/probe/authenticated")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk())
                .andReturn()
                .getRequest()
                .getSession(false))
                .isNull();
    }

    @Test
    void hashesPasswordsWithBcryptStrengthTen() {
        String hash = passwordEncoder.encode("Password1!");

        assertThat(hash).startsWith("$2a$10$");
        assertThat(passwordEncoder.matches("Password1!", hash)).isTrue();
        assertThat(passwordEncoder.matches("wrong", hash)).isFalse();
    }

    /**
     * Stand-ins for the real controllers, which arrive in later tasks.
     *
     * <p>{@code ProbeController} is registered by nesting alone, with no {@code @Bean} method:
     * {@code @RestController} is meta-annotated {@code @Component}, and a member class of a
     * {@code @Configuration} that carries a component annotation is itself processed as a
     * configuration class. Adding an explicit {@code @Bean} on top of that registers the class
     * twice and the context fails with "Ambiguous mapping".
     *
     * <p>{@code @RestController} is also required rather than optional: Spring Framework 7's
     * {@code RequestMappingHandlerMapping.isHandler} tests for {@code @Controller} only, so a class
     * carrying just {@code @RequestMapping} is never mapped and every probe request 404s.
     */
    @TestConfiguration
    static class ProbeConfig {

        @RestController
        static class ProbeController {

            @GetMapping("/api/products/probe")
            String publicProbe() {
                return "public";
            }

            @GetMapping("/api/probe/authenticated")
            String authenticatedProbe(@AuthenticationPrincipal AuthenticatedUser principal) {
                return principal.email();
            }

            @GetMapping("/api/admin/probe")
            String adminProbe() {
                return "admin";
            }

            @GetMapping("/api/probe/admin-only")
            @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
            String methodSecuredProbe() {
                return "admin";
            }
        }
    }
}
