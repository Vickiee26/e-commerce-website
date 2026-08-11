package com.mvp.ecommercebackend.catalog.dto;

import java.util.List;
import java.util.UUID;

/**
 * A category with its types nested, so a storefront can build its whole navigation tree from one
 * request rather than one call per category.
 */
public record CategoryResponse(
        UUID id,
        String code,
        String name,
        String description,
        List<CategoryTypeResponse> types) {
}
