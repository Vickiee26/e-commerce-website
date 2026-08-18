package com.mvp.ecommercebackend.admin.dto;

import java.util.UUID;

/**
 * The result of one adjustment.
 *
 * <p>Both quantities are returned rather than just the new one: an administrator who sees a
 * {@code previousQuantity} they did not expect has just learned that stock moved underneath them,
 * which a bare new total would hide.
 */
public record StockAdjustmentResponse(
        UUID variantId,
        int previousQuantity,
        int newQuantity,
        int delta,
        String reason) {
}
