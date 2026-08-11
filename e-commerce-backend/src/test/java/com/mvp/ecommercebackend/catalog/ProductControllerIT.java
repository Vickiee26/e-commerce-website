package com.mvp.ecommercebackend.catalog;

import com.mvp.ecommercebackend.catalog.entity.CategoryType;
import com.mvp.ecommercebackend.catalog.entity.Product;
import com.mvp.ecommercebackend.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Acceptance criterion 8, and the regression guard for the three catalog defects the design doc
 * records. Every request here is anonymous: browsing the catalogue must never require a login.
 */
class ProductControllerIT extends AbstractIntegrationTest {

    private static final String PROBLEM_JSON = "application/problem+json";

    @Test
    void servesAProductToAnAnonymousCaller() throws Exception {
        CategoryType type = catalogData.createCategoryWithType("Footwear", "Running Shoes");
        Product product = catalogData.createProduct(type, "Trail Runner", "129.99");

        // No Authorization header anywhere in this class.
        mockMvc.perform(get("/api/products/" + product.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(product.getId().toString()))
                .andExpect(jsonPath("$.name").value("Trail Runner"))
                .andExpect(jsonPath("$.price").value(129.99))
                .andExpect(jsonPath("$.description").value("Trail Runner description"));
    }

    @Test
    void returnsTheThumbnailUrlRatherThanAnOptionalToString() throws Exception {
        CategoryType type = catalogData.createCategoryWithType("Footwear", "Running Shoes");
        Product product = catalogData.createProduct(type, "Trail Runner", "129.99");
        catalogData.addImage(product, "https://cdn.example.com/trail-runner-side.jpg", false);
        catalogData.addImage(product, "https://cdn.example.com/trail-runner-hero.jpg", true);

        mockMvc.perform(get("/api/products/" + product.getId()))
                .andExpect(status().isOk())
                // The original defect rendered "Optional[https://...]" here.
                .andExpect(jsonPath("$.thumbnail")
                        .value("https://cdn.example.com/trail-runner-hero.jpg"));
    }

    @Test
    void leavesTheThumbnailEmptyWhenNoImageIsPrimary() throws Exception {
        CategoryType type = catalogData.createCategoryWithType("Footwear", "Running Shoes");
        Product product = catalogData.createProduct(type, "Trail Runner", "129.99");
        catalogData.addImage(product, "https://cdn.example.com/trail-runner-side.jpg", false);

        mockMvc.perform(get("/api/products/" + product.getId()))
                .andExpect(status().isOk())
                // The original defect rendered the literal string "Optional.empty" here.
                .andExpect(jsonPath("$.thumbnail").doesNotExist());
    }

    @Test
    void leavesTheThumbnailEmptyWhenTheProductHasNoImagesAtAll() throws Exception {
        CategoryType type = catalogData.createCategoryWithType("Footwear", "Running Shoes");
        Product product = catalogData.createProduct(type, "Trail Runner", "129.99");

        mockMvc.perform(get("/api/products/" + product.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.thumbnail").doesNotExist());
    }

    @Test
    void namesTheCategoryAndCategoryTypeAsWellAsTheirIds() throws Exception {
        CategoryType type = catalogData.createCategoryWithType("Footwear", "Running Shoes");
        Product product = catalogData.createProduct(type, "Trail Runner", "129.99");

        mockMvc.perform(get("/api/products/" + product.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categoryId").value(type.getCategory().getId().toString()))
                .andExpect(jsonPath("$.categoryName").value("Footwear"))
                .andExpect(jsonPath("$.categoryTypeId").value(type.getId().toString()))
                .andExpect(jsonPath("$.categoryTypeName").value("Running Shoes"));
    }

    @Test
    void returnsEveryVariant() throws Exception {
        CategoryType type = catalogData.createCategoryWithType("Footwear", "Running Shoes");
        Product product = catalogData.createProduct(type, "Trail Runner", "129.99");
        catalogData.addVariant(product, "Black", "42", 7);
        catalogData.addVariant(product, "Blue", "43", 0);

        mockMvc.perform(get("/api/products/" + product.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.variants.length()").value(2))
                // ['size'] rather than .size: bare "size" collides with a JsonPath function name.
                .andExpect(jsonPath("$.variants[?(@.color == 'Black')]['size']").value("42"))
                .andExpect(jsonPath("$.variants[?(@.color == 'Black')].stockQuantity").value(7))
                .andExpect(jsonPath("$.variants[?(@.color == 'Blue')].stockQuantity").value(0));
    }

    @Test
    void returnsEveryImageWithItsPrimaryFlag() throws Exception {
        CategoryType type = catalogData.createCategoryWithType("Footwear", "Running Shoes");
        Product product = catalogData.createProduct(type, "Trail Runner", "129.99");
        catalogData.addImage(product, "https://cdn.example.com/hero.jpg", true);
        catalogData.addImage(product, "https://cdn.example.com/side.jpg", false);

        mockMvc.perform(get("/api/products/" + product.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productResources.length()").value(2))
                .andExpect(jsonPath(
                        "$.productResources[?(@.url == 'https://cdn.example.com/hero.jpg')].isPrimary")
                        .value(true))
                .andExpect(jsonPath(
                        "$.productResources[?(@.url == 'https://cdn.example.com/side.jpg')].isPrimary")
                        .value(false))
                .andExpect(jsonPath(
                        "$.productResources[?(@.url == 'https://cdn.example.com/hero.jpg')].type")
                        .value("IMAGE"));
    }

    @Test
    void answersNotFoundForAnUnknownProductId() throws Exception {
        mockMvc.perform(get("/api/products/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Not found"));
    }

    @Test
    void answersBadRequestForAMalformedProductId() throws Exception {
        mockMvc.perform(get("/api/products/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));
    }
}
