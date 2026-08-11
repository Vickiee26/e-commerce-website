package com.mvp.ecommercebackend.auth;

import com.mvp.ecommercebackend.auth.entity.AuthEventType;
import com.mvp.ecommercebackend.auth.repository.AuthEventRepository;
import com.mvp.ecommercebackend.auth.repository.UserRepository;
import com.mvp.ecommercebackend.notification.EmailSender;
import com.mvp.ecommercebackend.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PasswordResetIT extends AbstractIntegrationTest {

    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String OLD_PASSWORD = "OldPassword1!";
    private static final String NEW_PASSWORD = "BrandNewPassword1!";

    /** Captures what the real sender would have delivered. */
    static class RecordingEmailSender implements EmailSender {

        record Sent(String recipient, String rawToken) {
        }

        final List<Sent> sent = new ArrayList<>();

        @Override
        public void sendPasswordReset(String recipientEmail, String rawToken) {
            sent.add(new Sent(recipientEmail, rawToken));
        }
    }

    @TestConfiguration
    static class EmailConfig {

        @Bean
        @Primary
        RecordingEmailSender recordingEmailSender() {
            return new RecordingEmailSender();
        }
    }

    @Autowired
    private RecordingEmailSender emailSender;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuthEventRepository authEventRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void clearSentMail() {
        emailSender.sent.clear();
    }

    private org.springframework.test.web.servlet.ResultActions requestReset(String email)
            throws Exception {
        return mockMvc.perform(post("/auth/password-reset/request")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s"}
                        """.formatted(email)));
    }

    private org.springframework.test.web.servlet.ResultActions confirmReset(String token,
                                                                            String newPassword)
            throws Exception {
        return mockMvc.perform(post("/auth/password-reset/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"token":"%s","newPassword":"%s"}
                        """.formatted(token, newPassword)));
    }

    private org.springframework.test.web.servlet.ResultActions login(String email, String password)
            throws Exception {
        return mockMvc.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"%s","password":"%s"}
                        """.formatted(email, password)));
    }

    @Test
    void issuesASingleUseTokenForAKnownEmail() throws Exception {
        testData.createCustomer("ada@example.com", OLD_PASSWORD);

        requestReset("ADA@Example.com").andExpect(status().isAccepted());

        assertThat(emailSender.sent).singleElement().satisfies(sent -> {
            assertThat(sent.recipient()).isEqualTo("ada@example.com");
            assertThat(sent.rawToken()).isNotBlank();
        });
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM password_reset_tokens", Integer.class)).isEqualTo(1);
        assertThat(authEventRepository.findAllByEventType(AuthEventType.PASSWORD_RESET_REQUESTED))
                .hasSize(1);
    }

    @Test
    void storesTheResetTokenOnlyAsAHash() throws Exception {
        testData.createCustomer("ada@example.com", OLD_PASSWORD);
        requestReset("ada@example.com").andExpect(status().isAccepted());
        String raw = emailSender.sent.get(0).rawToken();

        String storedHash = jdbcTemplate.queryForObject(
                "SELECT token_hash FROM password_reset_tokens", String.class);

        assertThat(storedHash).isNotEqualTo(raw).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void answersAnUnknownEmailExactlyAsItAnswersAKnownOne() throws Exception {
        testData.createCustomer("ada@example.com", OLD_PASSWORD);

        String known = requestReset("ada@example.com")
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        emailSender.sent.clear();

        String unknown = requestReset("nobody@example.com")
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        assertThat(unknown).isEqualTo(known);
        // No token, no email, no audit row that would confirm the address is unused.
        assertThat(emailSender.sent).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM password_reset_tokens", Integer.class)).isEqualTo(1);
    }

    @Test
    void invalidatesAnEarlierTokenWhenANewOneIsRequested() throws Exception {
        testData.createCustomer("ada@example.com", OLD_PASSWORD);
        requestReset("ada@example.com").andExpect(status().isAccepted());
        String first = emailSender.sent.get(0).rawToken();

        requestReset("ada@example.com").andExpect(status().isAccepted());
        String second = emailSender.sent.get(emailSender.sent.size() - 1).rawToken();

        confirmReset(first, NEW_PASSWORD).andExpect(status().isUnauthorized());
        confirmReset(second, NEW_PASSWORD).andExpect(status().isNoContent());
    }

    @Test
    void confirmChangesThePasswordAndRevokesEverySession() throws Exception {
        testData.createCustomer("ada@example.com", OLD_PASSWORD);
        login("ada@example.com", OLD_PASSWORD).andExpect(status().isOk());
        login("ada@example.com", OLD_PASSWORD).andExpect(status().isOk());
        requestReset("ada@example.com");
        String token = emailSender.sent.get(0).rawToken();

        confirmReset(token, NEW_PASSWORD).andExpect(status().isNoContent());

        assertThat(passwordEncoder.matches(NEW_PASSWORD,
                userRepository.findByEmailIgnoreCase("ada@example.com").orElseThrow()
                        .getPasswordHash())).isTrue();
        // A reset implies possible compromise, so both prior sessions must be dead.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE revoked_at IS NULL", Integer.class))
                .isZero();
        assertThat(authEventRepository.findAllByEventType(AuthEventType.PASSWORD_RESET_COMPLETED))
                .hasSize(1);
    }

    @Test
    void theOldPasswordStopsWorkingAndTheNewOneStarts() throws Exception {
        testData.createCustomer("ada@example.com", OLD_PASSWORD);
        requestReset("ada@example.com");

        confirmReset(emailSender.sent.get(0).rawToken(), NEW_PASSWORD)
                .andExpect(status().isNoContent());

        login("ada@example.com", OLD_PASSWORD).andExpect(status().isUnauthorized());
        login("ada@example.com", NEW_PASSWORD).andExpect(status().isOk());
    }

    @Test
    void aResetTokenWorksExactlyOnce() throws Exception {
        testData.createCustomer("ada@example.com", OLD_PASSWORD);
        requestReset("ada@example.com");
        String token = emailSender.sent.get(0).rawToken();

        confirmReset(token, NEW_PASSWORD).andExpect(status().isNoContent());

        confirmReset(token, "YetAnotherPassword1!")
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Password reset token is not valid"));
        login("ada@example.com", "YetAnotherPassword1!").andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsAnExpiredResetToken() throws Exception {
        testData.createCustomer("ada@example.com", OLD_PASSWORD);
        requestReset("ada@example.com");
        String token = emailSender.sent.get(0).rawToken();
        // Bind an OffsetDateTime, not an Instant: pgjdbc has no parameter mapping for Instant and
        // fails with "Can't infer the SQL type to use for an instance of java.time.Instant". Reading
        // a timestamptz back into an Instant is fine — Spring converts on the way out, not in.
        jdbcTemplate.update("UPDATE password_reset_tokens SET expires_at = ?",
                Instant.now().minus(1, ChronoUnit.MINUTES).atOffset(ZoneOffset.UTC));

        confirmReset(token, NEW_PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Password reset token is not valid"));
    }

    @Test
    void issuesResetTokensThatExpireWithinTheHour() throws Exception {
        testData.createCustomer("ada@example.com", OLD_PASSWORD);

        requestReset("ada@example.com").andExpect(status().isAccepted());

        Instant expiresAt = jdbcTemplate.queryForObject(
                "SELECT expires_at FROM password_reset_tokens", Instant.class);
        assertThat(expiresAt)
                .isAfter(Instant.now().plus(50, ChronoUnit.MINUTES))
                .isBefore(Instant.now().plus(70, ChronoUnit.MINUTES));
    }

    @Test
    void rejectsAnUnknownResetToken() throws Exception {
        confirmReset("never-issued", NEW_PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Password reset token is not valid"));
    }

    @Test
    void rejectsAWeakNewPassword() throws Exception {
        testData.createCustomer("ada@example.com", OLD_PASSWORD);
        requestReset("ada@example.com");

        confirmReset(emailSender.sent.get(0).rawToken(), "short")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'newPassword')]").exists());

        // The rejected attempt must not have consumed the token.
        confirmReset(emailSender.sent.get(0).rawToken(), NEW_PASSWORD)
                .andExpect(status().isNoContent());
    }

    @Test
    void rejectsAMalformedResetRequest() throws Exception {
        mockMvc.perform(post("/auth/password-reset/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"not-an-email"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'email')]").exists());
    }

    @Test
    void doesNotIssueResetTokensForSuspendedAccounts() throws Exception {
        testData.createCustomer("suspended@example.com", OLD_PASSWORD,
                com.mvp.ecommercebackend.auth.entity.UserStatus.SUSPENDED);

        requestReset("suspended@example.com").andExpect(status().isAccepted());

        assertThat(emailSender.sent).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM password_reset_tokens", Integer.class)).isZero();
    }
}
