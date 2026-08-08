package com.citypulse.notification;

import com.citypulse.common.config.SecurityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Chooses how mail leaves the platform, and refuses one combination outright.
 */
@Configuration
public class NotificationConfig {

    private static final Logger log = LoggerFactory.getLogger(NotificationConfig.class);

    /**
     * The real sender, when a mail host is configured.
     *
     * <p>Keyed on {@code spring.mail.host} because that is the setting that
     * actually determines whether mail can leave — a separate "email enabled"
     * flag would be a second source of truth that can disagree with the first.
     */
    @Bean
    @ConditionalOnProperty(name = "spring.mail.host")
    public EmailSender smtpEmailSender(
            JavaMailSender mailSender,
            @Value("${citypulse.notification.from:no-reply@citypulse.local}") String fromAddress,
            @Value("${citypulse.app.name:CityPulse OS}") String productName) {
        log.info("Mail: SMTP sender active, from {}", fromAddress);
        return new SmtpEmailSender(mailSender, fromAddress, productName);
    }

    /**
     * Fallback. Writes the link to the log and delivers nothing, saying so in
     * every line it writes.
     */
    @Bean
    @ConditionalOnMissingBean(EmailSender.class)
    public EmailSender loggingEmailSender() {
        log.warn("Mail: no provider configured. Verification and reset links will be written to "
                + "this log and delivered to nobody. Set spring.mail.host to change that.");
        return new LoggingEmailSender();
    }

    /**
     * Refuses to start when verification is required but nothing can deliver it.
     *
     * <p>These are two settings that must agree and are edited in different
     * places. Required verification with no mail provider locks every new
     * account out permanently: the user is told to check an inbox, and the link
     * is sitting in a server log they cannot read. Nothing about that failure is
     * visible from the outside — signup succeeds, and support hears about it
     * days later.
     *
     * <p>Failing at startup is deliberately loud. The alternative is a
     * deployment that looks healthy and quietly cannot onboard anyone.
     */
    @Bean
    public MailConfigurationCheck mailConfigurationCheck(EmailSender sender,
                                                         SecurityProperties securityProperties) {
        boolean required = securityProperties.signup().requireEmailVerification();
        if (required && !sender.deliversMail()) {
            throw new IllegalStateException("""
                    Email verification is required but no mail provider is configured.
                    Every new account would be locked out: the user is told to check \
                    their inbox while the link goes to this log.
                    Either set spring.mail.host, or set \
                    CITYPULSE_REQUIRE_EMAIL_VERIFICATION=false.""");
        }
        if (!required && sender.deliversMail()) {
            log.warn("Mail is configured but email verification is not required. New accounts are "
                    + "active immediately; set CITYPULSE_REQUIRE_EMAIL_VERIFICATION=true to change "
                    + "that.");
        }
        return new MailConfigurationCheck();
    }

    /** Marker for the startup check above; holds nothing. */
    public static final class MailConfigurationCheck {
    }
}
