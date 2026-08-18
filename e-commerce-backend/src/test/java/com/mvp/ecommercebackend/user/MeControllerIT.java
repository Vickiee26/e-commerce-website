package com.mvp.ecommercebackend.user;

import com.mvp.ecommercebackend.auth.entity.User;
import com.mvp.ecommercebackend.auth.repository.UserRepository;
import com.mvp.ecommercebackend.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MeControllerIT extends AbstractIntegrationTest {

    private static final String PROBLEM_JSON = "application/problem+json";

    @Autowired
    private UserRepository userRepository;

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));

        mockMvc.perform(patch("/api/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"Nope"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returnsTheCallersOwnProfile() throws Exception {
        User user = testData.createCustomer("ada@example.com", "Password1!x");

        mockMvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(user.getId().toString()))
                .andExpect(jsonPath("$.email").value("ada@example.com"))
                .andExpect(jsonPath("$.fullName").value("Test CUSTOMER"))
                .andExpect(jsonPath("$.emailVerified").value(false))
                .andExpect(jsonPath("$.roles").value("CUSTOMER"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
    }

    @Test
    void neverExposesThePasswordHashOrStatusInternals() throws Exception {
        User user = testData.createCustomer("ada@example.com", "Password1!x");

        mockMvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.roles[0].id").doesNotExist());
    }

    @Test
    void showsAdminRolesForAdministrators() throws Exception {
        User admin = testData.createAdmin("admin@example.com", "Password1!x");

        mockMvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles").value("ADMIN"));
    }

    @Test
    void updatesTheFullNameOnly() throws Exception {
        User user = testData.createCustomer("ada@example.com", "Password1!x");
        jdbcTemplate.update("UPDATE users SET phone = ? WHERE id = ?", "+15550100", user.getId());

        mockMvc.perform(patch("/api/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"Ada Lovelace"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Ada Lovelace"))
                // An absent field means "leave unchanged", not "set to null".
                .andExpect(jsonPath("$.phone").value("+15550100"));
    }

    @Test
    void updatesThePhoneOnly() throws Exception {
        User user = testData.createCustomer("ada@example.com", "Password1!x");

        mockMvc.perform(patch("/api/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":"+15550199"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value("+15550199"))
                .andExpect(jsonPath("$.fullName").value("Test CUSTOMER"));
    }

    @Test
    void clearsThePhoneWhenGivenAnEmptyString() throws Exception {
        User user = testData.createCustomer("ada@example.com", "Password1!x");
        jdbcTemplate.update("UPDATE users SET phone = ? WHERE id = ?", "+15550100", user.getId());

        mockMvc.perform(patch("/api/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":""}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").doesNotExist());

        assertThat(userRepository.findById(user.getId()).orElseThrow().getPhone()).isNull();
    }

    @Test
    void rejectsABlankFullName() throws Exception {
        User user = testData.createCustomer("ada@example.com", "Password1!x");

        mockMvc.perform(patch("/api/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"   "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.errors[?(@.field == 'fullName')]").exists());
    }

    @Test
    void rejectsAnOverlongPhone() throws Exception {
        User user = testData.createCustomer("ada@example.com", "Password1!x");

        mockMvc.perform(patch("/api/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"phone":"%s"}
                                """.formatted("9".repeat(31))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'phone')]").exists());
    }

    @Test
    void ignoresFieldsTheClientIsNotAllowedToChange() throws Exception {
        User user = testData.createCustomer("ada@example.com", "Password1!x");

        mockMvc.perform(patch("/api/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"Ada","email":"hijack@example.com",
                                 "emailVerified":true,"status":"SUSPENDED","roles":["ADMIN"],
                                 "passwordHash":"$2a$10$aaaaaaaaaaaaaaaaaaaaaa"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("ada@example.com"))
                .andExpect(jsonPath("$.emailVerified").value(false))
                .andExpect(jsonPath("$.roles").value("CUSTOMER"));

        User reloaded = userRepository.findByEmailIgnoreCase("ada@example.com").orElseThrow();
        assertThat(reloaded.getPasswordHash()).startsWith("$2a$10$");
        assertThat(reloaded.isActive()).isTrue();
    }

    @Test
    void updatesOnlyTheCallersOwnRecord() throws Exception {
        User ada = testData.createCustomer("ada@example.com", "Password1!x");
        User grace = testData.createCustomer("grace@example.com", "Password1!x");

        mockMvc.perform(patch("/api/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer(ada))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"Ada Only"}
                                """))
                .andExpect(status().isOk());

        assertThat(userRepository.findById(grace.getId()).orElseThrow().getFullName())
                .isEqualTo("Test CUSTOMER");
    }

    @Test
    void trimsSurroundingWhitespaceFromTheName() throws Exception {
        User user = testData.createCustomer("ada@example.com", "Password1!x");

        mockMvc.perform(patch("/api/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"  Ada Lovelace  "}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Ada Lovelace"));
    }

    @Test
    void returnsNotFoundWhenTheTokenNamesADeletedUser() throws Exception {
        User user = testData.createCustomer("ada@example.com", "Password1!x");
        String token = bearer(user);
        jdbcTemplate.update("DELETE FROM user_roles WHERE user_id = ?", user.getId());
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", user.getId());

        // The signature is still valid, so the filter authenticates; the record is simply gone.
        mockMvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Not found"));
    }
}
