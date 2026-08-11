package com.mvp.ecommercebackend.catalog;

import com.mvp.ecommercebackend.catalog.entity.CategoryType;
import com.mvp.ecommercebackend.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/categories} — the navigation tree a storefront needs before it can filter
 * products. Anonymous, like the rest of the catalogue.
 */
class CategoryControllerIT extends AbstractIntegrationTest {

    @Test
    void listsCategoriesWithTheirTypesToAnAnonymousCaller() throws Exception {
        CategoryType running = catalogData.createCategoryWithType("Footwear", "Running Shoes");

        mockMvc.perform(get("/api/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id")
                        .value(running.getCategory().getId().toString()))
                .andExpect(jsonPath("$[0].code").value("footwear"))
                .andExpect(jsonPath("$[0].name").value("Footwear"))
                .andExpect(jsonPath("$[0].description").value("Footwear description"))
                .andExpect(jsonPath("$[0].types.length()").value(1))
                .andExpect(jsonPath("$[0].types[0].id").value(running.getId().toString()))
                .andExpect(jsonPath("$[0].types[0].code").value("running-shoes"))
                .andExpect(jsonPath("$[0].types[0].name").value("Running Shoes"));
    }

    @Test
    void ordersCategoriesByNameAndTypesWithinThem() throws Exception {
        catalogData.createCategoryWithType("Outerwear", "Rain Jackets");
        catalogData.createCategoryWithType("Apparel", "T-Shirts");
        CategoryType hiking = catalogData.createCategoryWithType("Footwear", "Hiking Boots");
        catalogData.addCategoryType(hiking.getCategory(), "Running Shoes");

        mockMvc.perform(get("/api/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name",
                        contains("Apparel", "Footwear", "Outerwear")))
                .andExpect(jsonPath("$[1].types[*].name",
                        contains("Hiking Boots", "Running Shoes")));
    }

    @Test
    void returnsAnEmptyListWhenNothingIsCatalogued() throws Exception {
        mockMvc.perform(get("/api/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
