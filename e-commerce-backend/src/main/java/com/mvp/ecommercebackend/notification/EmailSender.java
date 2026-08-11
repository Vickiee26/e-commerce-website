package com.mvp.ecommercebackend.notification;

/**
 * Outbound email. Narrow on purpose: one method per message the application actually sends, so a
 * real provider can be dropped in without every caller learning about templates or headers.
 */
public interface EmailSender {

    void sendPasswordReset(String recipientEmail, String rawToken);
}
