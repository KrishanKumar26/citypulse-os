package com.citypulse.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Delivers transactional mail over SMTP.
 *
 * <p>Registered only when a mail host is configured. Without one the platform
 * keeps {@link LoggingEmailSender}, which says plainly in every log line that
 * nothing was delivered — the failure this whole arrangement exists to avoid is
 * a product that behaves as though mail went out when it did not.
 *
 * <p>Plain text, not HTML. These are two links and a sentence; an HTML template
 * adds a rendering surface, an image-blocking problem and a spam signal, for a
 * message whose entire content is a URL the recipient must click.
 *
 * <p>A send failure is logged and swallowed rather than propagated. The link is
 * already stored and the account already exists; turning a provider outage into
 * a failed signup would lose the account as well as the mail, and the user can
 * request another link. What must never happen is the caller being told the
 * message was delivered — {@link #deliversMail()} answers whether this
 * implementation delivers at all, and the API's wording follows it.
 */
public class SmtpEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailSender.class);

    private final JavaMailSender mailSender;
    private final String fromAddress;
    private final String productName;

    public SmtpEmailSender(JavaMailSender mailSender, String fromAddress, String productName) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
        this.productName = productName;
    }

    @Override
    public void sendEmailVerification(String toEmail, String recipientName, String verificationLink) {
        send(toEmail,
                productName + " — confirm your email address",
                """
                Hello %s,

                Confirm your email address to finish setting up your %s account:

                %s

                If you did not create this account, ignore this message and nothing
                will happen — the link expires on its own.
                """.formatted(recipientName, productName, verificationLink));
    }

    @Override
    public void sendPasswordReset(String toEmail, String recipientName, String resetLink) {
        send(toEmail,
                productName + " — reset your password",
                """
                Hello %s,

                Use this link to set a new %s password:

                %s

                If you did not ask for this, ignore the message. Your current
                password stays valid and the link expires on its own.
                """.formatted(recipientName, productName, resetLink));
    }

    private void send(String to, String subject, String body) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);

        try {
            mailSender.send(message);
            // The address is not logged. A log line naming who was sent a reset
            // link tells anyone reading logs which accounts exist and who is
            // trying to get into them.
            log.info("Sent {} mail", subject.contains("reset") ? "password-reset" : "verification");
        } catch (MailException ex) {
            // Swallowed on purpose — see the class note. The account exists and
            // the link is stored; losing the signup to a provider outage would
            // be worse than losing the message.
            log.error("Mail delivery failed for a {} message: {}",
                    subject.contains("reset") ? "password-reset" : "verification", ex.getMessage());
        }
    }

    @Override
    public boolean deliversMail() {
        return true;
    }
}
