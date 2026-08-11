package com.mvp.ecommercebackend.catalog;

import com.mvp.ecommercebackend.catalog.dto.ProductDto;
import com.mvp.ecommercebackend.catalog.entity.Product;
import com.mvp.ecommercebackend.catalog.repository.ProductRepository;
import com.mvp.ecommercebackend.common.ResourceNotFoundException;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class ProductService {

    private final ProductRepository productRepository;
    private final ProductMapper productMapper;

    public ProductService(ProductRepository productRepository, ProductMapper productMapper) {
        this.productRepository = productRepository;
        this.productMapper = productMapper;
    }

    // FIXME(Task 14): needs @Transactional(readOnly = true); walks four lazy associations.
    public ProductDto getProductById(UUID id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product Not Found!"));
        ProductDto productDto = productMapper.mapProductToDto(product);
        productDto.setCategoryId(product.getCategory().getId());
        productDto.setCategoryTypeId(product.getCategoryType().getId());
        productDto.setVariants(productMapper.mapProductVariantListToDto(product.getProductVariants()));
        productDto.setProductResources(productMapper.mapProductResourcesListDto(product.getResources()));
        return productDto;
    }
}
