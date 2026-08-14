package com.mvp.ecommercebackend.admin.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One row of the administrative product listing.
 *
 * @param variantCount unarchived variants only
 * @param totalStock   summed over unarchived variants only, so it answers "what can I sell"
 */
public record AdminProductSummaryResponse(
        UUID id,
        String name,
        BigDecimal price,
        String thumbnail,
        UUID categoryId,
        String categoryName,
        UUID categoryTypeId,
        String categoryTypeName,
        long variantCount,
        long totalStock,
        Instant archivedAt) {
}
