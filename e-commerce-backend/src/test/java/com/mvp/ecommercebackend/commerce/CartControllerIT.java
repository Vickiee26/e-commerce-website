package com.mvp.ecommercebackend.commerce;

import com.mvp.ecommercebackend.auth.TokenService;
import com.mvp.ecommercebackend.auth.entity.User;
import com.mvp.ecommercebackend.catalog.entity.CategoryType;
import com.mvp.ecommercebackend.catalog.entity.Product;
import com.mvp.ecommercebackend.catalog.entity.ProductVariant;
import com.mvp.ecommercebackend.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CartControllerIT extends AbstractIntegrationTest {

    private static final String PROBLEM_JSON = "application/problem+json";

    @Autowired
    private TokenService tokenService;

    private String bearer(User user) {
        return "Bearer " + tokenService.generateAccessToken(user);
    }

    private User customer(String email) {
        return testData.createCustomer(email, "correct-horse-battery");
    }

    /** A product with one variant, returned as the variant because cart lines reference variants. */
    private ProductVariant variant(String productName, String price, int stock) {
        CategoryType type = catalogData.createCategoryWithType(productName + " Category",
                productName + " Type");
        Product product = catalogData.createProduct(type, productName, price);
        return catalogData.createVariant(product, "Black", "42", stock);
    }

    private String addBody(ProductVariant variant, int quantity) {
        return "{\"variantId\":\"" + variant.getId() + "\",\"quantity\":" + quantity + "}";
    }

    private UUID addItem(User user, ProductVariant variant, int quantity) throws Exception {
        String response = mockMvc.perform(post("/api/me/cart/items")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addBody(variant, quantity)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return UUID.fromString(objectMapper.readTree(response)
                .get("items").get(0).get("id").asString());
    }

    @Test
    void requiresAuthenticationOnEveryCartEndpoint() throws Exception {
        UUID itemId = UUID.randomUUID();

        mockMvc.perform(get("/api/me/cart"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));
        mockMvc.perform(post("/api/me/cart/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"variantId":"00000000-0000-0000-0000-000000000001","quantity":1}
                                """))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(patch("/api/me/cart/items/" + itemId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity":2}
                                """))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/me/cart/items/" + itemId))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/me/cart"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returnsAnEmptyCartForAUserWhoHasNeverAddedAnything() throws Exception {
        User user = customer("empty@example.com");

        mockMvc.perform(get("/api/me/cart").header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.subtotal").value(0))
                .andExpect(jsonPath("$.totalQuantity").value(0));
    }

    /** No cart row is written just to read an empty cart. */
    @Test
    void doesNotCreateACartRowOnRead() throws Exception {
        User user = customer("reader@example.com");

        mockMvc.perform(get("/api/me/cart").header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk());

        Integer carts = jdbcTemplate.queryForObject("SELECT count(*) FROM carts", Integer.class);
        assertThat(carts).isZero();
    }

    @Test
    void addsALineAndReportsTheLineTotalAndSubtotal() throws Exception {
        User user = customer("adder@example.com");
        ProductVariant sneaker = variant("Trail Runner", "129.99", 5);

        mockMvc.perform(post("/api/me/cart/items")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addBody(sneaker, 2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].variantId").value(sneaker.getId().toString()))
                .andExpect(jsonPath("$.items[0].productName").value("Trail Runner"))
                .andExpect(jsonPath("$.items[0].color").value("Black"))
                .andExpect(jsonPath("$.items[0]['size']").value("42"))
                .andExpect(jsonPath("$.items[0].unitPrice").value(129.99))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.items[0].lineTotal").value(259.98))
                .andExpect(jsonPath("$.items[0].availableStock").value(5))
                .andExpect(jsonPath("$.subtotal").value(259.98))
                .andExpect(jsonPath("$.totalQuantity").value(2));
    }

    @Test
    void survivesAcrossRequests() throws Exception {
        User user = customer("persistent@example.com");
        addItem(user, variant("Trail Runner", "129.99", 5), 1);

        mockMvc.perform(get("/api/me/cart").header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.totalQuantity").value(1));
    }

    @Test
    void reportsThePrimaryImageAsTheLineThumbnail() throws Exception {
        User user = customer("thumbs@example.com");
        CategoryType type = catalogData.createCategoryWithType("Footwear", "Running Shoes");
        Product product = catalogData.createProduct(type, "Trail Runner", "129.99");
        catalogData.addImage(product, "https://cdn.example.com/side.jpg", false);
        catalogData.addImage(product, "https://cdn.example.com/hero.jpg", true);
        ProductVariant sneaker = catalogData.createVariant(product, "Black", "42", 3);

        mockMvc.perform(post("/api/me/cart/items")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addBody(sneaker, 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].thumbnail")
                        .value("https://cdn.example.com/hero.jpg"))
                .andExpect(jsonPath("$.items[0].productId").value(product.getId().toString()));
    }

    @Test
    void leavesTheThumbnailEmptyWhenNoImageIsPrimary() throws Exception {
        User user = customer("nothumb@example.com");
        CategoryType type = catalogData.createCategoryWithType("Footwear", "Running Shoes");
        Product product = catalogData.createProduct(type, "Trail Runner", "129.99");
        catalogData.addImage(product, "https://cdn.example.com/side.jpg", false);
        ProductVariant sneaker = catalogData.createVariant(product, "Black", "42", 3);

        mockMvc.perform(post("/api/me/cart/items")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addBody(sneaker, 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].thumbnail").value(nullValue()));
    }

    /** The unique constraint on (cart_id, product_variant_id) would reject a duplicate row. */
    @Test
    void mergesARepeatedAddIntoTheExistingLine() throws Exception {
        User user = customer("merger@example.com");
        ProductVariant sneaker = variant("Trail Runner", "10.00", 10);

        addItem(user, sneaker, 2);
        mockMvc.perform(post("/api/me/cart/items")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addBody(sneaker, 3)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].quantity").value(5))
                .andExpect(jsonPath("$.subtotal").value(50.00))
                .andExpect(jsonPath("$.totalQuantity").value(5));
    }

    @Test
    void keepsDistinctVariantsOnSeparateLines() throws Exception {
        User user = customer("twolines@example.com");
        CategoryType type = catalogData.createCategoryWithType("Footwear", "Running Shoes");
        Product product = catalogData.createProduct(type, "Trail Runner", "10.00");
        ProductVariant black = catalogData.createVariant(product, "Black", "42", 5);
        ProductVariant white = catalogData.createVariant(product, "White", "43", 5);

        addItem(user, black, 1);
        mockMvc.perform(post("/api/me/cart/items")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addBody(white, 2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.subtotal").value(30.00))
                .andExpect(jsonPath("$.totalQuantity").value(3));
    }

    @Test
    void refusesToAddMoreUnitsThanAreInStock() throws Exception {
        User user = customer("greedy@example.com");
        ProductVariant scarce = variant("Trail Runner", "10.00", 2);

        mockMvc.perform(post("/api/me/cart/items")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addBody(scarce, 3)))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Insufficient stock"));
    }

    /** Stock is checked against the resulting total, not the units in this one request. */
    @Test
    void refusesARepeatedAddThatWouldExceedStockInTotal() throws Exception {
        User user = customer("salami@example.com");
        ProductVariant scarce = variant("Trail Runner", "10.00", 2);

        addItem(user, scarce, 2);
        mockMvc.perform(post("/api/me/cart/items")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addBody(scarce, 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Insufficient stock"));

        mockMvc.perform(get("/api/me/cart").header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(jsonPath("$.items[0].quantity").value(2));
    }

    @Test
    void answersNotFoundForAnUnknownVariant() throws Exception {
        User user = customer("ghost@example.com");

        mockMvc.perform(post("/api/me/cart/items")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variantId\":\"" + UUID.randomUUID() + "\",\"quantity\":1}"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));
    }

    @Test
    void rejectsAnAddWithNoVariantOrAnOutOfRangeQuantity() throws Exception {
        User user = customer("invalid@example.com");
        ProductVariant sneaker = variant("Trail Runner", "10.00", 5);

        mockMvc.perform(post("/api/me/cart/items")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity":1}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.errors[0].field").value("variantId"));

        mockMvc.perform(post("/api/me/cart/items")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addBody(sneaker, 0)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("quantity"));

        mockMvc.perform(post("/api/me/cart/items")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(addBody(sneaker, 100)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("quantity"));
    }

    @Test
    void replacesALineQuantity() throws Exception {
        User user = customer("updater@example.com");
        ProductVariant sneaker = variant("Trail Runner", "10.00", 10);
        UUID itemId = addItem(user, sneaker, 2);

        mockMvc.perform(patch("/api/me/cart/items/" + itemId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity":4}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].quantity").value(4))
                .andExpect(jsonPath("$.subtotal").value(40.00));
    }

    @Test
    void refusesAnUpdateBeyondAvailableStock() throws Exception {
        User user = customer("overreach@example.com");
        ProductVariant scarce = variant("Trail Runner", "10.00", 2);
        UUID itemId = addItem(user, scarce, 1);

        mockMvc.perform(patch("/api/me/cart/items/" + itemId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity":3}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Insufficient stock"));
    }

    /** Zero is a validation failure, not a removal: emptying a line is a DELETE. */
    @Test
    void rejectsAnUpdateToZero() throws Exception {
        User user = customer("zero@example.com");
        UUID itemId = addItem(user, variant("Trail Runner", "10.00", 5), 1);

        mockMvc.perform(patch("/api/me/cart/items/" + itemId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity":0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("quantity"));
    }

    @Test
    void removesALineAndLeavesTheRest() throws Exception {
        User user = customer("remover@example.com");
        CategoryType type = catalogData.createCategoryWithType("Footwear", "Running Shoes");
        Product product = catalogData.createProduct(type, "Trail Runner", "10.00");
        ProductVariant black = catalogData.createVariant(product, "Black", "42", 5);
        ProductVariant white = catalogData.createVariant(product, "White", "43", 5);
        UUID blackLine = addItem(user, black, 1);
        addItem(user, white, 1);

        mockMvc.perform(delete("/api/me/cart/items/" + blackLine)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].variantId").value(white.getId().toString()))
                .andExpect(jsonPath("$.totalQuantity").value(1));

        Integer lines = jdbcTemplate.queryForObject("SELECT count(*) FROM cart_items", Integer.class);
        assertThat(lines).isEqualTo(1);
    }

    @Test
    void emptiesTheWholeCart() throws Exception {
        User user = customer("clearer@example.com");
        addItem(user, variant("Trail Runner", "10.00", 5), 2);

        mockMvc.perform(delete("/api/me/cart").header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/me/cart").header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.totalQuantity").value(0));

        Integer lines = jdbcTemplate.queryForObject("SELECT count(*) FROM cart_items", Integer.class);
        assertThat(lines).isZero();
    }

    /** Emptying an untouched cart is not an error. */
    @Test
    void emptiesACartThatWasNeverCreated() throws Exception {
        User user = customer("nocart@example.com");

        mockMvc.perform(delete("/api/me/cart").header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isNoContent());
    }

    /**
     * 404, not 403. A 403 would confirm that the line exists and belongs to someone else, which
     * tells an attacker something they should not learn from a guessed id.
     */
    @Test
    void answersNotFoundForAnotherUsersCartLine() throws Exception {
        User owner = customer("owner@example.com");
        User intruder = customer("intruder@example.com");
        UUID itemId = addItem(owner, variant("Trail Runner", "10.00", 5), 1);

        mockMvc.perform(patch("/api/me/cart/items/" + itemId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(intruder))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quantity":9}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));

        mockMvc.perform(delete("/api/me/cart/items/" + itemId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(intruder)))
                .andExpect(status().isNotFound());

        // And the owner's line is untouched.
        mockMvc.perform(get("/api/me/cart").header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andExpect(jsonPath("$.items[0].quantity").value(1));
    }

    @Test
    void keepsCartsSeparatePerUser() throws Exception {
        User first = customer("first@example.com");
        User second = customer("second@example.com");
        ProductVariant sneaker = variant("Trail Runner", "10.00", 10);

        addItem(first, sneaker, 3);

        mockMvc.perform(get("/api/me/cart").header(HttpHeaders.AUTHORIZATION, bearer(second)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());

        addItem(second, sneaker, 1);

        mockMvc.perform(get("/api/me/cart").header(HttpHeaders.AUTHORIZATION, bearer(first)))
                .andExpect(jsonPath("$.totalQuantity").value(3));
        mockMvc.perform(get("/api/me/cart").header(HttpHeaders.AUTHORIZATION, bearer(second)))
                .andExpect(jsonPath("$.totalQuantity").value(1));
    }

    @Test
    void answersBadRequestForAMalformedItemId() throws Exception {
        User user = customer("malformed@example.com");

        mockMvc.perform(delete("/api/me/cart/items/not-a-uuid")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));
    }
}
