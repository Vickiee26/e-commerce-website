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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Acceptance criterion 9: every admin mutation writes exactly one audit row naming the acting
 * administrator, and a mutation that rolls back writes none.
 *
 * <p>The rollback case is the one that matters. {@code AdminEventService.record} deliberately joins
 * the caller's transaction rather than opening its own, so a failed mutation cannot leave behind a
 * row claiming it happened. A {@code REQUIRES_NEW} propagation would break exactly this test.
 */
class AdminEventIT extends AbstractIntegrationTest {

    private User adminUser;
    private String admin;
    private Product product;
    private ProductVariant variant;

    @BeforeEach
    void setUp() {
        adminUser = testData.createAdmin("audit-admin@example.com", "correct-horse-battery");
        admin = bearer(adminUser);
        CategoryType type = catalogData.createCategoryWithType("Footwear", "Running Shoes");
        product = catalogData.createProduct(type, "Trail Runner", "129.99");
        variant = catalogData.createVariant(product, "Black", "42", 10);
    }

    @Test
    void writesExactlyOneRowPerMutationNamingTheActingAdministrator() throws Exception {
        adjustStock(3);

        assertThat(countEvents()).isEqualTo(1);

        mockMvc.perform(get("/api/admin/events").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].action").value("STOCK_ADJUSTED"))
                .andExpect(jsonPath("$.content[0].targetType").value("PRODUCT_VARIANT"))
                .andExpect(jsonPath("$.content[0].targetId").value(variant.getId().toString()))
                .andExpect(jsonPath("$.content[0].actorUserId").value(adminUser.getId().toString()))
                .andExpect(jsonPath("$.content[0].actorEmail").value("audit-admin@example.com"))
                .andExpect(jsonPath("$.content[0].detail").value(
                        org.hamcrest.Matchers.containsString("delta=3")))
                .andExpect(jsonPath("$.content[0].createdAt").isNotEmpty());
    }

    @Test
    void writesNoRowWhenTheMutationIsRejected() throws Exception {
        // Larger than the stock on hand, so the service throws and the transaction rolls back.
        mockMvc.perform(post("/api/admin/variants/" + variant.getId() + "/stock")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"delta":-99,"reason":"Shrinkage"}
                                """))
                .andExpect(status().isConflict());

        assertThat(countEvents()).isZero();
    }

    @Test
    void writesNoRowWhenTheTargetDoesNotExist() throws Exception {
        mockMvc.perform(delete("/api/admin/products/" + UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isNotFound());

        assertThat(countEvents()).isZero();
    }

    @Test
    void returnsTheNewestEventFirst() throws Exception {
        adjustStock(1);
        adjustStock(2);
        adjustStock(3);

        mockMvc.perform(get("/api/admin/events").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[0].detail").value(
                        org.hamcrest.Matchers.containsString("delta=3")))
                .andExpect(jsonPath("$.content[2].detail").value(
                        org.hamcrest.Matchers.containsString("delta=1")));
    }

    @Test
    void filtersByTargetActorAndAction() throws Exception {
        adjustStock(1);
        mockMvc.perform(delete("/api/admin/products/" + product.getId())
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/admin/events?targetType=PRODUCT")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].action").value("PRODUCT_ARCHIVED"));

        mockMvc.perform(get("/api/admin/events?targetId=" + variant.getId())
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].action").value("STOCK_ADJUSTED"));

        mockMvc.perform(get("/api/admin/events?action=STOCK_ADJUSTED")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        mockMvc.perform(get("/api/admin/events?actorUserId=" + adminUser.getId())
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));

        mockMvc.perform(get("/api/admin/events?actorUserId=" + UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void attributesTheEventToTheCallerNotToAnyoneNamedInTheBody() throws Exception {
        User other = testData.createAdmin("other-admin@example.com", "correct-horse-battery");

        // actorUserId in the body is ignored: the principal decides. Otherwise one administrator
        // could sign an action with a colleague's name.
        mockMvc.perform(post("/api/admin/variants/" + variant.getId() + "/stock")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"delta\":1,\"reason\":\"Recount\",\"actorUserId\":\""
                                + other.getId() + "\"}"))
                .andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT actor_user_id FROM admin_events", UUID.class))
                .isEqualTo(adminUser.getId());
    }

    @Test
    void survivesTheDeletionOfTheAdministratorWhoActed() throws Exception {
        adjustStock(1);

        // ON DELETE SET NULL: removing an administrator must not erase the record that they acted.
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", adminUser.getId());

        String reader = bearer(testData.createAdmin("reader@example.com", "correct-horse-battery"));
        mockMvc.perform(get("/api/admin/events").header(HttpHeaders.AUTHORIZATION, reader))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].action").value("STOCK_ADJUSTED"))
                .andExpect(jsonPath("$.content[0].actorUserId").doesNotExist())
                .andExpect(jsonPath("$.content[0].actorEmail").doesNotExist());
    }

    @Test
    void rejectsAnUnknownActionAnUnknownTargetTypeAndAnOutOfRangeSize() throws Exception {
        mockMvc.perform(get("/api/admin/events?action=DEFENESTRATED")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/admin/events?targetType=SPACESHIP")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/admin/events?size=101")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isBadRequest());
    }

    private void adjustStock(int delta) throws Exception {
        mockMvc.perform(post("/api/admin/variants/" + variant.getId() + "/stock")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"delta\":" + delta + ",\"reason\":\"Recount\"}"))
                .andExpect(status().isOk());
    }

    private Integer countEvents() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM admin_events", Integer.class);
    }
}
