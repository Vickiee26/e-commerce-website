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

    // FIXME(Task 14): returns "Optional[...]" instead of the URL and NPEs on a null isPrimary.
    private String getProductThumbnail(List<Resource> resources) {
        return resources.stream()
                .filter(Resource::getIsPrimary)
                .findFirst()
                .map(Resource::getUrl)
                .toString();
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
