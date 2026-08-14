package com.mvp.ecommercebackend.catalog;

import com.mvp.ecommercebackend.catalog.dto.CategoryResponse;
import com.mvp.ecommercebackend.catalog.dto.CategoryTypeResponse;
import com.mvp.ecommercebackend.catalog.entity.Category;
import com.mvp.ecommercebackend.catalog.entity.CategoryType;
import com.mvp.ecommercebackend.catalog.repository.CategoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

@Service
public class CategoryService {

    private final CategoryRepository categoryRepository;

    public CategoryService(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    /**
     * Every category with its types nested.
     *
     * <p>Not paginated: this is a navigation tree, and a storefront needs all of it to render a
     * menu. The repository fetches the types in the same query, so the response costs one round
     * trip however many categories there are.
     */
    @Transactional(readOnly = true)
    public List<CategoryResponse> listCategories() {
        return categoryRepository.findAllByOrderByNameAsc().stream()
                .map(CategoryService::toResponse)
                .toList();
    }

    /** Shared with the admin package, which returns the same shape after a write. */
    public static CategoryResponse toResponse(Category category) {
        // Sorted here rather than with @OrderBy: the ordering is a presentation choice, and the
        // collection is already in memory from the fetch graph, so this costs nothing.
        List<CategoryTypeResponse> types = category.getCategoryTypes().stream()
                .sorted(Comparator.comparing(CategoryType::getName))
                .map(type -> new CategoryTypeResponse(
                        type.getId(), type.getCode(), type.getName(), type.getDescription()))
                .toList();
        return new CategoryResponse(category.getId(), category.getCode(), category.getName(),
                category.getDescription(), types);
    }
}
