package com.mvp.ecommercebackend.admin;

import com.mvp.ecommercebackend.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Acceptance criterion 2: a customer cannot reach any {@code /api/admin/**} path — 403 with a token,
 * 401 without.
 *
 * <p>This test is expected to pass the moment it is written, because
 * {@code .requestMatchers("/api/admin/**").hasRole("ADMIN")} predates this slice. It is here as a
 * regression guard: it fails the day someone reorders the filter chain, or mounts an admin controller
 * under a path the rule does not cover. Step 3 of this task confirms it can fail.
 *
 * <p>One representative path per controller. The rule is prefix-based, so asserting every endpoint
 * would assert the same thing repeatedly — but a controller mounted outside the prefix is a real
 * mistake, and that is what the per-controller coverage catches.
 */
class AdminSecurityIT extends AbstractIntegrationTest {

    private String customer;
    private String admin;

    @BeforeEach
    void setUp() {
        customer = bearer(testData.createCustomer("shopper@example.com", "correct-horse-battery"));
        admin = bearer(testData.createAdmin("gate-admin@example.com", "correct-horse-battery"));
    }

    /** One GET per admin controller that has a list endpoint. Every one must be unreachable without ROLE_ADMIN. */
    @ParameterizedTest
    @ValueSource(strings = {
            "/api/admin/products",
            "/api/admin/orders",
            "/api/admin/events"})
    void refusesACustomerAndAnAnonymousCaller(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, customer))
                .andExpect(status().isForbidden());

        mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk());
    }

    /**
     * The variant and resource paths are addressed by id and have no list endpoint, so they are
     * checked with an id that does not exist. The point is the status: 403 or 401 means the gate ran,
     * and 404 would mean the request reached the handler.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "/api/admin/variants/00000000-0000-0000-0000-000000000000",
            "/api/admin/resources/00000000-0000-0000-0000-000000000000",
            "/api/admin/category-types/00000000-0000-0000-0000-000000000000"})
    void gatesThePathsAddressedOnlyById(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, customer))
                .andExpect(status().isForbidden());
    }

    /**
     * A mutation, not just a read. A chain that gated reads but let writes through would pass the
     * tests above.
     */
    @Test
    void refusesACustomerTryingToWrite() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/admin/categories")
                        .header(HttpHeaders.AUTHORIZATION, customer)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Footwear","code":"FOOTWEAR"}
                                """))
                .andExpect(status().isForbidden());
    }

    /** Browsing must stay open: the gate is on /api/admin/**, not on the catalogue. */
    @Test
    void leavesThePublicCatalogueOpen() throws Exception {
        mockMvc.perform(get("/api/products")).andExpect(status().isOk());
        mockMvc.perform(get("/api/categories")).andExpect(status().isOk());
    }
}
