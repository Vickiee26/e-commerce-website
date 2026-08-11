package com.mvp.ecommercebackend.commerce;

import com.mvp.ecommercebackend.auth.TokenService;
import com.mvp.ecommercebackend.auth.entity.User;
import com.mvp.ecommercebackend.catalog.entity.CategoryType;
import com.mvp.ecommercebackend.catalog.entity.Product;
import com.mvp.ecommercebackend.catalog.entity.ProductVariant;
import com.mvp.ecommercebackend.commerce.dto.PlaceOrderRequest;
import com.mvp.ecommercebackend.common.InsufficientStockException;
import com.mvp.ecommercebackend.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The one test that justifies the pessimistic lock in {@code OrderService}.
 *
 * <p>Placement is driven through the service rather than MockMvc: the point is two real, concurrent
 * transactions racing for the same row, and going through the service proxy from two threads gives
 * exactly that without depending on MockMvc being safe to share.
 *
 * <p>This test has been confirmed to have teeth: deleting the {@code @Lock} from
 * {@code ProductVariantRepository.lockAllByIdIn} makes it fail with two PLACED results — a genuine
 * oversell of the last unit.
 */
class OrderConcurrencyIT extends AbstractIntegrationTest {

    private static final String ADDRESS = """
            {"recipientName":"Ada Lovelace","line1":"12 Analytical Way","city":"London",
             "postalCode":"E1 6AN","country":"GB"}
            """;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private OrderService orderService;

    @Test
    void sellsTheLastUnitExactlyOnce() throws Exception {
        CategoryType type = catalogData.createCategoryWithType("Footwear", "Running Shoes");
        Product product = catalogData.createProduct(type, "Trail Runner", "129.99");
        ProductVariant lastOne = catalogData.createVariant(product, "Black", "42", 1);

        User first = testData.createCustomer("racer1@example.com", "correct-horse-battery");
        User second = testData.createCustomer("racer2@example.com", "correct-horse-battery");
        PlaceOrderRequest firstRequest = new PlaceOrderRequest(prepareCart(first, lastOne));
        PlaceOrderRequest secondRequest = new PlaceOrderRequest(prepareCart(second, lastOne));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // Both threads park on the latch so neither can finish before the other has started.
            // submit() rather than invokeAll(): invokeAll blocks until every task is done, so the
            // countDown below would never run and both threads would sit on the latch until it
            // timed out.
            CountDownLatch start = new CountDownLatch(1);
            Future<String> firstOutcome = pool.submit(checkout(start, first.getId(), firstRequest));
            Future<String> secondOutcome = pool.submit(checkout(start, second.getId(), secondRequest));
            start.countDown();

            List<String> results = List.of(
                    firstOutcome.get(30, TimeUnit.SECONDS), secondOutcome.get(30, TimeUnit.SECONDS));
            assertThat(results).containsExactlyInAnyOrder("PLACED", "OUT_OF_STOCK");
        } finally {
            pool.shutdownNow();
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM product_variants WHERE id = ?",
                Integer.class, lastOne.getId())).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM orders", Integer.class))
                .isEqualTo(1);
        // The loser's cart is intact, so they can buy something else with it.
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM cart_items", Integer.class))
                .isEqualTo(1);
    }

    private Callable<String> checkout(CountDownLatch start, UUID userId, PlaceOrderRequest request) {
        return () -> {
            start.await(10, TimeUnit.SECONDS);
            try {
                orderService.placeOrder(userId, request);
                return "PLACED";
            } catch (InsufficientStockException expected) {
                return "OUT_OF_STOCK";
            }
        };
    }

    /** Gives the user an address and a cart holding one of {@code variant}, and returns the address. */
    private UUID prepareCart(User user, ProductVariant variant) throws Exception {
        String token = "Bearer " + tokenService.generateAccessToken(user);
        mockMvc.perform(post("/api/me/cart/items")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variantId\":\"" + variant.getId() + "\",\"quantity\":1}"))
                .andExpect(status().isOk());

        String location = mockMvc.perform(post("/api/me/addresses")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ADDRESS))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader(HttpHeaders.LOCATION);
        return UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
    }
}
