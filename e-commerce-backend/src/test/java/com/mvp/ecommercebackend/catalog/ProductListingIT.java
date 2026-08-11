package com.mvp.ecommercebackend.catalog;

import com.mvp.ecommercebackend.catalog.entity.CategoryType;
import com.mvp.ecommercebackend.catalog.entity.Product;
import com.mvp.ecommercebackend.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/products} — the browse endpoint. Anonymous throughout, like the detail endpoint.
 */
class ProductListingIT extends AbstractIntegrationTest {

    private static final String PROBLEM_JSON = "application/problem+json";

    @Test
    void listsEveryProductToAnAnonymousCaller() throws Exception {
        CategoryType type = catalogData.createCategoryWithType("Footwear", "Running Shoes");
        catalogData.createProduct(type, "Trail Runner", "129.99");
        catalogData.createProduct(type, "Road Runner", "99.00");

        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.first").value(true))
                .andExpect(jsonPath("$.last").value(true));
    }

    @Test
    void sortsByNameAscendingByDefault() throws Exception {
        CategoryType type = catalogData.createCategoryWithType("Footwear", "Running Shoes");
        catalogData.createProduct(type, "Trail Runner", "129.99");
        catalogData.createProduct(type, "Alpine Boot", "199.00");
        catalogData.createProduct(type, "Road Runner", "99.00");

        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].name",
                        contains("Alpine Boot", "Road Runner", "Trail Runner")));
    }

    @Test
    void summarisesEachRowWithoutItsVariantsOrImages() throws Exception {
        CategoryType type = catalogData.createCategoryWithType("Footwear", "Running Shoes");
        Product product = catalogData.createProduct(type, "Trail Runner", "129.99");
        catalogData.addVariant(product, "Black", "42", 7);
        catalogData.addImage(product, "https://cdn.example.com/hero.jpg", true);

        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(product.getId().toString()))
                .andExpect(jsonPath("$.content[0].name").value("Trail Runner"))
                .andExpect(jsonPath("$.content[0].price").value(129.99))
                .andExpect(jsonPath("$.content[0].thumbnail")
                        .value("https://cdn.example.com/hero.jpg"))
                .andExpect(jsonPath("$.content[0].categoryId")
                        .value(type.getCategory().getId().toString()))
                .andExpect(jsonPath("$.content[0].categoryName").value("Footwear"))
                .andExpect(jsonPath("$.content[0].categoryTypeId").value(type.getId().toString()))
                .andExpect(jsonPath("$.content[0].categoryTypeName").value("Running Shoes"))
                // A summary row is not a detail view: the heavy collections stay out.
                .andExpect(jsonPath("$.content[0].variants").doesNotExist())
                .andExpect(jsonPath("$.content[0].productResources").doesNotExist())
                .andExpect(jsonPath("$.content[0].description").doesNotExist());
    }

    @Test
    void leavesTheThumbnailNullWhenNoImageIsPrimary() throws Exception {
        CategoryType type = catalogData.createCategoryWithType("Footwear", "Running Shoes");
        Product product = catalogData.createProduct(type, "Trail Runner", "129.99");
        catalogData.addImage(product, "https://cdn.example.com/side.jpg", false);

        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].thumbnail").doesNotExist());
    }

    @Test
    void givesEachRowItsOwnThumbnail() throws Exception {
        CategoryType type = catalogData.createCategoryWithType("Footwear", "Running Shoes");
        Product first = catalogData.createProduct(type, "Alpine Boot", "199.00");
        Product second = catalogData.createProduct(type, "Trail Runner", "129.99");
        catalogData.addImage(first, "https://cdn.example.com/boot.jpg", true);
        catalogData.addImage(second, "https://cdn.example.com/runner.jpg", true);

        // Guards against a batch thumbnail lookup that pairs urls with the wrong product.
        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].thumbnail")
                        .value("https://cdn.example.com/boot.jpg"))
                .andExpect(jsonPath("$.content[1].thumbnail")
                        .value("https://cdn.example.com/runner.jpg"));
    }

    @Test
    void pagesTheResults() throws Exception {
        CategoryType type = catalogData.createCategoryWithType("Footwear", "Running Shoes");
        for (String name : new String[]{"A Shoe", "B Shoe", "C Shoe", "D Shoe", "E Shoe"}) {
            catalogData.createProduct(type, name, "50.00");
        }

        mockMvc.perform(get("/api/products").param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].name", contains("A Shoe", "B Shoe")))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.first").value(true))
                .andExpect(jsonPath("$.last").value(false));

        mockMvc.perform(get("/api/products").param("page", "2").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].name", contains("E Shoe")))
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.first").value(false))
                .andExpect(jsonPath("$.last").value(true));
    }

    @Test
    void returnsAnEmptyPageBeyondTheLastOne() throws Exception {
        CategoryType type = catalogData.createCategoryWithType("Footwear", "Running Shoes");
        catalogData.createProduct(type, "Trail Runner", "129.99");

        mockMvc.perform(get("/api/products").param("page", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void filtersByCategory() throws Exception {
        CategoryType shoes = catalogData.createCategoryWithType("Footwear", "Running Shoes");
        CategoryType shirts = catalogData.createCategoryWithType("Apparel", "T-Shirts");
        catalogData.createProduct(shoes, "Trail Runner", "129.99");
        catalogData.createProduct(shirts, "Cotton Tee", "19.99");

        mockMvc.perform(get("/api/products")
                        .param("categoryId", shoes.getCategory().getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[*].name", contains("Trail Runner")));
    }

    @Test
    void filtersByCategoryType() throws Exception {
        CategoryType running = catalogData.createCategoryWithType("Footwear", "Running Shoes");
        // Not a second createCategoryWithType("Footwear", ...): categories.code is unique.
        CategoryType hiking = catalogData.addCategoryType(running.getCategory(), "Hiking Boots");
        catalogData.createProduct(running, "Trail Runner", "129.99");
        catalogData.createProduct(hiking, "Alpine Boot", "199.00");

        mockMvc.perform(get("/api/products")
                        .param("categoryTypeId", hiking.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[*].name", contains("Alpine Boot")));
    }

    @Test
    void searchesNamesCaseInsensitively() throws Exception {
        CategoryType type = catalogData.createCategoryWithType("Footwear", "Running Shoes");
        catalogData.createProduct(type, "Trail Runner", "129.99");
        catalogData.createProduct(type, "Road Runner", "99.00");
        catalogData.createProduct(type, "Alpine Boot", "199.00");

        mockMvc.perform(get("/api/products").param("q", "RUNNER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[*].name",
                        containsInAnyOrder("Road Runner", "Trail Runner")));
    }

    @Test
    void combinesASearchTermWithACategoryFilter() throws Exception {
        CategoryType shoes = catalogData.createCategoryWithType("Footwear", "Running Shoes");
        CategoryType shirts = catalogData.createCategoryWithType("Apparel", "T-Shirts");
        catalogData.createProduct(shoes, "Runner Tee Companion", "129.99");
        catalogData.createProduct(shirts, "Runner Tee", "19.99");

        mockMvc.perform(get("/api/products")
                        .param("q", "runner")
                        .param("categoryId", shirts.getCategory().getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[*].name", contains("Runner Tee")));
    }

    @Test
    void sortsByPriceInEitherDirection() throws Exception {
        CategoryType type = catalogData.createCategoryWithType("Footwear", "Running Shoes");
        catalogData.createProduct(type, "Trail Runner", "129.99");
        catalogData.createProduct(type, "Alpine Boot", "199.00");
        catalogData.createProduct(type, "Road Runner", "99.00");

        mockMvc.perform(get("/api/products").param("sort", "price").param("direction", "asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].name",
                        contains("Road Runner", "Trail Runner", "Alpine Boot")));

        mockMvc.perform(get("/api/products").param("sort", "price").param("direction", "desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].name",
                        contains("Alpine Boot", "Trail Runner", "Road Runner")));
    }

    @Test
    void returnsAnEmptyPageRatherThanNotFoundWhenNothingMatches() throws Exception {
        mockMvc.perform(get("/api/products").param("categoryId", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0));
    }

    @Test
    void rejectsANegativePageNumber() throws Exception {
        mockMvc.perform(get("/api/products").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors[0].field").value("page"));
    }

    @Test
    void rejectsAPageSizeAboveTheCap() throws Exception {
        mockMvc.perform(get("/api/products").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.errors[0].field").value("size"));
    }

    @Test
    void rejectsAPageSizeOfZero() throws Exception {
        mockMvc.perform(get("/api/products").param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("size"));
    }

    @Test
    void rejectsASortPropertyThatIsNotWhitelisted() throws Exception {
        // Passing the property straight to Sort would let a caller probe the entity model, and an
        // unknown property surfaces as a 500.
        mockMvc.perform(get("/api/products").param("sort", "category.id"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.errors[0].field").value("sort"));
    }

    @Test
    void rejectsAnUnknownSortDirection() throws Exception {
        mockMvc.perform(get("/api/products").param("direction", "sideways"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("direction"));
    }

    @Test
    void rejectsAMalformedCategoryId() throws Exception {
        mockMvc.perform(get("/api/products").param("categoryId", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Malformed request"));
    }
}
