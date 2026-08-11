package com.mvp.ecommercebackend.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Development stub: writes what would have been emailed to the log.
 *
 * <p>Logging the raw token is the entire point locally — it is the only way to complete a reset
 * without a mail provider — and is a credential leak anywhere else. {@code app.email.log-tokens} is
 * therefore {@code false} in the {@code prod} profile, which reduces this to a delivery record.
 * Replacing this bean with a real provider is the notifications slice's job.
 */
@Component
public class LoggingEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    private final boolean logTokens;

    public LoggingEmailSender(@Value("${app.email.log-tokens:false}") boolean logTokens) {
        this.logTokens = logTokens;
        if (logTokens) {
            log.warn("Password reset tokens will be written to the log. "
                    + "This is for local development only.");
        }
    }

    @Override
    public void sendPasswordReset(String recipientEmail, String rawToken) {
        if (logTokens) {
            log.info("Password reset for {}: token={}", recipientEmail, rawToken);
        } else {
            log.info("Password reset email suppressed for {} (no mail provider configured)",
                    recipientEmail);
        }
    }
}
