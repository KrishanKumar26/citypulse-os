package com.citypulse.notification;

import com.citypulse.common.config.SecurityProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The one configuration the platform refuses to start with.
 *
 * <p>Required verification and no mail provider locks every new account out
 * invisibly: signup succeeds, the user is told to check an inbox, and the link
 * sits in a server log they cannot read. Nothing about that is visible from the
 * outside, so it fails loudly at startup instead of quietly in production.
 */
@DisplayName("Mail configuration")
class MailConfigurationTest {

    private final NotificationConfig config = new NotificationConfig();

    /**
     * A real SecurityProperties, not a mock.
     *
     * <p>These are records, which Mockito cannot mock without the inline maker —
     * and constructing them is clearer anyway about what the check reads.
     */
    private SecurityProperties propertiesRequiring(boolean requireVerification) {
        return new SecurityProperties(
                null, null, null, null, null,
                new SecurityProperties.Signup(requireVerification, Duration.ofHours(24)),
                null);
    }

    /** A sender that answers honestly about whether it delivers, and does nothing else. */
    private EmailSender sender(boolean delivers) {
        return new EmailSender() {
            @Override
            public void sendEmailVerification(String to, String name, String link) {
            }

            @Override
            public void sendPasswordReset(String to, String name, String link) {
            }

            @Override
            public boolean deliversMail() {
                return delivers;
            }
        };
    }

    @Test
    @DisplayName("refuses to start when verification is required and nothing can deliver")
    void refusesRequiredVerificationWithoutAProvider() {
        assertThatThrownBy(() ->
                config.mailConfigurationCheck(sender(false), propertiesRequiring(true)))
                .isInstanceOf(IllegalStateException.class)
                // The message names both settings: whoever reads it should not
                // have to work out which two disagree.
                .hasMessageContaining("spring.mail.host")
                .hasMessageContaining("CITYPULSE_REQUIRE_EMAIL_VERIFICATION");
    }

    @Test
    @DisplayName("starts when verification is required and mail is configured")
    void allowsRequiredVerificationWithAProvider() {
        assertThatCode(() ->
                config.mailConfigurationCheck(sender(true), propertiesRequiring(true)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("starts without mail when verification is not required")
    void allowsNoProviderWhenVerificationIsOff() {
        // The default, and a working configuration rather than a broken one:
        // links go to the log, and every line says they were not delivered.
        assertThatCode(() ->
                config.mailConfigurationCheck(sender(false), propertiesRequiring(false)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the logging sender reports that it delivers nothing")
    void loggingSenderIsHonestAboutItself() {
        // Everything above rests on this answer being truthful — the startup
        // check and the API's wording both read it.
        assertThat(new LoggingEmailSender().deliversMail()).isFalse();
    }
}
