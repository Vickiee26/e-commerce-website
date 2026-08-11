package com.mvp.ecommercebackend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductResourceDto {
    private String id;
    private String name;
    private String url;
    private String type;
    private Boolean isPrimary;
}
