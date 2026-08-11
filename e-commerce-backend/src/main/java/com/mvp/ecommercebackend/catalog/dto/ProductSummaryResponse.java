package com.mvp.ecommercebackend.catalog.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One row in a product listing.
 *
 * <p>Intentionally narrower than {@link ProductDto}: no description, variants or image list. A
 * listing of twenty products would otherwise drag along every variant and image row of each,
 * which is both slow and more than a grid view can use.
 */
public record ProductSummaryResponse(
        UUID id,
        String name,
        BigDecimal price,
        String thumbnail,
        UUID categoryId,
        String categoryName,
        UUID categoryTypeId,
        String categoryTypeName) {
}
