package com.citypulse.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    private final AppProperties appProperties;

    public OpenApiConfig(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    @Bean
    public OpenAPI cityPulseOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title(appProperties.name() + " API")
                        .version("v1")
                        .description("""
                                Urban intelligence platform API.

                                **Response envelope.** Every response uses the same shape:
                                `{ "success": true, "data": {...}, "message": "..." }` on success and
                                `{ "success": false, "error": { "code": "...", "message": "..." } }` on failure.

                                **Authentication.** Sign in at `/api/v1/auth/login` and send the access
                                token as `Authorization: Bearer <token>`. Access tokens last 15 minutes;
                                exchange the refresh token at `/api/v1/auth/refresh` to obtain a new pair.
                                Refresh tokens are single-use — presenting a consumed one revokes the
                                whole session family.

                                **Authorization.** Endpoints require fine-grained permissions such as
                                `city:read`. A missing permission returns 403.

                                **Demo data.** Responses carrying city telemetry include a `demoData`
                                flag. When true, the values are synthetic and must not be presented as
                                live real-world information.
                                """)
                        .contact(new Contact().name("CityPulse OS"))
                        .license(new License().name("Proprietary")))
                .servers(List.of(new Server().url("/").description("Current host")))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Access token issued by /api/v1/auth/login")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
