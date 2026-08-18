package com.mvp.ecommercebackend.admin;

import com.mvp.ecommercebackend.admin.dto.AdminProductResponse;
import com.mvp.ecommercebackend.admin.dto.AdminProductSummaryResponse;
import com.mvp.ecommercebackend.admin.dto.CreateProductRequest;
import com.mvp.ecommercebackend.admin.dto.UpdateProductRequest;
import com.mvp.ecommercebackend.auth.AuthenticatedUser;
import com.mvp.ecommercebackend.common.PageResponse;
import com.mvp.ecommercebackend.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Product administration. Security comes from the {@code /api/admin/**} path rule. */
@RestController
@RequestMapping("/api/admin/products")
@Tag(name = "Admin Catalog")
@SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME)
public class AdminProductController {

    private final AdminProductService adminProductService;

    public AdminProductController(AdminProductService adminProductService) {
        this.adminProductService = adminProductService;
    }

    /**
     * @param archived {@code exclude}, {@code only} or {@code all}
     * @param sort     whitelisted, so a caller cannot sort by an arbitrary entity path
     */
    @GetMapping
    @Operation(summary = "List products for administration",
            description = "Excludes archived products unless archived=only or archived=all. "
                    + "Each row carries the unarchived variant count and total stock.")
    public PageResponse<AdminProductSummaryResponse> listProducts(
            @RequestParam(defaultValue = "exclude")
            @Pattern(regexp = "(?i)exclude|only|all",
                    message = "must be one of exclude, only, all") String archived,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(defaultValue = "0")
            @Min(value = 0, message = "must not be negative") int page,
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "must be at least 1")
            @Max(value = 100, message = "must not exceed 100") int size,
            @RequestParam(defaultValue = "name")
            @Pattern(regexp = "name|price|createdAt",
                    message = "must be one of name, price, createdAt") String sort,
            @RequestParam(defaultValue = "asc")
            @Pattern(regexp = "(?i)asc|desc", message = "must be asc or desc") String direction) {
        return adminProductService.list(archived, categoryId, q, page, size, sort, direction);
    }

    @GetMapping("/{id}")
    @Operation(summary = "One product, including archived rows",
            description = "Unlike the public endpoint, an archived product answers 200 rather than "
                    + "404, and archived variants are included.")
    public AdminProductResponse getProduct(@PathVariable UUID id) {
        return adminProductService.getProduct(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a product",
            description = "The category type must belong to the category, or the answer is 404.")
    public AdminProductResponse createProduct(@AuthenticationPrincipal AuthenticatedUser principal,
                                              @Valid @RequestBody CreateProductRequest request) {
        return adminProductService.createProduct(principal.id(), request);
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Update a product",
            description = "Partial: an omitted field is left unchanged.")
    public AdminProductResponse updateProduct(@AuthenticationPrincipal AuthenticatedUser principal,
                                              @PathVariable UUID id,
                                              @Valid @RequestBody UpdateProductRequest request) {
        return adminProductService.updateProduct(principal.id(), id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Archive a product",
            description = "Sets archivedAt rather than deleting the row, so order history and live "
                    + "carts survive. Idempotent.")
    public void archiveProduct(@AuthenticationPrincipal AuthenticatedUser principal,
                               @PathVariable UUID id) {
        adminProductService.archiveProduct(principal.id(), id);
    }

    @PostMapping("/{id}/restore")
    @Operation(summary = "Restore an archived product",
            description = "Does not resurrect variants archived separately. Idempotent.")
    public AdminProductResponse restoreProduct(@AuthenticationPrincipal AuthenticatedUser principal,
                                               @PathVariable UUID id) {
        return adminProductService.restoreProduct(principal.id(), id);
    }
}
