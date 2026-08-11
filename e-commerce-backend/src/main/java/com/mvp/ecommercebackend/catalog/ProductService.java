package com.mvp.ecommercebackend.catalog;

import com.mvp.ecommercebackend.catalog.dto.ProductDto;
import com.mvp.ecommercebackend.catalog.entity.Product;
import com.mvp.ecommercebackend.catalog.repository.ProductRepository;
import com.mvp.ecommercebackend.common.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class ProductService {

    private final ProductRepository productRepository;
    private final ProductMapper productMapper;

    public ProductService(ProductRepository productRepository, ProductMapper productMapper) {
        this.productRepository = productRepository;
        this.productMapper = productMapper;
    }

    /**
     * The full detail view of one product.
     *
     * <p>{@code @Transactional} is load-bearing, not decoration: this method walks four lazy
     * associations, and {@code spring.jpa.open-in-view=false} means there is no session outside a
     * transaction. Without it every one of these reads throws {@code LazyInitializationException}.
     */
    @Transactional(readOnly = true)
    public ProductDto getProductById(UUID id) {
        Product product = productRepository.findWithCategoriesById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product Not Found!"));
        ProductDto productDto = productMapper.mapProductToDto(product);
        productDto.setCategoryId(product.getCategory().getId());
        productDto.setCategoryName(product.getCategory().getName());
        productDto.setCategoryTypeId(product.getCategoryType().getId());
        productDto.setCategoryTypeName(product.getCategoryType().getName());
        productDto.setVariants(productMapper.mapProductVariantListToDto(product.getProductVariants()));
        productDto.setProductResources(productMapper.mapProductResourcesListDto(product.getResources()));
        return productDto;
    }
}
