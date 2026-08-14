package com.mvp.ecommercebackend.admin;

import com.mvp.ecommercebackend.auth.entity.User;
import com.mvp.ecommercebackend.catalog.entity.CategoryType;
import com.mvp.ecommercebackend.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminCategoryIT extends AbstractIntegrationTest {

    private String admin;

    @BeforeEach
    void authenticate() {
        User user = testData.createAdmin("cat-admin@example.com", "correct-horse-battery");
        admin = bearer(user);
    }

    @Test
    void createsACategoryThatAppearsInThePublicNavigation() throws Exception {
        mockMvc.perform(post("/api/admin/categories")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Footwear","code":"footwear","description":"Shoes and boots"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Footwear"))
                .andExpect(jsonPath("$.code").value("footwear"))
                .andExpect(jsonPath("$.types").isEmpty());

        mockMvc.perform(get("/api/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("footwear"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT action FROM admin_events", String.class)).isEqualTo("CATEGORY_CREATED");
    }

    @Test
    void rejectsADuplicateCategoryCode() throws Exception {
        catalogData.createCategoryWithType("Footwear", "Running Shoes");

        mockMvc.perform(post("/api/admin/categories")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Other Footwear","code":"footwear"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Conflict"));

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM categories", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void rejectsABlankName() throws Exception {
        mockMvc.perform(post("/api/admin/categories")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"  ","code":"footwear"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }

    @Test
    void patchesOnlyTheFieldsSupplied() throws Exception {
        CategoryType type = catalogData.createCategoryWithType("Footwear", "Running Shoes");

        mockMvc.perform(patch("/api/admin/categories/" + type.getCategory().getId())
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description":"Everything you wear on your feet"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Footwear"))
                .andExpect(jsonPath("$.description").value("Everything you wear on your feet"))
                .andExpect(jsonPath("$.types[0].name").value("Running Shoes"));
    }

    @Test
    void refusesToDeleteACategoryAProductStillUses() throws Exception {
        CategoryType type = catalogData.createCategoryWithType("Footwear", "Running Shoes");
        catalogData.createProduct(type, "Trail Runner", "129.99");

        mockMvc.perform(delete("/api/admin/categories/" + type.getCategory().getId())
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("product")));

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM categories", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void deletesAnUnusedCategoryAndItsTypes() throws Exception {
        CategoryType type = catalogData.createCategoryWithType("Footwear", "Running Shoes");

        mockMvc.perform(delete("/api/admin/categories/" + type.getCategory().getId())
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isNoContent());

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM categories", Integer.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM category_types", Integer.class))
                .isZero();
    }

    @Test
    void addsPatchesAndDeletesACategoryType() throws Exception {
        CategoryType existing = catalogData.createCategoryWithType("Footwear", "Running Shoes");

        String created = mockMvc.perform(
                        post("/api/admin/categories/" + existing.getCategory().getId() + "/types")
                                .header(HttpHeaders.AUTHORIZATION, admin)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"name":"Hiking Boots","code":"hiking-boots"}
                                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Hiking Boots"))
                .andReturn().getResponse().getContentAsString();
        String typeId = objectMapper.readTree(created).get("id").asString();

        mockMvc.perform(patch("/api/admin/category-types/" + typeId)
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Walking Boots"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Walking Boots"))
                .andExpect(jsonPath("$.code").value("hiking-boots"));

        mockMvc.perform(delete("/api/admin/category-types/" + typeId)
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isNoContent());

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM category_types", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void rejectsADuplicateTypeCodeWithinTheSameCategory() throws Exception {
        CategoryType existing = catalogData.createCategoryWithType("Footwear", "Running Shoes");

        mockMvc.perform(post("/api/admin/categories/" + existing.getCategory().getId() + "/types")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Running Shoes Again","code":"running-shoes"}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void refusesToDeleteATypeAProductStillUses() throws Exception {
        CategoryType type = catalogData.createCategoryWithType("Footwear", "Running Shoes");
        catalogData.createProduct(type, "Trail Runner", "129.99");

        mockMvc.perform(delete("/api/admin/category-types/" + type.getId())
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isConflict());
    }

    @Test
    void answers404ForAnUnknownCategory() throws Exception {
        mockMvc.perform(delete("/api/admin/categories/" + java.util.UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isNotFound());
    }
}
