package com.citypulse.notification;

/**
 * Outbound transactional email.
 *
 * <p>No delivery provider is configured in this environment, so the only
 * implementation is {@link LoggingEmailSender}, which writes the message to the
 * application log and delivers nothing. The interface exists so that adding SES
 * or SMTP later is a new bean plus a config property, with no change to the auth
 * module (PRD §29 of the execution prompt).
 *
 * <p>This is stated plainly wherever it matters: the API tells the caller that a
 * verification or reset link was <em>generated</em>, and the setup documentation
 * explains how to retrieve it from the log in local development. Nothing in the
 * product claims an email was sent when it was not.
 */
public interface EmailSender {

    void sendEmailVerification(String toEmail, String recipientName, String verificationLink);

    void sendPasswordReset(String toEmail, String recipientName, String resetLink);

    /**
     * @return false when this implementation does not actually deliver mail, so
     *         callers can shape their response honestly instead of implying
     *         delivery
     */
    boolean deliversMail();
}
