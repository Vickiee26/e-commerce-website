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
 * Acceptance criteria 3, 4, and 5: archiving hides a product from customers, an archived variant
 * cannot be bought — including one archived after it was put in a cart — and restoring reverses it
 * without resurrecting separately archived variants.
 */
class AdminArchiveIT extends AbstractIntegrationTest {

    private static final String ADDRESS = """
            {"recipientName":"Ada Lovelace","line1":"12 Analytical Way","city":"London",
             "postalCode":"E1 6AN","country":"GB"}
            """;

    private String admin;
    private String customer;
    private Product product;
    private ProductVariant variant;

    @BeforeEach
    void setUp() {
        admin = bearer(testData.createAdmin("archive-admin@example.com", "correct-horse-battery"));
        customer = bearer(testData.createCustomer("shopper@example.com", "correct-horse-battery"));
        CategoryType type = catalogData.createCategoryWithType("Footwear", "Running Shoes");
        product = catalogData.createProduct(type, "Trail Runner", "129.99");
        catalogData.addImage(product, "https://cdn.example.com/front.jpg", true);
        variant = catalogData.createVariant(product, "Black", "42", 10);
    }

    /** Criterion 3, first half. */
    @Test
    void hidesAnArchivedProductFromThePublicListingAndDetailView() throws Exception {
        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        archiveProduct();

        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.content").isEmpty());
        mockMvc.perform(get("/api/products/" + product.getId()))
                .andExpect(status().isNotFound());
    }

    /** Criterion 3, second half: order history survives archiving. */
    @Test
    void leavesAnExistingOrdersLineItemsIntactAfterTheProductIsArchived() throws Exception {
        UUID addressId = addAddress();
        addToCart(variant, 1);
        String orderId = placeOrder(addressId);

        archiveProduct();

        mockMvc.perform(get("/api/me/orders/" + orderId)
                        .header(HttpHeaders.AUTHORIZATION, customer))
                .andExpect(status().isOk())
                // Rendered from the snapshot columns on order_items, not from the catalogue.
                .andExpect(jsonPath("$.items[0].productName").value("Trail Runner"))
                .andExpect(jsonPath("$.items[0].color").value("Black"))
                .andExpect(jsonPath("$.items[0].unitPrice").value(129.99));
    }

    @Test
    void omitsAnArchivedVariantFromAProductsPublicDetailView() throws Exception {
        ProductVariant other = catalogData.createVariant(product, "Black", "43", 4);
        archiveVariant(other);

        mockMvc.perform(get("/api/products/" + product.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.variants.length()").value(1))
                .andExpect(jsonPath("$.variants[0].size").value("42"));
    }

    /** Criterion 4, first half. */
    @Test
    void refusesToAddAnArchivedVariantToACart() throws Exception {
        archiveVariant(variant);

        mockMvc.perform(post("/api/me/cart/items")
                        .header(HttpHeaders.AUTHORIZATION, customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variantId\":\"" + variant.getId() + "\",\"quantity\":1}"))
                .andExpect(status().isConflict());
    }

    @Test
    void refusesToAddAVariantOfAnArchivedProductToACart() throws Exception {
        archiveProduct();

        mockMvc.perform(post("/api/me/cart/items")
                        .header(HttpHeaders.AUTHORIZATION, customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variantId\":\"" + variant.getId() + "\",\"quantity\":1}"))
                .andExpect(status().isConflict());
    }

    /**
     * Criterion 4, second half, and the case the spec calls easiest to miss: archived *after* it was
     * already in the cart, so placement cannot trust the cart's contents.
     */
    @Test
    void refusesToCheckOutAVariantArchivedAfterItWasAddedToTheCart() throws Exception {
        UUID addressId = addAddress();
        addToCart(variant, 1);

        archiveVariant(variant);

        mockMvc.perform(post("/api/me/orders")
                        .header(HttpHeaders.AUTHORIZATION, customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shippingAddressId\":\"" + addressId + "\"}"))
                .andExpect(status().isConflict());

        // Nothing was taken: no order, stock untouched, and the cart is left for the customer to fix.
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM orders", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM product_variants WHERE id = ?",
                Integer.class, variant.getId())).isEqualTo(10);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM cart_items", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void refusesToCheckOutAVariantWhoseProductWasArchivedAfterItWasAdded() throws Exception {
        UUID addressId = addAddress();
        addToCart(variant, 1);

        archiveProduct();

        mockMvc.perform(post("/api/me/orders")
                        .header(HttpHeaders.AUTHORIZATION, customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shippingAddressId\":\"" + addressId + "\"}"))
                .andExpect(status().isConflict());
    }

    /** Criterion 5. */
    @Test
    void restoringAProductReturnsItWithoutResurrectingASeparatelyArchivedVariant() throws Exception {
        ProductVariant other = catalogData.createVariant(product, "Black", "43", 4);
        archiveVariant(other);
        archiveProduct();

        mockMvc.perform(post("/api/admin/products/" + product.getId() + "/restore")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
        // The variant retired on its own stays retired.
        mockMvc.perform(get("/api/products/" + product.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.variants.length()").value(1))
                .andExpect(jsonPath("$.variants[0].size").value("42"));

        mockMvc.perform(post("/api/admin/variants/" + other.getId() + "/restore")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/products/" + product.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.variants.length()").value(2));
    }

    @Test
    void keepsTheCategoryFilterAndSearchWorkingWithTheArchiveFilterApplied() throws Exception {
        CategoryType type = catalogData.addCategoryType(product.getCategory(), "Trail Shoes");
        Product second = catalogData.createProduct(type, "Road Runner", "89.99");
        archive(second);

        mockMvc.perform(get("/api/products?q=runner"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Trail Runner"));
        mockMvc.perform(get("/api/products?categoryId=" + product.getCategory().getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    private void archiveProduct() throws Exception {
        archive(product);
    }

    private void archive(Product target) throws Exception {
        mockMvc.perform(delete("/api/admin/products/" + target.getId())
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isNoContent());
    }

    private void archiveVariant(ProductVariant target) throws Exception {
        mockMvc.perform(delete("/api/admin/variants/" + target.getId())
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isNoContent());
    }

    private void addToCart(ProductVariant target, int quantity) throws Exception {
        mockMvc.perform(post("/api/me/cart/items")
                        .header(HttpHeaders.AUTHORIZATION, customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variantId\":\"" + target.getId()
                                + "\",\"quantity\":" + quantity + "}"))
                .andExpect(status().isOk());
    }

    private UUID addAddress() throws Exception {
        String location = mockMvc.perform(post("/api/me/addresses")
                        .header(HttpHeaders.AUTHORIZATION, customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ADDRESS))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader(HttpHeaders.LOCATION);
        return UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
    }

    private String placeOrder(UUID addressId) throws Exception {
        String body = mockMvc.perform(post("/api/me/orders")
                        .header(HttpHeaders.AUTHORIZATION, customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shippingAddressId\":\"" + addressId + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asString();
    }
}
