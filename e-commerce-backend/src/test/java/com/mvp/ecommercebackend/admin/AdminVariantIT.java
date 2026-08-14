package com.mvp.ecommercebackend.admin;

import com.mvp.ecommercebackend.auth.entity.User;
import com.mvp.ecommercebackend.catalog.entity.CategoryType;
import com.mvp.ecommercebackend.catalog.entity.Product;
import com.mvp.ecommercebackend.catalog.entity.ProductVariant;
import com.mvp.ecommercebackend.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminVariantIT extends AbstractIntegrationTest {

    private String admin;
    private Product product;

    @BeforeEach
    void setUp() {
        User user = testData.createAdmin("variant-admin@example.com", "correct-horse-battery");
        admin = bearer(user);
        CategoryType type = catalogData.createCategoryWithType("Footwear", "Running Shoes");
        product = catalogData.createProduct(type, "Trail Runner", "129.99");
    }

    @Test
    void createsAVariantWithAnOpeningStockBalance() throws Exception {
        mockMvc.perform(post("/api/admin/products/" + product.getId() + "/variants")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"color":"Black","size":"42","stockQuantity":5}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.color").value("Black"))
                .andExpect(jsonPath("$.size").value("42"))
                .andExpect(jsonPath("$.stockQuantity").value(5))
                .andExpect(jsonPath("$.archivedAt").doesNotExist());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT action FROM admin_events", String.class)).isEqualTo("VARIANT_CREATED");
    }

    @Test
    void defaultsTheOpeningBalanceToZero() throws Exception {
        mockMvc.perform(post("/api/admin/products/" + product.getId() + "/variants")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"color":"Black","size":"42"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.stockQuantity").value(0));
    }

    @Test
    void rejectsANegativeOpeningBalance() throws Exception {
        mockMvc.perform(post("/api/admin/products/" + product.getId() + "/variants")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"color":"Black","size":"42","stockQuantity":-1}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("stockQuantity"));
    }

    @Test
    void rejectsADuplicateColourAndSizeOnTheSameProduct() throws Exception {
        catalogData.addVariant(product, "Black", "42", 5);

        mockMvc.perform(post("/api/admin/products/" + product.getId() + "/variants")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"color":"black","size":"42","stockQuantity":1}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("already has a variant")));
    }

    @Test
    void allowsTheColourAndSizeOfAnArchivedVariantToBeReused() throws Exception {
        ProductVariant variant = catalogData.createVariant(product, "Black", "42", 5);
        mockMvc.perform(delete("/api/admin/variants/" + variant.getId())
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/admin/products/" + product.getId() + "/variants")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"color":"Black","size":"42","stockQuantity":2}
                                """))
                .andExpect(status().isCreated());
    }

    @Test
    void patchesColourAndSizeButNotStock() throws Exception {
        ProductVariant variant = catalogData.createVariant(product, "Black", "42", 5);

        mockMvc.perform(patch("/api/admin/variants/" + variant.getId())
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"color":"Charcoal"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.color").value("Charcoal"))
                .andExpect(jsonPath("$.size").value("42"))
                // Stock is untouched, and there is no field on this request that could touch it.
                .andExpect(jsonPath("$.stockQuantity").value(5));

        // An unknown property is silently ignored by Jackson (default for records), so stock only moves
        // through the delta endpoint. The database check proves it.
        mockMvc.perform(patch("/api/admin/variants/" + variant.getId())
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"stockQuantity":99}
                                """))
                .andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM product_variants", Integer.class)).isEqualTo(5);
    }

    @Test
    void archivesAndRestoresAVariantIdempotently() throws Exception {
        ProductVariant variant = catalogData.createVariant(product, "Black", "42", 5);

        mockMvc.perform(delete("/api/admin/variants/" + variant.getId())
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/admin/variants/" + variant.getId())
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isNoContent());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM product_variants WHERE archived_at IS NOT NULL", Integer.class))
                .isEqualTo(1);

        mockMvc.perform(post("/api/admin/variants/" + variant.getId() + "/restore")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archivedAt").doesNotExist());
    }

    @Test
    void keepsAnArchivedVariantOutOfTheStockTotalsButVisibleOnTheDetailView() throws Exception {
        catalogData.addVariant(product, "Black", "42", 5);
        ProductVariant retired = catalogData.createVariant(product, "Black", "43", 7);
        mockMvc.perform(delete("/api/admin/variants/" + retired.getId())
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/admin/products").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].variantCount").value(1))
                .andExpect(jsonPath("$.content[0].totalStock").value(5));

        // The detail view still shows it, so an administrator can find it to restore it.
        mockMvc.perform(get("/api/admin/products/" + product.getId())
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.variants.length()").value(2));
    }

    @Test
    void rejectsUpdatingAVariantToAnotherLiveVariantsColourAndSize() throws Exception {
        ProductVariant variantA = catalogData.createVariant(product, "Black", "42", 5);
        ProductVariant variantB = catalogData.createVariant(product, "Red", "43", 3);

        mockMvc.perform(patch("/api/admin/variants/" + variantB.getId())
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"color":"Black","size":"42"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("already has a variant")));
    }

    @Test
    void rejectsRestoringAVariantWhenItsSlotHasBeenTaken() throws Exception {
        ProductVariant original = catalogData.createVariant(product, "Black", "42", 5);

        // Archive the original
        mockMvc.perform(delete("/api/admin/variants/" + original.getId())
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isNoContent());

        // Create a new variant in the same slot
        mockMvc.perform(post("/api/admin/products/" + product.getId() + "/variants")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"color":"Black","size":"42","stockQuantity":2}
                                """))
                .andExpect(status().isCreated());

        // Attempt to restore the original should fail
        mockMvc.perform(post("/api/admin/variants/" + original.getId() + "/restore")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("Cannot restore variant")))
                .andExpect(jsonPath("$.detail").value(containsString("already has a live variant")));
    }

    @Test
    void createsAVariantOnAnArchivedProduct() throws Exception {
        // Archive the product first
        mockMvc.perform(delete("/api/admin/products/" + product.getId())
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isNoContent());

        // Creating a variant on the archived product should succeed
        mockMvc.perform(post("/api/admin/products/" + product.getId() + "/variants")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"color":"Black","size":"42","stockQuantity":5}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.color").value("Black"))
                .andExpect(jsonPath("$.size").value("42"))
                .andExpect(jsonPath("$.stockQuantity").value(5));
    }

    @Test
    void restoresAnAlreadyLiveVariantIdempotently() throws Exception {
        ProductVariant variant = catalogData.createVariant(product, "Black", "42", 5);

        // Archive and restore
        mockMvc.perform(delete("/api/admin/variants/" + variant.getId())
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/admin/variants/" + variant.getId() + "/restore")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archivedAt").doesNotExist());

        // Restoring again should be idempotent
        mockMvc.perform(post("/api/admin/variants/" + variant.getId() + "/restore")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archivedAt").doesNotExist());
    }

    @Test
    void answers404ForAnUnknownProductOrVariant() throws Exception {
        mockMvc.perform(post("/api/admin/products/" + UUID.randomUUID() + "/variants")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"color":"Black","size":"42"}
                                """))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/admin/variants/" + UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isNotFound());
    }
}
