package com.mvp.ecommercebackend.catalog;

import com.mvp.ecommercebackend.catalog.dto.ProductDto;
import com.mvp.ecommercebackend.catalog.dto.ProductResourceDto;
import com.mvp.ecommercebackend.catalog.dto.ProductVariantDto;
import com.mvp.ecommercebackend.catalog.entity.Product;
import com.mvp.ecommercebackend.catalog.entity.ProductVariant;
import com.mvp.ecommercebackend.catalog.entity.Resource;
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
                .thumbnail(getProductThumbnail(product.getResources()))
                .build();
    }

    /**
     * The primary image's URL, or null when no image is flagged primary.
     *
     * <p>{@code Boolean.TRUE.equals(...)} rather than a method reference: {@code isPrimary} is a
     * {@code Boolean}, so unboxing a null in the filter throws.
     */
    private String getProductThumbnail(List<Resource> resources) {
        return resources.stream()
                .filter(resource -> Boolean.TRUE.equals(resource.getIsPrimary()))
                .findFirst()
                .map(Resource::getUrl)
                .orElse(null);
    }

    public List<ProductVariantDto> mapProductVariantListToDto(List<ProductVariant> productVariants) {
        return productVariants.stream().map(this::mapProductVariantDto).toList();
    }

    private ProductVariantDto mapProductVariantDto(ProductVariant productVariant) {
        return ProductVariantDto.builder()
                .id(productVariant.getId())
                .color(productVariant.getColor())
                .size(productVariant.getSize())
                .stockQuantity(productVariant.getStockQuantity())
                .build();
    }

    public List<ProductResourceDto> mapProductResourcesListDto(List<Resource> resources) {
        return resources.stream().map(this::mapResourceToDto).toList();
    }

    private ProductResourceDto mapResourceToDto(Resource resource) {
        return ProductResourceDto.builder()
                .id(resource.getId())
                .url(resource.getUrl())
                .name(resource.getName())
                .isPrimary(resource.getIsPrimary())
                .type(resource.getType())
                .build();
    }
}
