package com.mvp.ecommercebackend.admin;

import com.mvp.ecommercebackend.auth.entity.User;
import com.mvp.ecommercebackend.catalog.entity.CategoryType;
import com.mvp.ecommercebackend.catalog.entity.Product;
import com.mvp.ecommercebackend.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminResourceIT extends AbstractIntegrationTest {

    private String admin;
    private Product product;

    @BeforeEach
    void setUp() {
        User user = testData.createAdmin("resource-admin@example.com", "correct-horse-battery");
        admin = bearer(user);
        CategoryType type = catalogData.createCategoryWithType("Footwear", "Running Shoes");
        product = catalogData.createProduct(type, "Trail Runner", "129.99");
    }

    @Test
    void addsAnImageToAProduct() throws Exception {
        mockMvc.perform(post("/api/admin/products/" + product.getId() + "/resources")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"front.jpg","url":"https://cdn.example.com/front.jpg",
                                 "type":"IMAGE","isPrimary":true}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.url").value("https://cdn.example.com/front.jpg"))
                .andExpect(jsonPath("$.isPrimary").value(true));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT action FROM admin_events", String.class)).isEqualTo("RESOURCE_CREATED");
    }

    @Test
    void defaultsIsPrimaryToFalse() throws Exception {
        mockMvc.perform(post("/api/admin/products/" + product.getId() + "/resources")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":"https://cdn.example.com/side.jpg","type":"IMAGE"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isPrimary").value(false));
    }

    @Test
    void requiresAUrl() throws Exception {
        mockMvc.perform(post("/api/admin/products/" + product.getId() + "/resources")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"front.jpg","type":"IMAGE"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("url"));
    }

    /** Exactly one primary per product, enforced here because no database constraint does. */
    @Test
    void movingThePrimaryFlagClearsItEverywhereElse() throws Exception {
        catalogData.addImage(product, "https://cdn.example.com/front.jpg", true);
        catalogData.addImage(product, "https://cdn.example.com/side.jpg", false);
        UUID side = resourceIdFor("https://cdn.example.com/side.jpg");

        mockMvc.perform(patch("/api/admin/resources/" + side)
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"isPrimary":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isPrimary").value(true));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM product_resources WHERE is_primary = true", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT url FROM product_resources WHERE is_primary = true", String.class))
                .isEqualTo("https://cdn.example.com/side.jpg");
    }

    @Test
    void addingASecondPrimaryDemotesTheFirst() throws Exception {
        catalogData.addImage(product, "https://cdn.example.com/front.jpg", true);

        mockMvc.perform(post("/api/admin/products/" + product.getId() + "/resources")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":"https://cdn.example.com/hero.jpg","type":"IMAGE",
                                 "isPrimary":true}
                                """))
                .andExpect(status().isCreated());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT url FROM product_resources WHERE is_primary = true", String.class))
                .isEqualTo("https://cdn.example.com/hero.jpg");
    }

    @Test
    void patchesOnlyTheFieldsSupplied() throws Exception {
        catalogData.addImage(product, "https://cdn.example.com/front.jpg", true);
        UUID front = resourceIdFor("https://cdn.example.com/front.jpg");

        mockMvc.perform(patch("/api/admin/resources/" + front)
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"hero-shot.jpg"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("hero-shot.jpg"))
                .andExpect(jsonPath("$.url").value("https://cdn.example.com/front.jpg"))
                .andExpect(jsonPath("$.isPrimary").value(true));
    }

    /** Hard delete, unlike products and variants: nothing snapshots a resource. */
    @Test
    void deletesAResourceOutright() throws Exception {
        catalogData.addImage(product, "https://cdn.example.com/front.jpg", true);
        UUID front = resourceIdFor("https://cdn.example.com/front.jpg");

        mockMvc.perform(delete("/api/admin/resources/" + front)
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isNoContent());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM product_resources", Integer.class)).isZero();
        // No replacement is promoted, so the product simply has no thumbnail.
        mockMvc.perform(get("/api/admin/products").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].thumbnail").doesNotExist());
    }

    @Test
    void answers404ForAnUnknownProductOrResource() throws Exception {
        mockMvc.perform(post("/api/admin/products/" + UUID.randomUUID() + "/resources")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":"https://cdn.example.com/front.jpg"}
                                """))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/admin/resources/" + UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isNotFound());
    }

    private UUID resourceIdFor(String url) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM product_resources WHERE url = ?", UUID.class, url);
    }
}
