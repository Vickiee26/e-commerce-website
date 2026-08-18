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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminStockIT extends AbstractIntegrationTest {

    private String admin;
    private ProductVariant variant;

    @BeforeEach
    void setUp() {
        User user = testData.createAdmin("stock-admin@example.com", "correct-horse-battery");
        admin = bearer(user);
        CategoryType type = catalogData.createCategoryWithType("Footwear", "Running Shoes");
        Product product = catalogData.createProduct(type, "Trail Runner", "129.99");
        variant = catalogData.createVariant(product, "Black", "42", 10);
    }

    @Test
    void appliesAPositiveDeltaAndRecordsTheReason() throws Exception {
        adjust("""
                {"delta":5,"reason":"Delivery from supplier, PO 4471"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previousQuantity").value(10))
                .andExpect(jsonPath("$.newQuantity").value(15))
                .andExpect(jsonPath("$.delta").value(5))
                .andExpect(jsonPath("$.reason").value("Delivery from supplier, PO 4471"));

        assertThat(stock()).isEqualTo(15);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT detail FROM admin_events WHERE action = 'STOCK_ADJUSTED'", String.class))
                .contains("delta=5", "10", "15", "Delivery from supplier, PO 4471");
    }

    @Test
    void appliesANegativeDelta() throws Exception {
        adjust("""
                {"delta":-4,"reason":"Damaged in the warehouse"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.newQuantity").value(6));

        assertThat(stock()).isEqualTo(6);
    }

    @Test
    void allowsADeltaThatLandsExactlyOnZero() throws Exception {
        adjust("""
                {"delta":-10,"reason":"Written off"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.newQuantity").value(0));

        assertThat(stock()).isZero();
    }

    /** Acceptance criterion 7: rejected with 409, and nothing changes — including the audit trail. */
    @Test
    void rejectsADeltaThatWouldGoNegativeAndChangesNothing() throws Exception {
        adjust("""
                {"delta":-11,"reason":"Typo"}
                """)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Insufficient stock"));

        assertThat(stock()).isEqualTo(10);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM admin_events", Integer.class)).isZero();
    }

    @Test
    void rejectsAZeroDeltaAndAMissingReason() throws Exception {
        adjust("""
                {"delta":0,"reason":"Nothing happened"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("deltaNonZero"));

        adjust("""
                {"delta":5}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("reason"));

        adjust("""
                {"delta":5,"reason":"   "}
                """)
                .andExpect(status().isBadRequest());
    }

    @Test
    void adjustsAnArchivedVariantSoStockCanBeCorrectedBeforeRestoring() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/admin/variants/" + variant.getId())
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isNoContent());

        adjust("""
                {"delta":3,"reason":"Recount before restoring"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.newQuantity").value(13));
    }

    @Test
    void answers404ForAnUnknownVariant() throws Exception {
        mockMvc.perform(post("/api/admin/variants/" + java.util.UUID.randomUUID() + "/stock")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"delta":1,"reason":"Recount"}
                                """))
                .andExpect(status().isNotFound());
    }

    private org.springframework.test.web.servlet.ResultActions adjust(String body) throws Exception {
        return mockMvc.perform(post("/api/admin/variants/" + variant.getId() + "/stock")
                .header(HttpHeaders.AUTHORIZATION, admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private Integer stock() {
        return jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM product_variants WHERE id = ?",
                Integer.class, variant.getId());
    }
}
