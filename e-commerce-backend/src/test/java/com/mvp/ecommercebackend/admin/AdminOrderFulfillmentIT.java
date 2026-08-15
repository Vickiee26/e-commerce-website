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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Acceptance criterion 8: the fulfilment lifecycle, and every illegal move refused with 409. */
class AdminOrderFulfillmentIT extends AbstractIntegrationTest {

    private static final String ADDRESS = """
            {"recipientName":"Ada Lovelace","line1":"12 Analytical Way","city":"London",
             "postalCode":"E1 6AN","country":"GB"}
            """;

    private String admin;
    private String customer;
    private User shopper;
    private ProductVariant variant;

    @BeforeEach
    void setUp() {
        admin = bearer(testData.createAdmin("order-admin@example.com", "correct-horse-battery"));
        shopper = testData.createCustomer("shopper@example.com", "correct-horse-battery");
        customer = bearer(shopper);
        CategoryType type = catalogData.createCategoryWithType("Footwear", "Running Shoes");
        Product product = catalogData.createProduct(type, "Trail Runner", "129.99");
        variant = catalogData.createVariant(product, "Black", "42", 10);
    }

    @Test
    void movesAPaidOrderThroughShippingToDelivery() throws Exception {
        UUID orderId = paidOrder();

        mockMvc.perform(post("/api/admin/orders/" + orderId + "/ship")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"trackingReference":"RM123456789GB"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SHIPPED"))
                .andExpect(jsonPath("$.trackingReference").value("RM123456789GB"))
                .andExpect(jsonPath("$.shippedAt").isNotEmpty())
                .andExpect(jsonPath("$.deliveredAt").doesNotExist());

        mockMvc.perform(post("/api/admin/orders/" + orderId + "/deliver")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"))
                .andExpect(jsonPath("$.deliveredAt").isNotEmpty())
                // The tracking reference and shipping timestamp survive delivery.
                .andExpect(jsonPath("$.trackingReference").value("RM123456789GB"))
                .andExpect(jsonPath("$.shippedAt").isNotEmpty());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT tracking_reference FROM orders", String.class)).isEqualTo("RM123456789GB");
        // The customer sees the status move even though tracking stays admin-only for now.
        mockMvc.perform(get("/api/me/orders/" + orderId)
                        .header(HttpHeaders.AUTHORIZATION, customer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"));
    }

    @Test
    void refusesToShipAnUnpaidOrder() throws Exception {
        UUID orderId = unpaidOrder();

        mockMvc.perform(post("/api/admin/orders/" + orderId + "/ship")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"trackingReference":"RM123456789GB"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Conflict"))
                .andExpect(jsonPath("$.detail")
                        .value("Order " + orderNumberOf(orderId)
                                + " is PENDING_PAYMENT and cannot be shipped"));

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM orders", String.class))
                .isEqualTo("PENDING_PAYMENT");
    }

    @Test
    void refusesToDeliverAnOrderThatWasNeverShipped() throws Exception {
        UUID orderId = paidOrder();

        mockMvc.perform(post("/api/admin/orders/" + orderId + "/deliver")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Conflict"))
                .andExpect(jsonPath("$.detail")
                        .value("Order " + orderNumberOf(orderId)
                                + " is PAID and cannot be delivered"));
    }

    @Test
    void refusesToShipTwiceOrToCancelAfterShipping() throws Exception {
        UUID orderId = paidOrder();
        ship(orderId);
        String number = orderNumberOf(orderId);

        mockMvc.perform(post("/api/admin/orders/" + orderId + "/ship")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"trackingReference":"RM999999999GB"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail")
                        .value("Order " + number + " is SHIPPED and cannot be shipped"));

        mockMvc.perform(post("/api/admin/orders/" + orderId + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail")
                        .value("Order " + number + " is SHIPPED and cannot be cancelled"));

        // The first shipment's tracking reference is untouched by the refused second attempt.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT tracking_reference FROM orders", String.class)).isEqualTo("RM123456789GB");
    }

    @Test
    void refusesToCancelAPaidOrderBecauseThatWouldBeARefund() throws Exception {
        UUID orderId = paidOrder();

        mockMvc.perform(post("/api/admin/orders/" + orderId + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Conflict"))
                .andExpect(jsonPath("$.detail")
                        .value("Order " + orderNumberOf(orderId)
                                + " is PAID and cannot be cancelled"));

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM orders", String.class))
                .isEqualTo("PAID");
        // A refused cancel leaves the stock where it was: still sold.
        assertThat(stock()).isEqualTo(8);
    }

    @Test
    void cancelsAnUnpaidOrderAndReturnsItsStock() throws Exception {
        UUID orderId = unpaidOrder();
        assertThat(stock()).isEqualTo(8);

        mockMvc.perform(post("/api/admin/orders/" + orderId + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledAt").isNotEmpty());

        assertThat(stock()).isEqualTo(10);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT action FROM admin_events", String.class)).isEqualTo("ORDER_CANCELLED");
    }

    @Test
    void showsAnyCustomersOrderWithTheirIdentity() throws Exception {
        UUID orderId = unpaidOrder();

        mockMvc.perform(get("/api/admin/orders/" + orderId)
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userEmail").value("shopper@example.com"))
                .andExpect(jsonPath("$.userId").value(shopper.getId().toString()))
                .andExpect(jsonPath("$.items[0].productName").value("Trail Runner"))
                .andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    void filtersTheOrderListByStatusCustomerAndOrderNumber() throws Exception {
        UUID pending = unpaidOrder();
        UUID paid = paidOrder();
        String pendingNumber = orderNumberOf(pending);

        mockMvc.perform(get("/api/admin/orders").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));

        mockMvc.perform(get("/api/admin/orders?status=PAID")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(paid.toString()));

        mockMvc.perform(get("/api/admin/orders?userId=" + shopper.getId())
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));

        mockMvc.perform(get("/api/admin/orders?orderNumber=" + pendingNumber)
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(pending.toString()));
    }

    @Test
    void filtersTheOrderListByPlacementWindow() throws Exception {
        unpaidOrder();

        // from is inclusive and to is exclusive: a window spanning the placement matches it, and a
        // window that only opens in the future does not.
        mockMvc.perform(get("/api/admin/orders?from=2000-01-01T00:00:00Z&to=2099-01-01T00:00:00Z")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        mockMvc.perform(get("/api/admin/orders?from=2099-01-01T00:00:00Z")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void rejectsAnUnknownStatusAnUnknownSortAndAnOverlongTrackingReference() throws Exception {
        mockMvc.perform(get("/api/admin/orders?status=SHIPPING")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Malformed request"));

        mockMvc.perform(get("/api/admin/orders?sort=user")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors[0].field").value("sort"))
                .andExpect(jsonPath("$.errors[0].message")
                        .value("must be one of placedAt, totalAmount, orderNumber, status"));

        UUID orderId = paidOrder();
        mockMvc.perform(post("/api/admin/orders/" + orderId + "/ship")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"trackingReference\":\"" + "R".repeat(101) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors[0].field").value("trackingReference"));
    }

    @Test
    void answers404ForAnUnknownOrder() throws Exception {
        mockMvc.perform(get("/api/admin/orders/" + UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Not found"));

        mockMvc.perform(post("/api/admin/orders/" + UUID.randomUUID() + "/deliver")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Not found"));
    }

    private void ship(UUID orderId) throws Exception {
        mockMvc.perform(post("/api/admin/orders/" + orderId + "/ship")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"trackingReference":"RM123456789GB"}
                                """))
                .andExpect(status().isOk());
    }

    /** An order for two units, left in PENDING_PAYMENT. */
    private UUID unpaidOrder() throws Exception {
        UUID addressId = addAddress();
        mockMvc.perform(post("/api/me/cart/items")
                        .header(HttpHeaders.AUTHORIZATION, customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variantId\":\"" + variant.getId() + "\",\"quantity\":2}"))
                .andExpect(status().isOk());
        String body = mockMvc.perform(post("/api/me/orders")
                        .header(HttpHeaders.AUTHORIZATION, customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shippingAddressId\":\"" + addressId + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("id").asString());
    }

    private UUID paidOrder() throws Exception {
        UUID orderId = unpaidOrder();
        mockMvc.perform(post("/api/me/orders/" + orderId + "/pay")
                        .header(HttpHeaders.AUTHORIZATION, customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"paymentMethodToken":"tok_visa"}
                                """))
                .andExpect(status().isOk());
        return orderId;
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

    private String orderNumberOf(UUID orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT order_number FROM orders WHERE id = ?", String.class, orderId);
    }

    private Integer stock() {
        return jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM product_variants WHERE id = ?",
                Integer.class, variant.getId());
    }
}
