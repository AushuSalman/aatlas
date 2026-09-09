package com.aatlas.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 3.1, generated from the code.
 *
 * <p>This is what keeps the frontend's view models and the backend's DTOs from drifting:
 * the Next.js client is generated from {@code /v3/api-docs}, so a renamed field breaks the
 * TypeScript build rather than a production screen.
 *
 * <p>The groups mirror the blueprint's nineteen resource groups, so the docs page reads in
 * the same order as the API inventory.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI aatlasOpenApi() {
        SecurityScheme bearer = new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .description("Access token from POST /api/v1/auth/login. The tenant is a claim "
                        + "inside it and is never accepted from the URL or a header.");

        return new OpenAPI()
                .info(new Info()
                        .title("Aatlas API")
                        .version("v1")
                        .description("""
                                Decision intelligence for distributors: what to charge, who to buy \
                                from, and what it was worth.

                                Reads answer from precomputed snapshots, so a recommendation is a \
                                primary-key lookup rather than an engine run. Writes commit the row \
                                and its event together, then workers recompute what the event touched.
                                """)
                        .contact(new Contact().name("Aatlas engineering").email("engineering@aatlas.io"))
                        .license(new License().name("Proprietary")))
                .servers(List.of(
                        new Server().url("/").description("This instance"),
                        new Server().url("https://api.aatlas.io").description("Production")))
                .components(new Components().addSecuritySchemes("bearerAuth", bearer))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }

    @Bean
    GroupedOpenApi platformApi() {
        return GroupedOpenApi.builder()
                .group("1-platform")
                .displayName("Auth, tenant, users, policy")
                .pathsToMatch("/api/v1/auth/**", "/api/v1/me", "/api/v1/users/**", "/api/v1/roles/**",
                        "/api/v1/tenant/**", "/api/v1/guardrails/**", "/api/v1/reference/**")
                .build();
    }

    @Bean
    GroupedOpenApi dataApi() {
        return GroupedOpenApi.builder()
                .group("2-data")
                .displayName("Sources, imports, catalog, suppliers")
                .pathsToMatch("/api/v1/data-sources/**", "/api/v1/imports/**", "/api/v1/products/**",
                        "/api/v1/stores/**", "/api/v1/regions/**", "/api/v1/customers/**",
                        "/api/v1/suppliers/**")
                .build();
    }

    @Bean
    GroupedOpenApi decisionApi() {
        return GroupedOpenApi.builder()
                .group("3-decisions")
                .displayName("Sell, buy, RFQ, approvals, history")
                .pathsToMatch("/api/v1/sell/**", "/api/v1/buy/**", "/api/v1/rfqs/**",
                        "/api/v1/approvals/**", "/api/v1/decisions/**", "/api/v1/deals/**",
                        "/api/v1/history/**", "/api/v1/impact/**")
                .build();
    }

    @Bean
    GroupedOpenApi insightsApi() {
        return GroupedOpenApi.builder()
                .group("4-insights")
                .displayName("Overview, insights, analytics, forecast")
                .pathsToMatch("/api/v1/overview/**", "/api/v1/insights/**", "/api/v1/analytics/**",
                        "/api/v1/forecast/**", "/api/v1/elasticity/**")
                .build();
    }

    @Bean
    GroupedOpenApi platformOpsApi() {
        return GroupedOpenApi.builder()
                .group("5-operations")
                .displayName("Integrations, MCP, assistant, notifications, admin")
                .pathsToMatch("/api/v1/integrations/**", "/api/v1/mcp/**", "/api/v1/webhooks/**",
                        "/api/v1/assistant/**", "/api/v1/notifications/**", "/api/v1/search",
                        "/api/v1/audit/**", "/api/v1/admin/**")
                .build();
    }
}
