package com.mvp.ecommercebackend.catalog;

import com.mvp.ecommercebackend.catalog.dto.CategoryResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Public, like the rest of the catalogue. */
@RestController
@RequestMapping("/api/categories")
@Tag(name = "Catalog")
public class CategoryController {

    private final CategoryService categoryService;

    public CategoryController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @GetMapping
    public List<CategoryResponse> listCategories() {
        return categoryService.listCategories();
    }
}
