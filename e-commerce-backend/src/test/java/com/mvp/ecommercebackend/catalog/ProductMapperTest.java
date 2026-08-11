package com.mvp.ecommercebackend.catalog;

import com.mvp.ecommercebackend.catalog.dto.ProductDto;
import com.mvp.ecommercebackend.catalog.entity.Product;
import com.mvp.ecommercebackend.catalog.entity.Resource;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the thumbnail selection, which is where the original defect lived.
 *
 * <p>A unit test rather than an integration test for the null-flag case specifically: {@code
 * is_primary} is {@code NOT NULL} in {@code V1__init.sql}, so a null can never come back from the
 * database. It can still arrive on an entity that has not been persisted, which is exactly what
 * makes the crash easy to reintroduce and invisible to a database-backed test.
 */
class ProductMapperTest {

    private final ProductMapper mapper = new ProductMapper();

    private static Resource image(String url, Boolean primary) {
        Resource resource = new Resource();
        resource.setUrl(url);
        resource.setIsPrimary(primary);
        return resource;
    }

    private static Product productWith(List<Resource> resources) {
        Product product = new Product();
        product.setName("Trail Runner");
        product.setPrice(new BigDecimal("129.99"));
        product.getResources().addAll(resources);
        return product;
    }

    @Test
    void usesThePrimaryImageUrlAsTheThumbnail() {
        ProductDto dto = mapper.mapProductToDto(productWith(List.of(
                image("https://cdn.example.com/side.jpg", false),
                image("https://cdn.example.com/hero.jpg", true))));

        assertThat(dto.getThumbnail()).isEqualTo("https://cdn.example.com/hero.jpg");
    }

    @Test
    void leavesTheThumbnailNullWhenNoImageIsPrimary() {
        ProductDto dto = mapper.mapProductToDto(productWith(List.of(
                image("https://cdn.example.com/side.jpg", false))));

        // Not the string "Optional.empty".
        assertThat(dto.getThumbnail()).isNull();
    }

    @Test
    void leavesTheThumbnailNullWhenThereAreNoImages() {
        assertThat(mapper.mapProductToDto(productWith(List.of())).getThumbnail()).isNull();
    }

    @Test
    void toleratesAnImageWithANullPrimaryFlag() {
        ProductDto dto = mapper.mapProductToDto(productWith(List.of(
                image("https://cdn.example.com/unflagged.jpg", null),
                image("https://cdn.example.com/hero.jpg", true))));

        assertThat(dto.getThumbnail()).isEqualTo("https://cdn.example.com/hero.jpg");
    }

    @Test
    void treatsAnEntirelyUnflaggedImageSetAsHavingNoPrimary() {
        ProductDto dto = mapper.mapProductToDto(productWith(List.of(
                image("https://cdn.example.com/unflagged.jpg", null))));

        assertThat(dto.getThumbnail()).isNull();
    }
}
