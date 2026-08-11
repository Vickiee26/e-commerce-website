package com.mvp.ecommercebackend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductVariantDto {
    private String id;
    private String color;
    private String size;
    private Integer stockQuantity;
}
