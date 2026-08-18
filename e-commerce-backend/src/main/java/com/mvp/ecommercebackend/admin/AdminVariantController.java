package com.mvp.ecommercebackend.admin;

import com.mvp.ecommercebackend.admin.dto.AdminVariantResponse;
import com.mvp.ecommercebackend.admin.dto.AdjustStockRequest;
import com.mvp.ecommercebackend.admin.dto.CreateVariantRequest;
import com.mvp.ecommercebackend.admin.dto.StockAdjustmentResponse;
import com.mvp.ecommercebackend.admin.dto.UpdateVariantRequest;
import com.mvp.ecommercebackend.auth.AuthenticatedUser;
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
 * Variant administration.
 *
 * <p>Mapped at {@code /api/admin} rather than at a single resource root because creating a variant is
 * addressed under its product while the rest are addressed by the variant's own id.
 */
@RestController
@RequestMapping("/api/admin")
@Tag(name = "Admin Catalog")
@SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME)
public class AdminVariantController {

    private final AdminVariantService adminVariantService;
    private final AdminStockService adminStockService;

    public AdminVariantController(AdminVariantService adminVariantService,
                                  AdminStockService adminStockService) {
        this.adminVariantService = adminVariantService;
        this.adminStockService = adminStockService;
    }

    @PostMapping("/products/{id}/variants")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a variant to a product",
            description = "stockQuantity is an opening balance and defaults to zero. Later changes "
                    + "go through the stock endpoint.")
    public AdminVariantResponse createVariant(@AuthenticationPrincipal AuthenticatedUser principal,
                                              @PathVariable UUID id,
                                              @Valid @RequestBody CreateVariantRequest request) {
        return adminVariantService.createVariant(principal.id(), id, request);
    }

    @PatchMapping("/variants/{id}")
    @Operation(summary = "Update a variant's colour or size",
            description = "Stock is not patchable; use the stock endpoint.")
    public AdminVariantResponse updateVariant(@AuthenticationPrincipal AuthenticatedUser principal,
                                              @PathVariable UUID id,
                                              @Valid @RequestBody UpdateVariantRequest request) {
        return adminVariantService.updateVariant(principal.id(), id, request);
    }

    @DeleteMapping("/variants/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Archive a variant",
            description = "Sets archivedAt rather than deleting the row, so live carts and order "
                    + "history survive. Idempotent.")
    public void archiveVariant(@AuthenticationPrincipal AuthenticatedUser principal,
                               @PathVariable UUID id) {
        adminVariantService.archiveVariant(principal.id(), id);
    }

    @PostMapping("/variants/{id}/restore")
    @Operation(summary = "Restore an archived variant", description = "Idempotent.")
    public AdminVariantResponse restoreVariant(@AuthenticationPrincipal AuthenticatedUser principal,
                                               @PathVariable UUID id) {
        return adminVariantService.restoreVariant(principal.id(), id);
    }

    @PostMapping("/variants/{id}/stock")
    @Operation(summary = "Adjust a variant's stock by a signed delta",
            description = "The row is locked for update, so an adjustment cannot be computed from a "
                    + "stale read while a checkout is in flight. A result below zero answers 409. "
                    + "reason is required and is written to the audit trail.")
    public StockAdjustmentResponse adjustStock(@AuthenticationPrincipal AuthenticatedUser principal,
                                               @PathVariable UUID id,
                                               @Valid @RequestBody AdjustStockRequest request) {
        return adminStockService.adjustStock(principal.id(), id, request);
    }
}
