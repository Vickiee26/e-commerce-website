package com.mvp.ecommercebackend.commerce.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One line of the caller's cart.
 *
 * @param unitPrice      the catalogue price as at this request, not a price captured when the line
 *                       was added
 * @param availableStock current stock for the variant, so a client can warn before checkout rather
 *                       than after a 409
 */
public record CartItemResponse(
        UUID id,
        UUID productId,
        String productName,
        String thumbnail,
        UUID variantId,
        String color,
        String size,
        BigDecimal unitPrice,
        int quantity,
        BigDecimal lineTotal,
        int availableStock) {
}
