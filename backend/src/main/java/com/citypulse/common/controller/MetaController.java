package com.citypulse.common.controller;

import com.citypulse.common.api.ApiResponse;
import com.citypulse.common.config.AppProperties;
import com.citypulse.notification.EmailSender;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public platform metadata. Unauthenticated, because the landing page and the
 * sign-in screen need it before a session exists.
 *
 * <p>Exposes only facts that are already observable, and deliberately reports
 * the two things the UI must not misrepresent: whether the data on display is
 * synthetic, and whether email is actually delivered.
 */
@RestController
@RequestMapping("/api/v1/meta")
@Tag(name = "Platform", description = "Public platform metadata")
public class MetaController {

    private final AppProperties appProperties;
    private final EmailSender emailSender;
    private final String version;

    public MetaController(AppProperties appProperties,
                          EmailSender emailSender,
                          @Value("${citypulse.version:0.1.0}") String version) {
        this.appProperties = appProperties;
        this.emailSender = emailSender;
        this.version = version;
    }

    @GetMapping("/platform")
    @Operation(summary = "Platform capabilities and data provenance",
            description = "Public. The UI uses demoMode to label synthetic data, and "
                          + "emailDeliveryEnabled to avoid claiming an email was sent when it was not.")
    public ResponseEntity<ApiResponse<PlatformInfo>> platform() {
        return ResponseEntity.ok(ApiResponse.ok(new PlatformInfo(
                appProperties.name(),
                version,
                appProperties.demoMode(),
                emailSender.deliversMail()
        )));
    }

    /**
     * @param demoMode             true when city telemetry is synthetic; the UI must
     *                             label it rather than presenting it as live (PRD §42)
     * @param emailDeliveryEnabled false when no mail provider is configured, so the
     *                             UI tells the user where to find the generated link
     *                             instead of saying an email was sent
     */
    public record PlatformInfo(
            String name,
            String version,
            boolean demoMode,
            boolean emailDeliveryEnabled
    ) {
    }
}
