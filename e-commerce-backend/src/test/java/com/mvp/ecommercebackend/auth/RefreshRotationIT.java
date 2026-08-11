package com.mvp.ecommercebackend.auth;

import com.mvp.ecommercebackend.auth.entity.AuthEventType;
import com.mvp.ecommercebackend.auth.entity.User;
import com.mvp.ecommercebackend.auth.entity.UserStatus;
import com.mvp.ecommercebackend.auth.repository.AuthEventRepository;
import com.mvp.ecommercebackend.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RefreshRotationIT extends AbstractIntegrationTest {

    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String PASSWORD = "Password1!x";

    @Autowired
    private AuthEventRepository authEventRepository;

    @Autowired
    private TokenService tokenService;

    private JsonNode login(String email) throws Exception {
        String body = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(email, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private org.springframework.test.web.servlet.ResultActions refresh(String refreshToken)
            throws Exception {
        return mockMvc.perform(post("/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"refreshToken":"%s"}
                        """.formatted(refreshToken)));
    }

    @Test
    void refreshIsPublicAndReturnsAFreshPair() throws Exception {
        User user = testData.createCustomer("ada@example.com", PASSWORD);
        JsonNode first = login("ada@example.com");

        String body = refresh(first.get("refreshToken").asText())
                .andExpect(status().isOk())
                // No Authorization header was sent: refresh must not require a live access token.
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andReturn().getResponse().getContentAsString();

        JsonNode second = objectMapper.readTree(body);
        assertThat(second.get("refreshToken").asText())
                .isNotEqualTo(first.get("refreshToken").asText());
        assertThat(tokenService.parseAccessToken(second.get("accessToken").asText()).id())
                .isEqualTo(user.getId());
    }

    @Test
    void theRotatedTokenStopsWorkingAndTheNewOneKeepsWorking() throws Exception {
        testData.createCustomer("ada@example.com", PASSWORD);
        String original = login("ada@example.com").get("refreshToken").asText();

        String second = objectMapper.readTree(refresh(original)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString())
                .get("refreshToken").asText();

        String third = objectMapper.readTree(refresh(second)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString())
                .get("refreshToken").asText();

        assertThat(third).isNotEqualTo(second).isNotEqualTo(original);
        assertThat(authEventRepository.findAllByEventType(AuthEventType.TOKEN_REFRESH)).hasSize(2);
    }

    @Test
    void replayingARotatedTokenReturnsFourZeroOneAndKillsEverySession() throws Exception {
        testData.createCustomer("ada@example.com", PASSWORD);
        testData.createCustomer("bystander@example.com", PASSWORD);
        String stolen = login("ada@example.com").get("refreshToken").asText();
        String otherDevice = login("ada@example.com").get("refreshToken").asText();
        String bystander = login("bystander@example.com").get("refreshToken").asText();

        String rotated = objectMapper.readTree(refresh(stolen)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString())
                .get("refreshToken").asText();

        refresh(stolen)
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.detail").value("Refresh token is not valid"));

        // Detection revoked the family and audited it, despite the request ending in a 401.
        assertThat(authEventRepository.findAllByEventType(AuthEventType.TOKEN_REUSE_DETECTED))
                .hasSize(1);
        refresh(rotated).andExpect(status().isUnauthorized());
        refresh(otherDevice).andExpect(status().isUnauthorized());

        // Nobody else is affected.
        refresh(bystander).andExpect(status().isOk());
    }

    @Test
    void rejectsAnUnknownRefreshToken() throws Exception {
        refresh("never-issued-value")
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Refresh token is not valid"));
    }

    @Test
    void rejectsAMissingOrBlankRefreshTokenAsAValidationError() throws Exception {
        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'refreshToken')]").exists());

        refresh("   ").andExpect(status().isBadRequest());
    }

    @Test
    void rejectsARefreshTokenThatWasLoggedOut() throws Exception {
        testData.createCustomer("ada@example.com", PASSWORD);
        JsonNode tokens = login("ada@example.com");

        mockMvc.perform(post("/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.get("accessToken").asText())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}
                                """.formatted(tokens.get("refreshToken").asText())))
                .andExpect(status().isNoContent());

        // A logged-out token is revoked, so presenting it looks exactly like a replay.
        refresh(tokens.get("refreshToken").asText()).andExpect(status().isUnauthorized());
        assertThat(authEventRepository.findAllByEventType(AuthEventType.TOKEN_REUSE_DETECTED))
                .hasSize(1);
    }

    @Test
    void rejectsRefreshOnceTheAccountIsSuspended() throws Exception {
        testData.createCustomer("ada@example.com", PASSWORD);
        String token = login("ada@example.com").get("refreshToken").asText();

        jdbcTemplate.update("UPDATE users SET status = 'SUSPENDED' WHERE email = ?",
                "ada@example.com");

        refresh(token)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Refresh token is not valid"));
    }

    @Test
    void refreshDoesNotResurrectASuspendedAccountsAccessToken() throws Exception {
        User user = testData.createCustomer("ada@example.com", PASSWORD, UserStatus.SUSPENDED);

        // Cannot even get a pair to begin with.
        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(user.getEmail(), PASSWORD)))
                .andExpect(status().isUnauthorized());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM refresh_tokens", Integer.class)).isZero();
    }
}
