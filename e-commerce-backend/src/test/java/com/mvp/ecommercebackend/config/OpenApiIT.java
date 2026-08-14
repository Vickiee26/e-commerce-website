package com.mvp.ecommercebackend.config;

import com.mvp.ecommercebackend.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Acceptance criterion 6: every endpoint appears in the OpenAPI document.
 *
 * <p>An endpoint missing from the document is not a cosmetic problem — it is the difference between
 * a client author knowing an operation exists and not knowing. The path list below is therefore
 * asserted exactly, so adding a controller method without documenting it fails the build.
 */
class OpenApiIT extends AbstractIntegrationTest {

    /** Every path this API serves. Update deliberately: an exact match is the point of the test. */
    private static final List<String> EVERY_PATH = List.of(
            "/auth/register",
            "/auth/login",
            "/auth/refresh",
            "/auth/logout",
            "/auth/password-reset/request",
            "/auth/password-reset/confirm",
            "/api/products",
            "/api/products/{id}",
            "/api/categories",
            "/api/me",
            "/api/me/addresses",
            "/api/me/addresses/{id}",
            "/api/me/cart",
            "/api/me/cart/items",
            "/api/me/cart/items/{itemId}",
            "/api/me/orders",
            "/api/me/orders/{orderId}",
            "/api/me/orders/{orderId}/pay",
            "/api/me/orders/{orderId}/cancel",
            "/api/admin/categories",
            "/api/admin/categories/{id}",
            "/api/admin/categories/{id}/types",
            "/api/admin/category-types/{id}");

    @Test
    void publishesTheApiDocumentWithoutATokenSoSwaggerUiCanLoadIt() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("ShopFlow API"))
                .andExpect(jsonPath("$.info.version").value("v1"));
    }

    @Test
    void servesSwaggerUiAnonymously() throws Exception {
        // springdoc redirects the configured path to its bundled index page. Either is fine; a 401
        // or 403 is not, because the docs are useless behind the login they document.
        int status = mockMvc.perform(get("/swagger-ui")).andReturn().getResponse().getStatus();
        assertThat(status).isIn(200, 301, 302, 307, 308);
    }

    @Test
    void documentsEveryEndpoint() throws Exception {
        assertThat(paths().propertyNames()).containsExactlyInAnyOrderElementsOf(EVERY_PATH);
    }

    @Test
    void documentsEveryMethodOfTheCartAndOrderEndpoints() throws Exception {
        JsonNode paths = paths();

        assertThat(paths.get("/api/me/cart").propertyNames()).containsExactlyInAnyOrder("get", "delete");
        assertThat(paths.get("/api/me/cart/items").propertyNames()).containsExactly("post");
        assertThat(paths.get("/api/me/cart/items/{itemId}").propertyNames())
                .containsExactlyInAnyOrder("patch", "delete");
        assertThat(paths.get("/api/me/orders").propertyNames())
                .containsExactlyInAnyOrder("get", "post");
        assertThat(paths.get("/api/me/orders/{orderId}").propertyNames()).containsExactly("get");
        assertThat(paths.get("/api/me/orders/{orderId}/pay").propertyNames()).containsExactly("post");
        assertThat(paths.get("/api/me/orders/{orderId}/cancel").propertyNames())
                .containsExactly("post");
    }

    @Test
    void declaresTheBearerScheme() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.bearerFormat")
                        .value("JWT"));
    }

    @Test
    void marksTheCallersOwnResourcesAsNeedingAToken() throws Exception {
        JsonNode paths = paths();

        assertThat(requiredSchemesOf(paths, "/api/me", "get")).containsExactly("bearerAuth");
        assertThat(requiredSchemesOf(paths, "/api/me/addresses", "post")).containsExactly("bearerAuth");
        assertThat(requiredSchemesOf(paths, "/api/me/cart/items", "post")).containsExactly("bearerAuth");
        assertThat(requiredSchemesOf(paths, "/api/me/orders", "post")).containsExactly("bearerAuth");
        assertThat(requiredSchemesOf(paths, "/auth/logout", "post")).containsExactly("bearerAuth");
    }

    /**
     * The documentation has to agree with the filter chain: browsing is anonymous, so the catalogue
     * operations must not be shown behind a token.
     */
    @Test
    void leavesCatalogueBrowsingUnauthenticatedInTheDocument() throws Exception {
        JsonNode paths = paths();

        assertThat(requiredSchemesOf(paths, "/api/products", "get")).isEmpty();
        assertThat(requiredSchemesOf(paths, "/api/products/{id}", "get")).isEmpty();
        assertThat(requiredSchemesOf(paths, "/api/categories", "get")).isEmpty();
        assertThat(requiredSchemesOf(paths, "/auth/register", "post")).isEmpty();
        assertThat(requiredSchemesOf(paths, "/auth/login", "post")).isEmpty();
    }

    @Test
    void groupsTheOperationsUnderReadableTags() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/me/orders'].post.tags[0]").value("Orders"))
                .andExpect(jsonPath("$.paths['/api/me/cart'].get.tags[0]").value("Cart"))
                .andExpect(jsonPath("$.paths['/api/products'].get.tags[0]").value("Catalog"))
                .andExpect(jsonPath("$.paths['/auth/login'].post.tags[0]").value("Authentication"));
    }

    /** Names of the security schemes an operation requires; empty when it is public. */
    private static List<String> requiredSchemesOf(JsonNode paths, String path, String method) {
        JsonNode security = paths.get(path).get(method).get("security");
        if (security == null) {
            return List.of();
        }
        return security.valueStream()
                .flatMap(requirement -> requirement.propertyNames().stream())
                .toList();
    }

    private JsonNode paths() throws Exception {
        String document = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(document).get("paths");
    }
}
