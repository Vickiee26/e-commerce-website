package com.mvp.ecommercebackend.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The document served at {@code /v3/api-docs} and rendered at {@code /swagger-ui}.
 *
 * <p>springdoc discovers the endpoints themselves from the controllers; this bean supplies what it
 * cannot infer — the title, and the bearer scheme that gives Swagger UI its Authorize button.
 *
 * <p>The scheme is declared here but deliberately not applied globally. Which endpoints need a token
 * is a security fact worth stating exactly, so the authenticated endpoints carry
 * {@code @SecurityRequirement} individually and browsing the catalogue shows no lock at all.
 */
@Configuration
public class OpenApiConfig {

    /** Referenced by name from every {@code @SecurityRequirement} in the controllers. */
    public static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    OpenAPI shopFlowOpenApi() {
        SecurityScheme bearer = new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .description("Paste the accessToken returned by POST /auth/login.");

        return new OpenAPI()
                .info(new Info()
                        .title("ShopFlow API")
                        .version("v1")
                        .description("""
                                Authentication, profiles, addresses, catalogue browsing, cart and \
                                checkout. Catalogue reads are anonymous; everything under /api/me \
                                needs a bearer token. Errors are RFC 7807 application/problem+json."""))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME, bearer));
    }
}
