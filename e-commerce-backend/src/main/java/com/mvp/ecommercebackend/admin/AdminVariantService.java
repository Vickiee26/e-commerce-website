package com.mvp.ecommercebackend.admin;

import com.mvp.ecommercebackend.admin.dto.AdminVariantResponse;
import com.mvp.ecommercebackend.admin.dto.CreateVariantRequest;
import com.mvp.ecommercebackend.admin.dto.UpdateVariantRequest;
import com.mvp.ecommercebackend.admin.entity.AdminEventType;
import com.mvp.ecommercebackend.admin.entity.AdminTargetType;
import com.mvp.ecommercebackend.catalog.entity.Product;
import com.mvp.ecommercebackend.catalog.entity.ProductVariant;
import com.mvp.ecommercebackend.catalog.repository.ProductVariantRepository;
import com.mvp.ecommercebackend.common.DuplicateResourceException;
import com.mvp.ecommercebackend.common.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/** Variant writes for administrators. Stock changes live in {@link AdminStockService}. */
@Service
public class AdminVariantService {

    private final ProductVariantRepository variantRepository;
    private final AdminProductService adminProductService;
    private final AdminEventService adminEventService;
    private final Clock clock;

    public AdminVariantService(ProductVariantRepository variantRepository,
                               AdminProductService adminProductService,
                               AdminEventService adminEventService,
                               Clock clock) {
        this.variantRepository = variantRepository;
        this.adminProductService = adminProductService;
        this.adminEventService = adminEventService;
        this.clock = clock;
    }

    /**
     * Adds a variant, optionally with an opening stock balance.
     *
     * <p>Permitted on an archived product: an administrator preparing a product for restore should
     * not have to un-archive it first, and an archived product hides from the catalogue regardless of
     * its variants' own flags.
     */
    @Transactional
    public AdminVariantResponse createVariant(UUID actorUserId, UUID productId,
                                              CreateVariantRequest request) {
        Product product = adminProductService.requireProduct(productId);
        requireNoLiveDuplicate(product, request.color(), request.size(), null);

        ProductVariant variant = new ProductVariant();
        variant.setColor(request.color().trim());
        variant.setSize(request.size().trim());
        variant.setStockQuantity(request.openingBalance());
        variant.setProduct(product);
        product.getProductVariants().add(variant);
        ProductVariant saved = variantRepository.saveAndFlush(variant);

        adminEventService.record(actorUserId, AdminEventType.VARIANT_CREATED,
                AdminTargetType.PRODUCT_VARIANT, saved.getId(),
                "product=" + productId + " " + saved.getColor() + "/" + saved.getSize()
                        + " opening=" + saved.getStockQuantity());
        return AdminProductService.toVariantResponse(saved);
    }

    @Transactional
    public AdminVariantResponse updateVariant(UUID actorUserId, UUID variantId,
                                              UpdateVariantRequest request) {
        ProductVariant variant = requireVariant(variantId);

        // Determine effective color and size after the patch
        String effectiveColor = request.color() != null ? request.color().trim() : variant.getColor();
        String effectiveSize = request.size() != null ? request.size().trim() : variant.getSize();

        // Check for duplicates only if at least one field is being changed
        if (request.color() != null || request.size() != null) {
            Product product = variant.getProduct();
            requireNoLiveDuplicate(product, effectiveColor, effectiveSize, variantId);
        }

        if (request.color() != null) {
            variant.setColor(request.color().trim());
        }
        if (request.size() != null) {
            variant.setSize(request.size().trim());
        }

        adminEventService.record(actorUserId, AdminEventType.VARIANT_UPDATED,
                AdminTargetType.PRODUCT_VARIANT, variantId,
                variant.getColor() + "/" + variant.getSize());
        return AdminProductService.toVariantResponse(variant);
    }

    /** Idempotent, like {@code AdminProductService.archiveProduct}. */
    @Transactional
    public void archiveVariant(UUID actorUserId, UUID variantId) {
        ProductVariant variant = requireVariant(variantId);
        if (variant.getArchivedAt() != null) {
            return;
        }
        variant.setArchivedAt(clock.instant());
        adminEventService.record(actorUserId, AdminEventType.VARIANT_ARCHIVED,
                AdminTargetType.PRODUCT_VARIANT, variantId,
                variant.getColor() + "/" + variant.getSize());
    }

    @Transactional
    public AdminVariantResponse restoreVariant(UUID actorUserId, UUID variantId) {
        ProductVariant variant = requireVariant(variantId);
        if (variant.getArchivedAt() != null) {
            // Check if the slot is still free before un-archiving
            Product product = variant.getProduct();
            boolean slotTaken = product.getProductVariants().stream()
                    .filter(existing -> existing.getArchivedAt() == null)
                    .filter(existing -> !existing.getId().equals(variantId))
                    .anyMatch(existing -> variant.getColor().equalsIgnoreCase(existing.getColor())
                            && variant.getSize().equalsIgnoreCase(existing.getSize()));
            if (slotTaken) {
                throw new DuplicateResourceException("Cannot restore variant " + variantId
                        + ": product " + product.getId() + " already has a live variant "
                        + variant.getColor() + "/" + variant.getSize());
            }

            variant.setArchivedAt(null);
            adminEventService.record(actorUserId, AdminEventType.VARIANT_RESTORED,
                    AdminTargetType.PRODUCT_VARIANT, variantId,
                    variant.getColor() + "/" + variant.getSize());
        }
        return AdminProductService.toVariantResponse(variant);
    }

    /** Shared with {@link AdminStockService}. */
    ProductVariant requireVariant(UUID variantId) {
        return variantRepository.findById(variantId).orElseThrow(
                () -> new ResourceNotFoundException("Variant " + variantId + " was not found"));
    }

    /**
     * Two live "Black / 42" rows on one product would show a customer the same option twice and split
     * its stock across two rows.
     *
     * <p>Archived rows are ignored, so retiring a variant frees its colour and size for reuse. This
     * check is case-insensitive and trims whitespace, so "Black/42" and "black / 42" are treated as
     * duplicates. The partial unique index {@code uq_product_variants_live} on {@code (product_id,
     * color, size) WHERE archived_at IS NULL} guarantees exact-duplicate safety under a lost race, but
     * does not enforce case or whitespace normalization — this service-level check is stricter.
     *
     * @param excludeId if non-null, exclude this variant from the duplicate check (for updates where a
     *                  variant is being patched to its own values or to values that would not conflict
     *                  with itself)
     */
    private static void requireNoLiveDuplicate(Product product, String color, String size,
                                               UUID excludeId) {
        boolean duplicate = product.getProductVariants().stream()
                .filter(existing -> existing.getArchivedAt() == null)
                .filter(existing -> excludeId == null || !existing.getId().equals(excludeId))
                .anyMatch(existing -> color.trim().equalsIgnoreCase(existing.getColor())
                        && size.trim().equalsIgnoreCase(existing.getSize()));
        if (duplicate) {
            throw new DuplicateResourceException("Product " + product.getId()
                    + " already has a variant " + color.trim() + "/" + size.trim());
        }
    }
}
