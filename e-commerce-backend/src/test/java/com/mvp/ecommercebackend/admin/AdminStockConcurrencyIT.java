package com.mvp.ecommercebackend.admin;

import com.mvp.ecommercebackend.admin.dto.AdjustStockRequest;
import com.mvp.ecommercebackend.auth.entity.User;
import com.mvp.ecommercebackend.catalog.entity.CategoryType;
import com.mvp.ecommercebackend.catalog.entity.Product;
import com.mvp.ecommercebackend.catalog.entity.ProductVariant;
import com.mvp.ecommercebackend.commerce.OrderService;
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
 * Acceptance criterion 6, and the test the delta endpoint exists for.
 *
 * <p>An absolute {@code PATCH stockQuantity} could not pass either of these: an administrator who
 * reads 10, has a customer buy one, and then writes 10 has put a sold unit back on the shelf. The
 * delta plus the row lock in {@code ProductVariantRepository.lockById} makes that arithmetic
 * impossible.
 *
 * <p>Confirm this test has teeth before committing: delete {@code @Lock} from {@code lockById} and
 * {@code noLostUpdateWhenAnAdjustmentRacesASale} must fail with 6 or 0 rather than 5. Put the
 * annotation back afterwards. A concurrency test that passes without the lock is proving nothing.
 */
class AdminStockConcurrencyIT extends AbstractIntegrationTest {

    private static final String ADDRESS = """
            {"recipientName":"Ada Lovelace","line1":"12 Analytical Way","city":"London",
             "postalCode":"E1 6AN","country":"GB"}
            """;

    @Autowired
    private OrderService orderService;

    @Autowired
    private AdminStockService adminStockService;

    /**
     * Stock 1, a sale of 1 and a write-off of 1 racing. Exactly one wins, and stock lands on 0 — never
     * -1, and never 0 with both having reported success.
     */
    @Test
    void neverLetsASaleAndAWriteOffBothTakeTheLastUnit() throws Exception {
        ProductVariant lastOne = variantWithStock(1);
        User customer = testData.createCustomer("racer@example.com", "correct-horse-battery");
        User admin = testData.createAdmin("stock-racer@example.com", "correct-horse-battery");
        PlaceOrderRequest order = new PlaceOrderRequest(prepareCart(customer, lastOne));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Future<String> sale = pool.submit(sell(start, customer.getId(), order));
            Future<String> writeOff = pool.submit(
                    adjust(start, admin.getId(), lastOne.getId(), -1, "Damaged"));
            start.countDown();

            List<String> results = List.of(
                    sale.get(30, TimeUnit.SECONDS), writeOff.get(30, TimeUnit.SECONDS));
            // Either order is legitimate; what is not legitimate is both succeeding.
            assertThat(results).containsAnyOf("REJECTED");
            assertThat(results.stream().filter(result -> !"REJECTED".equals(result)).count())
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        assertThat(stockOf(lastOne)).isZero();
    }

    /**
     * The lost-update case, and the one that actually needs the lock.
     *
     * <p>Stock 1, a delivery of +5 and a sale of 1 racing. Both must succeed and the result must be
     * exactly 5. Without the lock both threads read 1, one computes 6 and the other 0, and whichever
     * flushes last wins — so a passing 5 is only possible if the two serialised.
     */
    @Test
    void noLostUpdateWhenAnAdjustmentRacesASale() throws Exception {
        ProductVariant variant = variantWithStock(1);
        User customer = testData.createCustomer("racer2@example.com", "correct-horse-battery");
        User admin = testData.createAdmin("stock-racer2@example.com", "correct-horse-battery");
        PlaceOrderRequest order = new PlaceOrderRequest(prepareCart(customer, variant));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Future<String> sale = pool.submit(sell(start, customer.getId(), order));
            Future<String> delivery = pool.submit(
                    adjust(start, admin.getId(), variant.getId(), 5, "Delivery, PO 4471"));
            start.countDown();

            assertThat(List.of(sale.get(30, TimeUnit.SECONDS), delivery.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("PLACED", "ADJUSTED");
        } finally {
            pool.shutdownNow();
        }

        // 1 + 5 - 1. Six would mean the sale was lost; zero would mean the delivery was.
        assertThat(stockOf(variant)).isEqualTo(5);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM orders", Integer.class))
                .isEqualTo(1);
    }

    private Callable<String> sell(CountDownLatch start, UUID userId, PlaceOrderRequest request) {
        return () -> {
            start.await(10, TimeUnit.SECONDS);
            try {
                orderService.placeOrder(userId, request);
                return "PLACED";
            } catch (InsufficientStockException expected) {
                return "REJECTED";
            }
        };
    }

    private Callable<String> adjust(CountDownLatch start, UUID actorId, UUID variantId,
                                    int delta, String reason) {
        return () -> {
            start.await(10, TimeUnit.SECONDS);
            try {
                adminStockService.adjustStock(actorId, variantId,
                        new AdjustStockRequest(delta, reason));
                return "ADJUSTED";
            } catch (InsufficientStockException expected) {
                return "REJECTED";
            }
        };
    }

    private ProductVariant variantWithStock(int stockQuantity) {
        CategoryType type = catalogData.createCategoryWithType("Footwear", "Running Shoes");
        Product product = catalogData.createProduct(type, "Trail Runner", "129.99");
        return catalogData.createVariant(product, "Black", "42", stockQuantity);
    }

    private Integer stockOf(ProductVariant variant) {
        return jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM product_variants WHERE id = ?",
                Integer.class, variant.getId());
    }

    /** Gives the user an address and a cart holding one of {@code variant}, and returns the address. */
    private UUID prepareCart(User user, ProductVariant variant) throws Exception {
        String token = bearer(user);
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
