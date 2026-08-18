package com.mvp.ecommercebackend.admin.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * A variant as an administrator sees it.
 *
 * <p>Wider than the public {@code ProductVariantDto}: it carries {@code archivedAt}, because an
 * administrator needs to see what has been retired in order to restore it.
 */
public record AdminVariantResponse(
        UUID id,
        String color,
        String size,
        Integer stockQuantity,
        Instant archivedAt) {
}
