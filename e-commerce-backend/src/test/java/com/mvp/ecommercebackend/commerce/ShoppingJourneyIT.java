package com.mvp.ecommercebackend.commerce;

import com.jayway.jsonpath.JsonPath;
import com.mvp.ecommercebackend.catalog.entity.CategoryType;
import com.mvp.ecommercebackend.catalog.entity.Product;
import com.mvp.ecommercebackend.catalog.entity.ProductVariant;
import com.mvp.ecommercebackend.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole purchase, in one sequence and entirely over HTTP: browse anonymously, register, fill a
 * cart, check out, pay, and find the order in the history.
 *
 * <p>The per-feature tests each prove one endpoint. This proves the endpoints compose — that the id
 * one response hands back is the id the next request accepts. The catalogue is the only thing seeded
 * directly, because there is no admin product endpoint to seed it through yet.
 */
class ShoppingJourneyIT extends AbstractIntegrationTest {

    private static String read(String body, String path) {
        return JsonPath.read(body, path);
    }

    @Test
    void anonymousShopperCanBrowseThenRegisterFillACartCheckOutAndPay() throws Exception {
        CategoryType type = catalogData.createCategoryWithType("Footwear", "Running Shoes");
        Product product = catalogData.createProduct(type, "Trail Runner", "129.99");
        ProductVariant variant = catalogData.createVariant(product, "Black", "42", 5);

        // 1. Browse with no token at all. This is the requirement that must never regress: a shopper
        //    sees the catalogue before deciding whether an account is worth creating.
        mockMvc.perform(get("/api/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Footwear"));

        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Trail Runner"));

        mockMvc.perform(get("/api/products/" + product.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(129.99));

        // 2. The cart is where anonymity ends.
        mockMvc.perform(get("/api/me/cart"))
                .andExpect(status().isUnauthorized());

        // 3. Register. The pair returned is immediately usable, so no separate login step.
        String registration = mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"shopper@example.com","password":"Password1!x",
                                 "fullName":"Shopper One"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String token = "Bearer " + read(registration, "$.accessToken");

        // 4. Two of the same variant, added in two requests, must merge into one line.
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/me/cart/items")
                            .header(HttpHeaders.AUTHORIZATION, token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"variantId\":\"" + variant.getId() + "\",\"quantity\":1}"))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(get("/api/me/cart").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.totalQuantity").value(2))
                .andExpect(jsonPath("$.subtotal").value(259.98));

        // 5. Somewhere to ship it.
        String addressLocation = mockMvc.perform(post("/api/me/addresses")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"recipientName":"Shopper One","line1":"1 First Street",
                                 "city":"Chennai","postalCode":"600001","country":"IN",
                                 "defaultShipping":true}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader(HttpHeaders.LOCATION);
        String addressId = addressLocation.substring(addressLocation.lastIndexOf('/') + 1);

        // 6. Check out. The order carries the cart's contents and the address as a snapshot.
        String placed = mockMvc.perform(post("/api/me/orders")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shippingAddressId\":\"" + addressId + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().exists(HttpHeaders.LOCATION))
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.totalAmount").value(259.98))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].productName").value("Trail Runner"))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.shippingAddress.city").value("Chennai"))
                .andReturn().getResponse().getContentAsString();
        String orderId = read(placed, "$.id");

        // 7. The cart is empty and the stock is down by two.
        mockMvc.perform(get("/api/me/cart").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.totalQuantity").value(0));

        mockMvc.perform(get("/api/products/" + product.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.variants[0].stockQuantity").value(3));

        // 8. Pay.
        mockMvc.perform(post("/api/me/orders/" + orderId + "/pay")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentMethodToken\":\"tok_visa_ok\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.paidAt").isNotEmpty());

        // 9. And it is in the history, paid, under the same order number.
        String history = mockMvc.perform(get("/api/me/orders")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(orderId))
                .andExpect(jsonPath("$.content[0].status").value("PAID"))
                .andReturn().getResponse().getContentAsString();

        assertThat(read(history, "$.content[0].orderNumber"))
                .isEqualTo(read(placed, "$.orderNumber"));
    }
}
