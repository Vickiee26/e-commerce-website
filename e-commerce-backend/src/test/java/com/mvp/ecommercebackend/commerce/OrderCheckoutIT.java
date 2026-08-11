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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OrderCheckoutIT extends AbstractIntegrationTest {

    private static final String PROBLEM_JSON = "application/problem+json";

    private static final String ADDRESS = """
            {"recipientName":"Ada Lovelace","phone":"+15550100","line1":"12 Analytical Way",
             "line2":"Flat 3","city":"London","state":"Greater London","postalCode":"E1 6AN",
             "country":"GB"}
            """;

    @Autowired
    private TokenService tokenService;

    private String bearer(User user) {
        return "Bearer " + tokenService.generateAccessToken(user);
    }

    private User customer(String email) {
        return testData.createCustomer(email, "correct-horse-battery");
    }

    private ProductVariant variant(String productName, String price, int stock) {
        CategoryType type = catalogData.createCategoryWithType(productName + " Category",
                productName + " Type");
        Product product = catalogData.createProduct(type, productName, price);
        return catalogData.createVariant(product, "Black", "42", stock);
    }

    private UUID createAddress(User user) throws Exception {
        String location = mockMvc.perform(post("/api/me/addresses")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ADDRESS))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader(HttpHeaders.LOCATION);
        return UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
    }

    private void addToCart(User user, ProductVariant variant, int quantity) throws Exception {
        mockMvc.perform(post("/api/me/cart/items")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variantId\":\"" + variant.getId()
                                + "\",\"quantity\":" + quantity + "}"))
                .andExpect(status().isOk());
    }

    private String placeOrderBody(UUID addressId) {
        return "{\"shippingAddressId\":\"" + addressId + "\"}";
    }

    /** Places an order over HTTP and returns its id. */
    private UUID placeOrder(User user, UUID addressId) throws Exception {
        String location = mockMvc.perform(post("/api/me/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(placeOrderBody(addressId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader(HttpHeaders.LOCATION);
        return UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
    }

    private void pay(User user, UUID orderId, String token) throws Exception {
        mockMvc.perform(post("/api/me/orders/" + orderId + "/pay")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentMethodToken\":\"" + token + "\"}"))
                .andExpect(status().isOk());
    }

    private Integer stockOf(ProductVariant variant) {
        return jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM product_variants WHERE id = ?",
                Integer.class, variant.getId());
    }

    @Test
    void requiresAuthenticationOnEveryOrderEndpoint() throws Exception {
        UUID orderId = UUID.randomUUID();

        mockMvc.perform(get("/api/me/orders"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));
        mockMvc.perform(get("/api/me/orders/" + orderId))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/me/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(placeOrderBody(UUID.randomUUID())))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/me/orders/" + orderId + "/pay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"paymentMethodToken":"tok_visa"}
                                """))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/me/orders/" + orderId + "/cancel"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void placesAnOrderFromTheCart() throws Exception {
        User user = customer("buyer@example.com");
        UUID addressId = createAddress(user);
        ProductVariant sneaker = variant("Trail Runner", "129.99", 5);
        addToCart(user, sneaker, 2);

        mockMvc.perform(post("/api/me/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(placeOrderBody(addressId)))
                .andExpect(status().isCreated())
                .andExpect(header().exists(HttpHeaders.LOCATION))
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.subtotalAmount").value(259.98))
                .andExpect(jsonPath("$.totalAmount").value(259.98))
                .andExpect(jsonPath("$.placedAt").exists())
                .andExpect(jsonPath("$.paidAt").doesNotExist())
                .andExpect(jsonPath("$.cancelledAt").doesNotExist())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].productName").value("Trail Runner"))
                .andExpect(jsonPath("$.items[0].variantId").value(sneaker.getId().toString()))
                .andExpect(jsonPath("$.items[0].color").value("Black"))
                .andExpect(jsonPath("$.items[0]['size']").value("42"))
                .andExpect(jsonPath("$.items[0].unitPrice").value(129.99))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.items[0].lineTotal").value(259.98))
                .andExpect(jsonPath("$.shippingAddress.recipientName").value("Ada Lovelace"))
                .andExpect(jsonPath("$.shippingAddress.city").value("London"))
                .andExpect(jsonPath("$.shippingAddress.postalCode").value("E1 6AN"))
                .andExpect(jsonPath("$.shippingAddress.country").value("GB"));
    }

    /** Random and unpredictable, never a sequence: the number is quoted to customers. */
    @Test
    void givesEachOrderAnUnguessableNumber() throws Exception {
        User user = customer("numbers@example.com");
        UUID addressId = createAddress(user);
        ProductVariant sneaker = variant("Trail Runner", "10.00", 10);

        addToCart(user, sneaker, 1);
        UUID first = placeOrder(user, addressId);
        addToCart(user, sneaker, 1);
        UUID second = placeOrder(user, addressId);

        String firstNumber = orderNumberOf(user, first);
        String secondNumber = orderNumberOf(user, second);

        assertThat(firstNumber).matches("ORD-[0-9A-HJKMNP-TV-Z]{12}");
        assertThat(secondNumber).matches("ORD-[0-9A-HJKMNP-TV-Z]{12}");
        assertThat(firstNumber).isNotEqualTo(secondNumber);
    }

    private String orderNumberOf(User user, UUID orderId) throws Exception {
        String body = mockMvc.perform(get("/api/me/orders/" + orderId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("orderNumber").asString();
    }

    @Test
    void emptiesTheCartOnPlacement() throws Exception {
        User user = customer("emptied@example.com");
        UUID addressId = createAddress(user);
        addToCart(user, variant("Trail Runner", "10.00", 5), 2);
        placeOrder(user, addressId);

        mockMvc.perform(get("/api/me/cart").header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.totalQuantity").value(0));
    }

    @Test
    void takesTheStockOnPlacement() throws Exception {
        User user = customer("stock@example.com");
        UUID addressId = createAddress(user);
        ProductVariant sneaker = variant("Trail Runner", "10.00", 5);
        addToCart(user, sneaker, 2);

        placeOrder(user, addressId);

        assertThat(stockOf(sneaker)).isEqualTo(3);
    }

    @Test
    void placesAnOrderWithSeveralLinesInCartOrder() throws Exception {
        User user = customer("multiline@example.com");
        UUID addressId = createAddress(user);
        CategoryType type = catalogData.createCategoryWithType("Footwear", "Running Shoes");
        Product product = catalogData.createProduct(type, "Trail Runner", "10.00");
        ProductVariant black = catalogData.createVariant(product, "Black", "42", 5);
        ProductVariant white = catalogData.createVariant(product, "White", "43", 5);
        addToCart(user, black, 1);
        addToCart(user, white, 3);

        mockMvc.perform(post("/api/me/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(placeOrderBody(addressId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].variantId").value(black.getId().toString()))
                .andExpect(jsonPath("$.items[1].variantId").value(white.getId().toString()))
                .andExpect(jsonPath("$.subtotalAmount").value(40.00));

        assertThat(stockOf(black)).isEqualTo(4);
        assertThat(stockOf(white)).isEqualTo(2);
    }

    /**
     * The shipping address is a copy. Correcting the address book afterwards must not change where
     * an already-placed order says it went.
     */
    @Test
    void snapshotsTheShippingAddress() throws Exception {
        User user = customer("snapshot@example.com");
        UUID addressId = createAddress(user);
        addToCart(user, variant("Trail Runner", "10.00", 5), 1);
        UUID orderId = placeOrder(user, addressId);

        mockMvc.perform(patch("/api/me/addresses/" + addressId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"city":"Paris","recipientName":"Someone Else"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/me/orders/" + orderId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shippingAddress.city").value("London"))
                .andExpect(jsonPath("$.shippingAddress.recipientName").value("Ada Lovelace"));
    }

    @Test
    void survivesTheAddressBeingDeleted() throws Exception {
        User user = customer("deleted-address@example.com");
        UUID addressId = createAddress(user);
        addToCart(user, variant("Trail Runner", "10.00", 5), 1);
        UUID orderId = placeOrder(user, addressId);

        mockMvc.perform(delete("/api/me/addresses/" + addressId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/me/orders/" + orderId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shippingAddress.line1").value("12 Analytical Way"));
    }

    /** The line records the price agreed at placement, not the price today. */
    @Test
    void snapshotsThePriceAtPlacement() throws Exception {
        User user = customer("price@example.com");
        UUID addressId = createAddress(user);
        ProductVariant sneaker = variant("Trail Runner", "129.99", 5);
        addToCart(user, sneaker, 1);
        UUID orderId = placeOrder(user, addressId);

        jdbcTemplate.update("UPDATE products SET price = 999.99 WHERE name = 'Trail Runner'");

        mockMvc.perform(get("/api/me/orders/" + orderId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].unitPrice").value(129.99))
                .andExpect(jsonPath("$.totalAmount").value(129.99));
    }

    @Test
    void refusesToCheckOutAnEmptyCart() throws Exception {
        User user = customer("nothing@example.com");
        UUID addressId = createAddress(user);

        mockMvc.perform(post("/api/me/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(placeOrderBody(addressId)))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Cart is empty"));
    }

    @Test
    void answersNotFoundForAnAddressThatIsNotTheCallers() throws Exception {
        User user = customer("mine@example.com");
        User stranger = customer("theirs@example.com");
        UUID strangersAddress = createAddress(stranger);
        addToCart(user, variant("Trail Runner", "10.00", 5), 1);

        mockMvc.perform(post("/api/me/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(placeOrderBody(strangersAddress)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));
    }

    @Test
    void rejectsAPlaceOrderWithNoAddress() throws Exception {
        User user = customer("noaddress@example.com");

        mockMvc.perform(post("/api/me/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("shippingAddressId"));
    }

    /**
     * Stock can fall between adding to the cart and checking out, and the cart is not a reservation.
     * Nothing must change when that happens.
     */
    @Test
    void refusesToCheckOutMoreThanRemainsInStock() throws Exception {
        User user = customer("toolate@example.com");
        UUID addressId = createAddress(user);
        ProductVariant sneaker = variant("Trail Runner", "10.00", 5);
        addToCart(user, sneaker, 3);
        jdbcTemplate.update("UPDATE product_variants SET stock_quantity = 1 WHERE id = ?",
                sneaker.getId());

        mockMvc.perform(post("/api/me/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(placeOrderBody(addressId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Insufficient stock"));

        // Rolled back whole: no order, the stock untouched, the cart still holding the lines.
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM orders", Integer.class))
                .isZero();
        assertThat(stockOf(sneaker)).isEqualTo(1);
        mockMvc.perform(get("/api/me/cart").header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(jsonPath("$.items[0].quantity").value(3));
    }

    @Test
    void paysAnOrder() throws Exception {
        User user = customer("payer@example.com");
        UUID addressId = createAddress(user);
        addToCart(user, variant("Trail Runner", "10.00", 5), 1);
        UUID orderId = placeOrder(user, addressId);

        mockMvc.perform(post("/api/me/orders/" + orderId + "/pay")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"paymentMethodToken":"tok_visa"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.paidAt").exists());
    }

    /** The payment token never appears in a response, whatever happens to the charge. */
    @Test
    void neverEchoesThePaymentToken() throws Exception {
        User user = customer("token@example.com");
        UUID addressId = createAddress(user);
        addToCart(user, variant("Trail Runner", "10.00", 5), 1);
        UUID orderId = placeOrder(user, addressId);

        String body = mockMvc.perform(post("/api/me/orders/" + orderId + "/pay")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"paymentMethodToken":"tok_secret_instrument"}
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("tok_secret_instrument");
    }

    /** A decline leaves the order payable, so the customer can try another card. */
    @Test
    void reportsADeclinedChargeWithoutMovingTheOrder() throws Exception {
        User user = customer("declined@example.com");
        UUID addressId = createAddress(user);
        addToCart(user, variant("Trail Runner", "10.00", 5), 1);
        UUID orderId = placeOrder(user, addressId);

        mockMvc.perform(post("/api/me/orders/" + orderId + "/pay")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"paymentMethodToken":"tok_declined"}
                                """))
                .andExpect(status().isPaymentRequired())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Payment declined"));

        mockMvc.perform(get("/api/me/orders/" + orderId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.paidAt").doesNotExist());
    }

    @Test
    void rejectsAPaymentWithNoToken() throws Exception {
        User user = customer("notoken@example.com");
        UUID addressId = createAddress(user);
        addToCart(user, variant("Trail Runner", "10.00", 5), 1);
        UUID orderId = placeOrder(user, addressId);

        mockMvc.perform(post("/api/me/orders/" + orderId + "/pay")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"paymentMethodToken":"  "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("paymentMethodToken"));
    }

    @Test
    void refusesToPayAnOrderTwice() throws Exception {
        User user = customer("twice@example.com");
        UUID addressId = createAddress(user);
        addToCart(user, variant("Trail Runner", "10.00", 5), 1);
        UUID orderId = placeOrder(user, addressId);
        pay(user, orderId, "tok_visa");

        mockMvc.perform(post("/api/me/orders/" + orderId + "/pay")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"paymentMethodToken":"tok_visa"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));
    }

    @Test
    void cancelsAnUnpaidOrderAndReturnsTheStock() throws Exception {
        User user = customer("canceller@example.com");
        UUID addressId = createAddress(user);
        ProductVariant sneaker = variant("Trail Runner", "10.00", 5);
        addToCart(user, sneaker, 2);
        UUID orderId = placeOrder(user, addressId);
        assertThat(stockOf(sneaker)).isEqualTo(3);

        mockMvc.perform(post("/api/me/orders/" + orderId + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledAt").exists());

        assertThat(stockOf(sneaker)).isEqualTo(5);
    }

    /** Reversing a completed sale is a refund, and refunds are not part of this API yet. */
    @Test
    void refusesToCancelAPaidOrder() throws Exception {
        User user = customer("paidcancel@example.com");
        UUID addressId = createAddress(user);
        ProductVariant sneaker = variant("Trail Runner", "10.00", 5);
        addToCart(user, sneaker, 2);
        UUID orderId = placeOrder(user, addressId);
        pay(user, orderId, "tok_visa");

        mockMvc.perform(post("/api/me/orders/" + orderId + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));

        // And the stock stays sold.
        assertThat(stockOf(sneaker)).isEqualTo(3);
    }

    @Test
    void refusesToPayACancelledOrder() throws Exception {
        User user = customer("cancelledpay@example.com");
        UUID addressId = createAddress(user);
        addToCart(user, variant("Trail Runner", "10.00", 5), 1);
        UUID orderId = placeOrder(user, addressId);

        mockMvc.perform(post("/api/me/orders/" + orderId + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/me/orders/" + orderId + "/pay")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"paymentMethodToken":"tok_visa"}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void refusesToCancelAnOrderTwice() throws Exception {
        User user = customer("doublecancel@example.com");
        UUID addressId = createAddress(user);
        ProductVariant sneaker = variant("Trail Runner", "10.00", 5);
        addToCart(user, sneaker, 2);
        UUID orderId = placeOrder(user, addressId);

        mockMvc.perform(post("/api/me/orders/" + orderId + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/me/orders/" + orderId + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isConflict());

        // The stock came back exactly once.
        assertThat(stockOf(sneaker)).isEqualTo(5);
    }

    @Test
    void listsTheCallersOrdersNewestFirst() throws Exception {
        User user = customer("history@example.com");
        UUID addressId = createAddress(user);
        ProductVariant sneaker = variant("Trail Runner", "10.00", 10);

        addToCart(user, sneaker, 1);
        UUID first = placeOrder(user, addressId);
        addToCart(user, sneaker, 2);
        UUID second = placeOrder(user, addressId);

        mockMvc.perform(get("/api/me/orders").header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].id").value(second.toString()))
                .andExpect(jsonPath("$.content[1].id").value(first.toString()))
                .andExpect(jsonPath("$.content[0].totalAmount").value(20.00))
                .andExpect(jsonPath("$.content[0].status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.content[0].orderNumber").exists())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.first").value(true))
                .andExpect(jsonPath("$.last").value(true));
    }

    @Test
    void pagesTheOrderList() throws Exception {
        User user = customer("paged@example.com");
        UUID addressId = createAddress(user);
        ProductVariant sneaker = variant("Trail Runner", "10.00", 10);
        for (int index = 0; index < 3; index++) {
            addToCart(user, sneaker, 1);
            placeOrder(user, addressId);
        }

        mockMvc.perform(get("/api/me/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.first").value(false))
                .andExpect(jsonPath("$.last").value(true));
    }

    @Test
    void rejectsAnOutOfRangePageSize() throws Exception {
        User user = customer("badpage@example.com");

        mockMvc.perform(get("/api/me/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("size"));
        mockMvc.perform(get("/api/me/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user))
                        .param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("page"));
    }

    @Test
    void showsAUserOnlyTheirOwnOrders() throws Exception {
        User owner = customer("owner@example.com");
        User stranger = customer("stranger@example.com");
        UUID addressId = createAddress(owner);
        addToCart(owner, variant("Trail Runner", "10.00", 5), 1);
        UUID orderId = placeOrder(owner, addressId);

        mockMvc.perform(get("/api/me/orders").header(HttpHeaders.AUTHORIZATION, bearer(stranger)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0));

        // 404, not 403: a 403 would confirm the order exists.
        mockMvc.perform(get("/api/me/orders/" + orderId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(stranger)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));
        mockMvc.perform(post("/api/me/orders/" + orderId + "/pay")
                        .header(HttpHeaders.AUTHORIZATION, bearer(stranger))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"paymentMethodToken":"tok_visa"}
                                """))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/me/orders/" + orderId + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, bearer(stranger)))
                .andExpect(status().isNotFound());

        // Still awaiting payment, untouched by any of that.
        mockMvc.perform(get("/api/me/orders/" + orderId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"));
    }

    @Test
    void answersNotFoundForAnUnknownOrder() throws Exception {
        User user = customer("unknown@example.com");

        mockMvc.perform(get("/api/me/orders/" + UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));
    }

    @Test
    void answersBadRequestForAMalformedOrderId() throws Exception {
        User user = customer("malformed@example.com");

        mockMvc.perform(get("/api/me/orders/not-a-uuid")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));
    }
}
