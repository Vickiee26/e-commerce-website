package com.mvp.ecommercebackend.admin;

import com.mvp.ecommercebackend.admin.dto.CreateCategoryRequest;
import com.mvp.ecommercebackend.admin.dto.CreateCategoryTypeRequest;
import com.mvp.ecommercebackend.admin.dto.UpdateCategoryRequest;
import com.mvp.ecommercebackend.admin.dto.UpdateCategoryTypeRequest;
import com.mvp.ecommercebackend.admin.entity.AdminEventType;
import com.mvp.ecommercebackend.admin.entity.AdminTargetType;
import com.mvp.ecommercebackend.catalog.CategoryService;
import com.mvp.ecommercebackend.catalog.dto.CategoryResponse;
import com.mvp.ecommercebackend.catalog.dto.CategoryTypeResponse;
import com.mvp.ecommercebackend.catalog.entity.Category;
import com.mvp.ecommercebackend.catalog.entity.CategoryType;
import com.mvp.ecommercebackend.catalog.repository.CategoryRepository;
import com.mvp.ecommercebackend.catalog.repository.CategoryTypeRepository;
import com.mvp.ecommercebackend.catalog.repository.ProductRepository;
import com.mvp.ecommercebackend.common.DuplicateResourceException;
import com.mvp.ecommercebackend.common.ResourceInUseException;
import com.mvp.ecommercebackend.common.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Writes to the category tree.
 *
 * <p>Separate from {@link CategoryService}, which stays {@code readOnly}: keeping the public
 * navigation query in a service that cannot write is a guarantee worth having, and it is lost the
 * moment a save lands in the same class.
 */
@Service
public class AdminCategoryService {

    private final CategoryRepository categoryRepository;
    private final CategoryTypeRepository categoryTypeRepository;
    private final ProductRepository productRepository;
    private final AdminEventService adminEventService;

    public AdminCategoryService(CategoryRepository categoryRepository,
                                CategoryTypeRepository categoryTypeRepository,
                                ProductRepository productRepository,
                                AdminEventService adminEventService) {
        this.categoryRepository = categoryRepository;
        this.categoryTypeRepository = categoryTypeRepository;
        this.productRepository = productRepository;
        this.adminEventService = adminEventService;
    }

    @Transactional
    public CategoryResponse createCategory(UUID actorUserId, CreateCategoryRequest request) {
        // Checked here rather than caught from uq_categories_code: a constraint violation arrives as
        // an opaque DataIntegrityViolationException, which the handler can only turn into a 500.
        if (categoryRepository.existsByCode(request.code())) {
            throw new DuplicateResourceException(
                    "A category with code " + request.code() + " already exists");
        }

        Category category = new Category();
        category.setName(request.name().trim());
        category.setCode(request.code());
        category.setDescription(request.description());
        Category saved = categoryRepository.saveAndFlush(category);

        adminEventService.record(actorUserId, AdminEventType.CATEGORY_CREATED,
                AdminTargetType.CATEGORY, saved.getId(), "code=" + saved.getCode());
        return CategoryService.toResponse(saved);
    }

    @Transactional
    public CategoryResponse updateCategory(UUID actorUserId, UUID categoryId,
                                           UpdateCategoryRequest request) {
        Category category = requireCategory(categoryId);
        if (request.name() != null) {
            category.setName(request.name().trim());
        }
        if (request.description() != null) {
            category.setDescription(request.description());
        }

        adminEventService.record(actorUserId, AdminEventType.CATEGORY_UPDATED,
                AdminTargetType.CATEGORY, categoryId, null);
        return CategoryService.toResponse(category);
    }

    /**
     * Removes a category and, by cascade, its types.
     *
     * <p>Categories are not archived: nothing references a category except products, and a product
     * must always name a live one. Refusing the delete while products remain is the whole guard.
     */
    @Transactional
    public void deleteCategory(UUID actorUserId, UUID categoryId) {
        Category category = requireCategory(categoryId);
        if (productRepository.existsByCategoryId(categoryId)) {
            throw new ResourceInUseException(
                    "Category " + categoryId + " still has product(s) and cannot be deleted");
        }

        adminEventService.record(actorUserId, AdminEventType.CATEGORY_DELETED,
                AdminTargetType.CATEGORY, categoryId, "code=" + category.getCode());
        // Cascade ALL on Category.categoryTypes removes the types in the same flush.
        categoryRepository.delete(category);
    }

    @Transactional
    public CategoryTypeResponse createCategoryType(UUID actorUserId, UUID categoryId,
                                                   CreateCategoryTypeRequest request) {
        Category category = requireCategory(categoryId);
        if (categoryTypeRepository.existsByCategoryIdAndCode(categoryId, request.code())) {
            throw new DuplicateResourceException("Category " + categoryId
                    + " already has a type with code " + request.code());
        }

        CategoryType type = new CategoryType();
        type.setName(request.name().trim());
        type.setCode(request.code());
        type.setDescription(request.description());
        type.setCategory(category);
        CategoryType saved = categoryTypeRepository.saveAndFlush(type);

        adminEventService.record(actorUserId, AdminEventType.CATEGORY_TYPE_CREATED,
                AdminTargetType.CATEGORY_TYPE, saved.getId(), "code=" + saved.getCode());
        return toResponse(saved);
    }

    @Transactional
    public CategoryTypeResponse updateCategoryType(UUID actorUserId, UUID categoryTypeId,
                                                   UpdateCategoryTypeRequest request) {
        CategoryType type = requireCategoryType(categoryTypeId);
        if (request.name() != null) {
            type.setName(request.name().trim());
        }
        if (request.description() != null) {
            type.setDescription(request.description());
        }

        adminEventService.record(actorUserId, AdminEventType.CATEGORY_TYPE_UPDATED,
                AdminTargetType.CATEGORY_TYPE, categoryTypeId, null);
        return toResponse(type);
    }

    @Transactional
    public void deleteCategoryType(UUID actorUserId, UUID categoryTypeId) {
        CategoryType type = requireCategoryType(categoryTypeId);
        if (productRepository.existsByCategoryTypeId(categoryTypeId)) {
            throw new ResourceInUseException(
                    "Category type " + categoryTypeId + " still has product(s) and cannot be deleted");
        }

        adminEventService.record(actorUserId, AdminEventType.CATEGORY_TYPE_DELETED,
                AdminTargetType.CATEGORY_TYPE, categoryTypeId, "code=" + type.getCode());
        categoryTypeRepository.delete(type);
    }

    private Category requireCategory(UUID categoryId) {
        return categoryRepository.findById(categoryId).orElseThrow(
                () -> new ResourceNotFoundException("Category " + categoryId + " was not found"));
    }

    private CategoryType requireCategoryType(UUID categoryTypeId) {
        return categoryTypeRepository.findById(categoryTypeId).orElseThrow(
                () -> new ResourceNotFoundException(
                        "Category type " + categoryTypeId + " was not found"));
    }

    static CategoryTypeResponse toResponse(CategoryType type) {
        return new CategoryTypeResponse(type.getId(), type.getCode(), type.getName(),
                type.getDescription());
    }
}
