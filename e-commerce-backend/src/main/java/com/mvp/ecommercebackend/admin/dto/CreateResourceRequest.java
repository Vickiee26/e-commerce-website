package com.mvp.ecommercebackend.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param url       required; capped at 1000 to match {@code product_resources.url varchar(1000)}
 * @param isPrimary optional. The column is {@code NOT NULL DEFAULT false}, so a missing value means
 *                  false. Setting it true demotes the product's other resources.
 */
public record CreateResourceRequest(
        @Size(max = 255) String name,
        @NotBlank @Size(max = 1000) String url,
        @Size(max = 30) String type,
        Boolean isPrimary) {

    public boolean primary() {
        return Boolean.TRUE.equals(isPrimary);
    }
}
