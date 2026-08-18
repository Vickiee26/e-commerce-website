package com.mvp.ecommercebackend.admin;

import com.mvp.ecommercebackend.admin.dto.CreateResourceRequest;
import com.mvp.ecommercebackend.admin.dto.UpdateResourceRequest;
import com.mvp.ecommercebackend.auth.AuthenticatedUser;
import com.mvp.ecommercebackend.catalog.dto.ProductResourceDto;
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

/** Product images and attachments. */
@RestController
@RequestMapping("/api/admin")
@Tag(name = "Admin Catalog")
@SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME)
public class AdminResourceController {

    private final AdminResourceService adminResourceService;

    public AdminResourceController(AdminResourceService adminResourceService) {
        this.adminResourceService = adminResourceService;
    }

    @PostMapping("/products/{id}/resources")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Attach a resource to a product",
            description = "isPrimary defaults to false. Setting it true demotes the product's "
                    + "current primary in the same transaction.")
    public ProductResourceDto createResource(@AuthenticationPrincipal AuthenticatedUser principal,
                                             @PathVariable UUID id,
                                             @Valid @RequestBody CreateResourceRequest request) {
        return adminResourceService.createResource(principal.id(), id, request);
    }

    @PatchMapping("/resources/{id}")
    @Operation(summary = "Update a resource",
            description = "Partial: an omitted field is left unchanged. Promoting one to primary "
                    + "demotes the others.")
    public ProductResourceDto updateResource(@AuthenticationPrincipal AuthenticatedUser principal,
                                             @PathVariable UUID id,
                                             @Valid @RequestBody UpdateResourceRequest request) {
        return adminResourceService.updateResource(principal.id(), id, request);
    }

    @DeleteMapping("/resources/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a resource",
            description = "A hard delete, unlike products and variants: nothing snapshots a "
                    + "resource. No replacement primary is promoted.")
    public void deleteResource(@AuthenticationPrincipal AuthenticatedUser principal,
                               @PathVariable UUID id) {
        adminResourceService.deleteResource(principal.id(), id);
    }
}
