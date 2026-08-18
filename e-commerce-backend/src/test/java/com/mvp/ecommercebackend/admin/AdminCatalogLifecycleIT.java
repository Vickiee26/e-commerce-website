package com.mvp.ecommercebackend.admin;

import com.mvp.ecommercebackend.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Acceptance criterion 1: an administrator can build a category, a category type, a product, a
 * variant, and an image through the API alone, and a customer then sees that product.
 *
 * <p>Separate from the per-endpoint tests on purpose. Each of those proves one endpoint works; none
 * of them proves that a product assembled entirely through the admin API is visible to a shopper,
 * which is the requirement. No test data factory is used here — using one would skip the very code
 * path under test.
 */
class AdminCatalogLifecycleIT extends AbstractIntegrationTest {

    private String admin;

    @BeforeEach
    void setUp() {
        admin = bearer(testData.createAdmin("catalog-admin@example.com", "correct-horse-battery"));
    }

    @Test
    void buildsAProductThroughTheApiAndShowsItToACustomer() throws Exception {
        UUID categoryId = idOf(post("/api/admin/categories", """
                {"name":"Footwear","code":"footwear","description":"Shoes and boots"}
                """));

        UUID categoryTypeId = idOf(post("/api/admin/categories/" + categoryId + "/types", """
                {"name":"Running Shoes","code":"running","description":"For the road"}
                """));

        UUID productId = idOf(post("/api/admin/products", """
                {"name":"Trail Runner","description":"Grippy and light","price":129.99,
                 "categoryId":"%s","categoryTypeId":"%s"}
                """.formatted(categoryId, categoryTypeId)));

        idOf(post("/api/admin/products/" + productId + "/variants", """
                {"color":"Black","size":"42","stockQuantity":7}
                """));

        idOf(post("/api/admin/products/" + productId + "/resources", """
                {"name":"Hero shot","url":"https://cdn.example.com/trail-runner.jpg",
                 "type":"IMAGE","isPrimary":true}
                """));

        // The customer's view, with no token at all.
        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(productId.toString()))
                .andExpect(jsonPath("$.content[0].name").value("Trail Runner"))
                .andExpect(jsonPath("$.content[0].thumbnail")
                        .value("https://cdn.example.com/trail-runner.jpg"));

        mockMvc.perform(get("/api/products/" + productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Trail Runner"))
                .andExpect(jsonPath("$.categoryName").value("Footwear"))
                .andExpect(jsonPath("$.variants.length()").value(1))
                .andExpect(jsonPath("$.variants[0].color").value("Black"))
                .andExpect(jsonPath("$.variants[0].stockQuantity").value(7))
                .andExpect(jsonPath("$.productResources.length()").value(1));

        mockMvc.perform(get("/api/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("footwear"))
                .andExpect(jsonPath("$[0].types[0].code").value("running"));

        // And the whole build is on the record: category, type, product, variant, resource.
        mockMvc.perform(get("/api/admin/events").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(5));
    }

    private UUID idOf(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b)
            throws Exception {
        String body = mockMvc.perform(b)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("id").asText());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder post(
            String path, String json) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                .header(HttpHeaders.AUTHORIZATION, admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);
    }
}
