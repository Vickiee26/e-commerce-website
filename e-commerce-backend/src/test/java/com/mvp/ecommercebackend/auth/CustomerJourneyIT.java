package com.mvp.ecommercebackend.auth;

import com.jayway.jsonpath.JsonPath;
import com.mvp.ecommercebackend.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Acceptance criterion 3, end to end in one sequence: a new customer can register, log in, read
 * their profile, add an address, refresh, and log out — after which the refresh token they logged
 * out with is dead.
 *
 * <p>Every step goes over HTTP. Nothing is set up through a repository, so this fails if any layer
 * in the chain is wired wrongly, which is precisely what the per-feature tests cannot tell you.
 */
class CustomerJourneyIT extends AbstractIntegrationTest {

    private String readJson(String body, String path) {
        return JsonPath.read(body, path);
    }

    @Test
    void newCustomerCanRegisterLogInReadTheProfileAddAnAddressRefreshAndLogOut() throws Exception {
        // 1. Register. The response is a usable token pair, so no separate login is required.
        String registration = mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"journey@example.com","password":"Password1!x",
                                 "fullName":"Journey Customer","phone":"+15550142"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andReturn().getResponse().getContentAsString();

        assertThat(readJson(registration, "$.refreshToken")).isNotBlank();

        // 2. Log in with the same credentials, which must issue a second, independent pair.
        String login = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"journey@example.com","password":"Password1!x"}
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String accessToken = readJson(login, "$.accessToken");
        String refreshToken = readJson(login, "$.refreshToken");
        assertThat(refreshToken).isNotEqualTo(readJson(registration, "$.refreshToken"));

        // 3. Read the profile with the access token.
        mockMvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("journey@example.com"))
                .andExpect(jsonPath("$.fullName").value("Journey Customer"))
                .andExpect(jsonPath("$.roles").value("CUSTOMER"));

        // 4. Add an address, then read it back through the list endpoint.
        mockMvc.perform(post("/api/me/addresses")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"recipientName":"Journey Customer","line1":"1 First Street",
                                 "city":"Chennai","postalCode":"600001","country":"IN",
                                 "defaultShipping":true}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/me/addresses")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].city").value("Chennai"))
                .andExpect(jsonPath("$[0].defaultShipping").value(true));

        // 5. Refresh. The rotated pair must differ from the one presented.
        String refreshed = mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}
                                """.formatted(refreshToken)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String rotatedAccess = readJson(refreshed, "$.accessToken");
        String rotatedRefresh = readJson(refreshed, "$.refreshToken");
        assertThat(rotatedRefresh).isNotEqualTo(refreshToken);

        // The new access token works on a protected route.
        mockMvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + rotatedAccess))
                .andExpect(status().isOk());

        // 6. Log out with the rotated refresh token.
        mockMvc.perform(post("/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + rotatedAccess)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}
                                """.formatted(rotatedRefresh)))
                .andExpect(status().isNoContent());

        // 7. That refresh token is now dead.
        mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}
                                """.formatted(rotatedRefresh)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void recordsTheWholeJourneyInTheAuditTrail() throws Exception {
        String registration = mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"audited@example.com","password":"Password1!x",
                                 "fullName":"Audited Customer"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String refreshed = mockMvc.perform(post("/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}
                                """.formatted(readJson(registration, "$.refreshToken"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        mockMvc.perform(post("/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION,
                                "Bearer " + readJson(refreshed, "$.accessToken"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}
                                """.formatted(readJson(refreshed, "$.refreshToken"))))
                .andExpect(status().isNoContent());

        List<String> events = jdbcTemplate.queryForList(
                "SELECT event_type FROM auth_events ORDER BY created_at", String.class);

        assertThat(events).containsExactly("LOGIN_SUCCESS", "TOKEN_REFRESH", "LOGOUT");
    }
}
