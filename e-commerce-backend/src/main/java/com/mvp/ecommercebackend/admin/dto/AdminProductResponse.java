package com.mvp.ecommercebackend.admin.dto;

import com.mvp.ecommercebackend.catalog.dto.ProductResourceDto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The administrative detail view.
 *
 * <p>Distinct from {@code ProductDto} rather than an extension of it: this one includes archived
 * variants and {@code archivedAt}, and the public shape must not start leaking either.
 */
public record AdminProductResponse(
        UUID id,
        String name,
        String description,
        BigDecimal price,
        UUID categoryId,
        String categoryName,
        UUID categoryTypeId,
        String categoryTypeName,
        Instant archivedAt,
        List<AdminVariantResponse> variants,
        List<ProductResourceDto> resources) {
}
