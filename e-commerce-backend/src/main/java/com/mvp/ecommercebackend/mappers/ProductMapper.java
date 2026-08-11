package com.mvp.ecommercebackend.mappers;

import com.mvp.ecommercebackend.dto.ProductDto;
import com.mvp.ecommercebackend.dto.ProductResourceDto;
import com.mvp.ecommercebackend.dto.ProductVariantDto;
import com.mvp.ecommercebackend.entities.Product;
import com.mvp.ecommercebackend.entities.ProductVariant;
import com.mvp.ecommercebackend.entities.Resource;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ProductMapper {
    public ProductDto mapProductToDto(Product product) {
        return ProductDto.builder()
                .id(product.getId())
                .name(product.getName())
                .price(product.getPrice())
                .description(product.getDescription())
                .thumbnail(getProductThumbnail(product.getResources())).build();
    }

    private String getProductThumbnail(List<Resource> resources) {
        return resources.stream().filter(Resource::getIsPrimary).findFirst().map(Resource::getUrl).toString();
    }

    public List<ProductVariantDto> mapProductVariantListToDto(List<ProductVariant> productVariants) {
        return productVariants.stream().map(this::mapProductVariantDto).toList();
    }

    private ProductVariantDto mapProductVariantDto(ProductVariant productVariant) {
        return ProductVariantDto.builder()
                .color(productVariant.getColor())
                .id(productVariant.getId())
                .size(productVariant.getSize())
                .stockQuantity(productVariant.getStockQuantity())
                .build();
    }

    public List<ProductResourceDto> mapProductResourcesListDto(List<Resource> resources) {
        return resources.stream().map(this::mapResourceToDto).toList();
    }

    private ProductResourceDto mapResourceToDto(Resource resources) {
        return ProductResourceDto.builder()
                .id(resources.getId())
                .url(resources.getUrl())
                .name(resources.getName())
                .isPrimary(resources.getIsPrimary())
                .type(resources.getType())
                .build();
    }
}
