package com.mvp.ecommercebackend.admin;

import com.mvp.ecommercebackend.admin.dto.AdjustStockRequest;
import com.mvp.ecommercebackend.admin.dto.StockAdjustmentResponse;
import com.mvp.ecommercebackend.admin.entity.AdminEventType;
import com.mvp.ecommercebackend.admin.entity.AdminTargetType;
import com.mvp.ecommercebackend.catalog.entity.ProductVariant;
import com.mvp.ecommercebackend.catalog.repository.ProductVariantRepository;
import com.mvp.ecommercebackend.common.InsufficientStockException;
import com.mvp.ecommercebackend.common.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * The only path that changes stock outside checkout.
 *
 * <p>Signed deltas applied under a row lock, never an absolute set. An absolute setter would let an
 * administrator's stale read undo a concurrent sale: read 10, a customer buys one leaving 9, write
 * 10, and a sold unit is back on the shelf. {@code AdminStockConcurrencyIT} proves the lock works.
 */
@Service
public class AdminStockService {

    private final ProductVariantRepository variantRepository;
    private final AdminEventService adminEventService;

    public AdminStockService(ProductVariantRepository variantRepository,
                             AdminEventService adminEventService) {
        this.variantRepository = variantRepository;
        this.adminEventService = adminEventService;
    }

    /**
     * Applies {@code delta} to the variant's stock.
     *
     * <p>Permitted on an archived variant, so a count can be corrected before restoring it.
     *
     * @throws InsufficientStockException when the result would be negative; the transaction rolls
     *                                    back, so no audit row survives either
     */
    @Transactional
    public StockAdjustmentResponse adjustStock(UUID actorUserId, UUID variantId,
                                               AdjustStockRequest request) {
        // The lock, not findById: everything after this reads and writes the row we hold.
        ProductVariant variant = variantRepository.lockById(variantId).orElseThrow(
                () -> new ResourceNotFoundException("Variant " + variantId + " was not found"));

        int previous = variant.getStockQuantity();
        int updated = previous + request.delta();
        if (updated < 0) {
            throw new InsufficientStockException("Variant " + variantId + " holds " + previous
                    + ", so a change of " + request.delta() + " would leave " + updated);
        }
        variant.setStockQuantity(updated);

        adminEventService.record(actorUserId, AdminEventType.STOCK_ADJUSTED,
                AdminTargetType.PRODUCT_VARIANT, variantId,
                "delta=" + request.delta() + " " + previous + "->" + updated
                        + " reason=" + request.reason().trim());
        return new StockAdjustmentResponse(variantId, previous, updated, request.delta(),
                request.reason().trim());
    }
}
