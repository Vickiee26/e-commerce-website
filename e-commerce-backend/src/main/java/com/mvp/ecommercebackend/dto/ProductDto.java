package com.mvp.ecommercebackend.dto;

import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.Data;
import lombok.Builder;

import java.math.BigDecimal;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductDto {
    private String id;
    private String name;
    private String description;
    private BigDecimal price;
    private String thumbnail;
    private String categoryId;
    private String categoryName;
    private String categoryTypeId;
    private String categoryTypeName;
    private List<ProductVariantDto> variants;
    private List<ProductResourceDto> productResources;
}
