package com.mvp.ecommercebackend.admin;

import com.mvp.ecommercebackend.admin.dto.CreateResourceRequest;
import com.mvp.ecommercebackend.admin.dto.UpdateResourceRequest;
import com.mvp.ecommercebackend.admin.entity.AdminEventType;
import com.mvp.ecommercebackend.admin.entity.AdminTargetType;
import com.mvp.ecommercebackend.catalog.dto.ProductResourceDto;
import com.mvp.ecommercebackend.catalog.entity.Product;
import com.mvp.ecommercebackend.catalog.entity.Resource;
import com.mvp.ecommercebackend.catalog.repository.ResourceRepository;
import com.mvp.ecommercebackend.common.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Product images and attachments.
 *
 * <p>Hard deletes, unlike products and variants: nothing snapshots a resource — {@code order_items}
 * copies a product's name and price but no image — so removing one cannot damage an order.
 */
@Service
public class AdminResourceService {

    private final ResourceRepository resourceRepository;
    private final AdminProductService adminProductService;
    private final AdminEventService adminEventService;

    public AdminResourceService(ResourceRepository resourceRepository,
                                AdminProductService adminProductService,
                                AdminEventService adminEventService) {
        this.resourceRepository = resourceRepository;
        this.adminProductService = adminProductService;
        this.adminEventService = adminEventService;
    }

    @Transactional
    public ProductResourceDto createResource(UUID actorUserId, UUID productId,
                                             CreateResourceRequest request) {
        Product product = adminProductService.requireProduct(productId);

        Resource resource = new Resource();
        resource.setName(request.name());
        resource.setUrl(request.url().trim());
        resource.setType(request.type());
        resource.setIsPrimary(request.primary());
        resource.setProduct(product);
        if (request.primary()) {
            demoteOtherPrimaries(product, null);
        }
        product.getResources().add(resource);
        Resource saved = resourceRepository.saveAndFlush(resource);

        adminEventService.record(actorUserId, AdminEventType.RESOURCE_CREATED,
                AdminTargetType.PRODUCT_RESOURCE, saved.getId(),
                "product=" + productId + " url=" + saved.getUrl());
        return AdminProductService.toResourceResponse(saved);
    }

    @Transactional
    public ProductResourceDto updateResource(UUID actorUserId, UUID resourceId,
                                             UpdateResourceRequest request) {
        Resource resource = requireResource(resourceId);
        if (request.name() != null) {
            resource.setName(request.name());
        }
        if (request.url() != null) {
            resource.setUrl(request.url().trim());
        }
        if (request.type() != null) {
            resource.setType(request.type());
        }
        if (request.isPrimary() != null) {
            resource.setIsPrimary(request.isPrimary());
            if (request.isPrimary()) {
                demoteOtherPrimaries(resource.getProduct(), resourceId);
            }
        }

        adminEventService.record(actorUserId, AdminEventType.RESOURCE_UPDATED,
                AdminTargetType.PRODUCT_RESOURCE, resourceId, "url=" + resource.getUrl());
        return AdminProductService.toResourceResponse(resource);
    }

    /**
     * Deletes the row.
     *
     * <p>No replacement primary is promoted: the product simply shows no thumbnail until an
     * administrator picks one. Guessing would put an image on the storefront that nobody chose, and a
     * missing thumbnail is at least visible.
     */
    @Transactional
    public void deleteResource(UUID actorUserId, UUID resourceId) {
        Resource resource = requireResource(resourceId);
        // Both, deliberately: Product maps resources with cascade ALL and no orphanRemoval, so
        // leaving the deleted child in the loaded collection risks a cascade re-persisting it on
        // flush, while removing it from the collection alone would not delete the row.
        resource.getProduct().getResources().remove(resource);
        resourceRepository.delete(resource);

        adminEventService.record(actorUserId, AdminEventType.RESOURCE_DELETED,
                AdminTargetType.PRODUCT_RESOURCE, resourceId,
                "product=" + resource.getProduct().getId() + " url=" + resource.getUrl());
    }

    private Resource requireResource(UUID resourceId) {
        return resourceRepository.findWithProductById(resourceId).orElseThrow(
                () -> new ResourceNotFoundException("Resource " + resourceId + " was not found"));
    }

    /**
     * Leaves at most one primary on the product.
     *
     * <p>No database constraint enforces this. Two primaries would make
     * {@code ProductRepository.findPrimaryThumbnails} return two rows for one product, and its
     * {@code Collectors.toMap} merge function then picks one arbitrarily — a thumbnail that changes
     * between requests.
     *
     * @param keepId the resource being promoted, or null when it does not exist yet
     */
    private static void demoteOtherPrimaries(Product product, UUID keepId) {
        product.getResources().stream()
                .filter(existing -> !existing.getId().equals(keepId))
                .filter(existing -> Boolean.TRUE.equals(existing.getIsPrimary()))
                .forEach(existing -> existing.setIsPrimary(false));
    }
}
