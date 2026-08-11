package com.mvp.ecommercebackend.commerce.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One line of an order, as it was sold.
 *
 * @param productId the catalogue product, or null once it has been withdrawn. Everything a client
 *                  needs to display the line is beside it, so a null here only means "buy again" is
 *                  not offered.
 * @param variantId the catalogue variant, or null once it has been withdrawn
 * @param unitPrice the price agreed at placement, not today's price
 */
public record OrderItemResponse(
        UUID id,
        UUID productId,
        UUID variantId,
        String productName,
        String color,
        String size,
        BigDecimal unitPrice,
        int quantity,
        BigDecimal lineTotal) {
}
