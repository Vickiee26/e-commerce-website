package com.mvp.ecommercebackend.auth;

import com.mvp.ecommercebackend.auth.entity.AuthEventType;
import com.mvp.ecommercebackend.auth.entity.Role;
import com.mvp.ecommercebackend.auth.entity.RoleCode;
import com.mvp.ecommercebackend.auth.entity.User;
import com.mvp.ecommercebackend.auth.entity.UserStatus;
import com.mvp.ecommercebackend.auth.repository.AuthEventRepository;
import com.mvp.ecommercebackend.auth.repository.UserRepository;
import com.mvp.ecommercebackend.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthControllerIT extends AbstractIntegrationTest {

    private static final String PROBLEM_JSON = "application/problem+json";

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuthEventRepository authEventRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TokenService tokenService;

    private MvcResult register(String body) throws Exception {
        return mockMvc.perform(post("/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)).andReturn();
    }

    private String validRegistration(String email) {
        return """
                {"email":"%s","password":"Password1!x","fullName":"Ada Lovelace","phone":"+15550100"}
                """.formatted(email);
    }

    @Test
    void registersANewCustomerAndReturnsATokenPair() throws Exception {
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRegistration("ada@example.com")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900));
    }

    @Test
    void storesTheUserWithABcryptHashAndTheCustomerRoleOnly() throws Exception {
        register(validRegistration("ada@example.com"));

        User saved = userRepository.findByEmailIgnoreCase("ada@example.com").orElseThrow();
        assertThat(saved.getFullName()).isEqualTo("Ada Lovelace");
        assertThat(saved.getPhone()).isEqualTo("+15550100");
        assertThat(saved.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(saved.isEmailVerified()).isFalse();
        assertThat(saved.getPasswordHash()).startsWith("$2a$10$").isNotEqualTo("Password1!x");
        assertThat(passwordEncoder.matches("Password1!x", saved.getPasswordHash())).isTrue();
        assertThat(saved.getRoles()).extracting(Role::getCode).containsExactly(RoleCode.CUSTOMER);
    }

    @Test
    void neverGrantsAdminEvenWhenTheClientAsksForIt() throws Exception {
        register("""
                {"email":"sneaky@example.com","password":"Password1!x","fullName":"Sneaky",
                 "roles":["ADMIN"],"role":"ADMIN","status":"SUSPENDED","emailVerified":true}
                """);

        User saved = userRepository.findByEmailIgnoreCase("sneaky@example.com").orElseThrow();
        assertThat(saved.getRoles()).extracting(Role::getCode).containsExactly(RoleCode.CUSTOMER);
        assertThat(saved.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(saved.isEmailVerified()).isFalse();
    }

    @Test
    void issuesAnAccessTokenCarryingTheCustomerRole() throws Exception {
        MvcResult result = register(validRegistration("ada@example.com"));
        String accessToken = readJson(result).get("accessToken").asText();

        AuthenticatedUser principal = tokenService.parseAccessToken(accessToken);

        assertThat(principal.email()).isEqualTo("ada@example.com");
        assertThat(principal.roles()).containsExactly("CUSTOMER");
    }

    @Test
    void rejectsADuplicateEmailRegardlessOfCase() throws Exception {
        register(validRegistration("ada@example.com"));

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRegistration("ADA@Example.COM")))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Conflict"))
                .andExpect(jsonPath("$.detail").value("Email is already registered"));

        assertThat(userRepository.count()).isEqualTo(1);
    }

    @Test
    void rejectsInvalidRegistrationFieldsWithFieldLevelErrors() throws Exception {
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"not-an-email","password":"short","fullName":""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors[?(@.field == 'email')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'password')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'fullName')]").exists());

        assertThat(userRepository.count()).isZero();
    }

    @Test
    void rejectsAPasswordLongerThanBcryptCanHash() throws Exception {
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"ada@example.com","password":"%s","fullName":"Ada"}
                                """.formatted("a".repeat(73))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'password')]").exists());
    }

    @Test
    void logsInWithValidCredentials() throws Exception {
        testData.createCustomer("ada@example.com", "Password1!x");

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"ADA@example.com","password":"Password1!x"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty());

        assertThat(authEventRepository.findAllByEventType(AuthEventType.LOGIN_SUCCESS)).hasSize(1);
    }

    @Test
    void returnsAnIdenticalResponseForAnUnknownEmailAndAWrongPassword() throws Exception {
        testData.createCustomer("ada@example.com", "Password1!x");

        String wrongPassword = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"ada@example.com","password":"NotThePassword1"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andReturn().getResponse().getContentAsString();

        String unknownEmail = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"nobody@example.com","password":"NotThePassword1"}
                                """))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(unknownEmail).isEqualTo(wrongPassword);
        assertThat(readJson(wrongPassword).get("detail").asText())
                .isEqualTo("Email or password is incorrect");
    }

    @Test
    void auditsFailedLoginsForBothKnownAndUnknownEmails() throws Exception {
        testData.createCustomer("ada@example.com", "Password1!x");

        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"ada@example.com","password":"NotThePassword1"}
                        """));
        mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"nobody@example.com","password":"NotThePassword1"}
                        """));

        // Both rows must have survived the rollback that produced the 401.
        //
        // Read the FK column rather than AuthEvent.getUser(): the association is LAZY, so outside a
        // session the getter hands back a detached proxy. Nothing here would initialise it, but
        // AssertJ renders the actual value when isNull() fails on the row that does have a user, and
        // that render calls toString() on the proxy -> LazyInitializationException instead of the
        // assertion result you wanted.
        List<Map<String, Object>> failures = jdbcTemplate.queryForList(
                "SELECT user_id FROM auth_events WHERE event_type = 'LOGIN_FAILURE'");

        assertThat(failures)
                .hasSize(2)
                .anySatisfy(row -> assertThat(row.get("user_id")).isNotNull())
                .anySatisfy(row -> assertThat(row.get("user_id")).isNull());
    }

    @Test
    void refusesToLogInASuspendedUserWithTheSameGenericResponse() throws Exception {
        testData.createCustomer("suspended@example.com", "Password1!x", UserStatus.SUSPENDED);

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"suspended@example.com","password":"Password1!x"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Email or password is incorrect"));
    }

    @Test
    void requiresAuthenticationToLogOut() throws Exception {
        mockMvc.perform(post("/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"anything"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));
    }

    @Test
    void logsOutAndRevokesThePresentedRefreshToken() throws Exception {
        MvcResult registered = register(validRegistration("ada@example.com"));
        JsonNode tokens = readJson(registered);

        mockMvc.perform(post("/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.get("accessToken").asText())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}
                                """.formatted(tokens.get("refreshToken").asText())))
                .andExpect(status().isNoContent());

        assertThat(activeRefreshTokenCount()).isZero();
        assertThat(authEventRepository.findAllByEventType(AuthEventType.LOGOUT)).hasSize(1);
    }

    @Test
    void cannotLogOutSomebodyElsesSession() throws Exception {
        JsonNode victim = readJson(register(validRegistration("victim@example.com")));
        JsonNode attacker = readJson(register(validRegistration("attacker@example.com")));

        mockMvc.perform(post("/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION,
                                "Bearer " + attacker.get("accessToken").asText())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}
                                """.formatted(victim.get("refreshToken").asText())))
                .andExpect(status().isNoContent());

        // The victim's session is untouched; only the attacker's own (unrevoked) one remains too.
        assertThat(activeRefreshTokenCount()).isEqualTo(2);
    }

    private int activeRefreshTokenCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE revoked_at IS NULL", Integer.class);
        return count == null ? 0 : count;
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode readJson(String json) throws Exception {
        return objectMapper.readTree(json);
    }
}
