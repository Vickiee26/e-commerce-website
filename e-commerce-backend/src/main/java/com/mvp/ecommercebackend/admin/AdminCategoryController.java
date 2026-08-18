package com.mvp.ecommercebackend.admin;

import com.mvp.ecommercebackend.admin.dto.CreateCategoryRequest;
import com.mvp.ecommercebackend.admin.dto.CreateCategoryTypeRequest;
import com.mvp.ecommercebackend.admin.dto.UpdateCategoryRequest;
import com.mvp.ecommercebackend.admin.dto.UpdateCategoryTypeRequest;
import com.mvp.ecommercebackend.auth.AuthenticatedUser;
import com.mvp.ecommercebackend.catalog.dto.CategoryResponse;
import com.mvp.ecommercebackend.catalog.dto.CategoryTypeResponse;
import com.mvp.ecommercebackend.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Category administration.
 *
 * <p>No {@code @PreAuthorize} here or on any admin controller: {@code SecurityConfig} already gates
 * {@code /api/admin/**} with {@code hasRole("ADMIN")}, and a second overlapping check is one more
 * thing that can drift out of step with the first.
 *
 * <p>Reads stay on the public {@code GET /api/categories}: the navigation tree is the same tree an
 * administrator edits, and duplicating it would let the two answers disagree.
 */
@RestController
@RequestMapping("/api/admin")
@Tag(name = "Admin Catalog")
@SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME)
public class AdminCategoryController {

    private final AdminCategoryService adminCategoryService;

    public AdminCategoryController(AdminCategoryService adminCategoryService) {
        this.adminCategoryService = adminCategoryService;
    }

    @PostMapping("/categories")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a category",
            description = "Answers 409 when the code is already taken.")
    public CategoryResponse createCategory(@AuthenticationPrincipal AuthenticatedUser principal,
                                           @Valid @RequestBody CreateCategoryRequest request) {
        return adminCategoryService.createCategory(principal.id(), request);
    }

    @PatchMapping("/categories/{id}")
    @Operation(summary = "Update a category",
            description = "Partial: an omitted field is left unchanged. The code is immutable.")
    public CategoryResponse updateCategory(@AuthenticationPrincipal AuthenticatedUser principal,
                                           @PathVariable UUID id,
                                           @Valid @RequestBody UpdateCategoryRequest request) {
        return adminCategoryService.updateCategory(principal.id(), id, request);
    }

    @DeleteMapping("/categories/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a category and its types",
            description = "Answers 409 while any product still references it.")
    public void deleteCategory(@AuthenticationPrincipal AuthenticatedUser principal,
                               @PathVariable UUID id) {
        adminCategoryService.deleteCategory(principal.id(), id);
    }

    @PostMapping("/categories/{id}/types")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a type to a category",
            description = "The code must be unique within the category.")
    public CategoryTypeResponse createCategoryType(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID id,
            @Valid @RequestBody CreateCategoryTypeRequest request) {
        return adminCategoryService.createCategoryType(principal.id(), id, request);
    }

    @PatchMapping("/category-types/{id}")
    @Operation(summary = "Update a category type",
            description = "Partial. A type cannot be moved to another category.")
    public CategoryTypeResponse updateCategoryType(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateCategoryTypeRequest request) {
        return adminCategoryService.updateCategoryType(principal.id(), id, request);
    }

    @DeleteMapping("/category-types/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a category type",
            description = "Answers 409 while any product still references it.")
    public void deleteCategoryType(@AuthenticationPrincipal AuthenticatedUser principal,
                                   @PathVariable UUID id) {
        adminCategoryService.deleteCategoryType(principal.id(), id);
    }
}
