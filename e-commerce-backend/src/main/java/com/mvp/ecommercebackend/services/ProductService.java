package com.mvp.ecommercebackend.services;

import com.mvp.ecommercebackend.dto.ProductDto;
import com.mvp.ecommercebackend.entities.Product;
import com.mvp.ecommercebackend.exceptions.ResourceNotFoundException;
import com.mvp.ecommercebackend.mappers.ProductMapper;
import com.mvp.ecommercebackend.repositories.ProductRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ProductService {
    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductMapper productMapper;

    public ProductDto getProductById(String id) {
        Product product = productRepository.findById(id).orElseThrow(()-> new ResourceNotFoundException("Product Not Found!"));
        ProductDto productDto = productMapper.mapProductToDto(product);
        productDto.setCategoryId(product.getCategory().getId());
        productDto.setCategoryTypeId(product.getCategoryType().getId());
        productDto.setVariants(productMapper.mapProductVariantListToDto(product.getProductVariants()));
        productDto.setProductResources(productMapper.mapProductResourcesListDto(product.getResources()));
        return productDto;
    }
}
