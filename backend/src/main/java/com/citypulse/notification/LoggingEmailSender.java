package com.citypulse.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Development stand-in for {@link EmailSender}. Writes the link to the log so a
 * developer can complete the flow locally, and delivers no mail.
 *
 * <p>Registered by {@link NotificationConfig} only when no other
 * {@code EmailSender} bean exists, so adding a real provider replaces it with no
 * change to the auth module.
 */
public class LoggingEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    @Override
    public void sendEmailVerification(String toEmail, String recipientName, String verificationLink) {
        log.warn("""

                ================= EMAIL NOT DELIVERED (no mail provider configured) =================
                Type      : EMAIL VERIFICATION
                Recipient : {} <{}>
                Link      : {}
                Copy the link above to complete verification in local development.
                =====================================================================================""",
                recipientName, toEmail, verificationLink);
    }

    @Override
    public void sendPasswordReset(String toEmail, String recipientName, String resetLink) {
        log.warn("""

                ================= EMAIL NOT DELIVERED (no mail provider configured) =================
                Type      : PASSWORD RESET
                Recipient : {} <{}>
                Link      : {}
                Copy the link above to complete the reset in local development.
                =====================================================================================""",
                recipientName, toEmail, resetLink);
    }

    @Override
    public boolean deliversMail() {
        return false;
    }
}
