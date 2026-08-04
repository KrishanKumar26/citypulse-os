package com.citypulse.notification;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class NotificationConfig {

    /**
     * Fallback sender. Supplying any other {@link EmailSender} bean — an SES or
     * SMTP implementation — takes precedence automatically.
     */
    @Bean
    @ConditionalOnMissingBean(EmailSender.class)
    public EmailSender loggingEmailSender() {
        return new LoggingEmailSender();
    }
}
