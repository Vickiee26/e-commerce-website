package com.mvp.ecommercebackend.catalog.dto;

import java.util.UUID;

/** A subdivision of a category, e.g. "Running Shoes" within "Footwear". */
public record CategoryTypeResponse(UUID id, String code, String name, String description) {
}
