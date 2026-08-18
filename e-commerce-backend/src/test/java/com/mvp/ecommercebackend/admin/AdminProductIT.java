package com.mvp.ecommercebackend.admin;

import com.mvp.ecommercebackend.auth.entity.User;
import com.mvp.ecommercebackend.catalog.entity.CategoryType;
import com.mvp.ecommercebackend.catalog.entity.Product;
import com.mvp.ecommercebackend.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminProductIT extends AbstractIntegrationTest {

    private String admin;
    private CategoryType type;

    @BeforeEach
    void setUp() {
        User user = testData.createAdmin("product-admin@example.com", "correct-horse-battery");
        admin = bearer(user);
        type = catalogData.createCategoryWithType("Footwear", "Running Shoes");
    }

    @Test
    void createsAProductAndReturnsItInTheAdminDetailView() throws Exception {
        String body = mockMvc.perform(post("/api/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Trail Runner","description":"For rough ground",
                                 "price":"129.99","categoryId":"%s","categoryTypeId":"%s"}
                                """.formatted(type.getCategory().getId(), type.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Trail Runner"))
                .andExpect(jsonPath("$.price").value(129.99))
                .andExpect(jsonPath("$.categoryName").value("Footwear"))
                .andExpect(jsonPath("$.categoryTypeName").value("Running Shoes"))
                .andExpect(jsonPath("$.archivedAt").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String productId = objectMapper.readTree(body).get("id").asString();

        mockMvc.perform(get("/api/admin/products/" + productId)
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Trail Runner"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT action FROM admin_events", String.class)).isEqualTo("PRODUCT_CREATED");
    }

    @Test
    void rejectsATypeThatBelongsToAnotherCategory() throws Exception {
        CategoryType other = catalogData.createCategoryWithType("Outerwear", "Rain Jackets");

        mockMvc.perform(post("/api/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Trail Runner","price":"129.99",
                                 "categoryId":"%s","categoryTypeId":"%s"}
                                """.formatted(type.getCategory().getId(), other.getId())))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsANegativePriceAndTooManyDecimals() throws Exception {
        mockMvc.perform(post("/api/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Trail Runner","price":"-1.00",
                                 "categoryId":"%s","categoryTypeId":"%s"}
                                """.formatted(type.getCategory().getId(), type.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("price"));

        mockMvc.perform(post("/api/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Trail Runner","price":"1.001",
                                 "categoryId":"%s","categoryTypeId":"%s"}
                                """.formatted(type.getCategory().getId(), type.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("price"));
    }

    @Test
    void patchesOnlyTheFieldsSupplied() throws Exception {
        Product product = catalogData.createProduct(type, "Trail Runner", "129.99");

        mockMvc.perform(patch("/api/admin/products/" + product.getId())
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"price":"99.00"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Trail Runner"))
                .andExpect(jsonPath("$.price").value(99.00));
    }

    @Test
    void listsUnarchivedProductsByDefaultWithStockTotals() throws Exception {
        Product product = catalogData.createProduct(type, "Trail Runner", "129.99");
        catalogData.addVariant(product, "Black", "42", 3);
        catalogData.addVariant(product, "Black", "43", 4);
        Product retired = catalogData.createProduct(type, "Old Runner", "59.99");
        archive(retired);

        mockMvc.perform(get("/api/admin/products").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Trail Runner"))
                .andExpect(jsonPath("$.content[0].variantCount").value(2))
                .andExpect(jsonPath("$.content[0].totalStock").value(7));
    }

    @Test
    void selectsOnlyArchivedOrAllProducts() throws Exception {
        catalogData.createProduct(type, "Trail Runner", "129.99");
        Product retired = catalogData.createProduct(type, "Old Runner", "59.99");
        archive(retired);

        mockMvc.perform(get("/api/admin/products?archived=only")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Old Runner"))
                .andExpect(jsonPath("$.content[0].archivedAt").isNotEmpty());

        mockMvc.perform(get("/api/admin/products?archived=all")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void filtersByCategoryAndSearchTerm() throws Exception {
        catalogData.createProduct(type, "Trail Runner", "129.99");
        catalogData.createProduct(type, "Road Runner", "89.99");

        mockMvc.perform(get("/api/admin/products?q=trail&categoryId=" + type.getCategory().getId())
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Trail Runner"));
    }

    @Test
    void rejectsAnUnknownSortProperty() throws Exception {
        mockMvc.perform(get("/api/admin/products?sort=stockQuantity")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isBadRequest());
    }

    @Test
    void archivesAndRestoresIdempotently() throws Exception {
        Product product = catalogData.createProduct(type, "Trail Runner", "129.99");

        mockMvc.perform(delete("/api/admin/products/" + product.getId())
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isNoContent());
        // Repeating an idempotent DELETE is not an error.
        mockMvc.perform(delete("/api/admin/products/" + product.getId())
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isNoContent());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM products WHERE archived_at IS NOT NULL", Integer.class))
                .isEqualTo(1);

        // The archived product is still visible to an administrator, unlike on the public endpoint.
        mockMvc.perform(get("/api/admin/products/" + product.getId())
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archivedAt").isNotEmpty());

        mockMvc.perform(post("/api/admin/products/" + product.getId() + "/restore")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archivedAt").doesNotExist());
    }

    @Test
    void answers404ForAnUnknownProduct() throws Exception {
        mockMvc.perform(get("/api/admin/products/" + UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isNotFound());
    }

    /** Archives through the API, so the test never writes a column the service owns. */
    private void archive(Product product) throws Exception {
        mockMvc.perform(delete("/api/admin/products/" + product.getId())
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isNoContent());
    }
}
