package com.mvp.ecommercebackend.catalog;

import com.mvp.ecommercebackend.catalog.dto.ProductDto;
import com.mvp.ecommercebackend.catalog.dto.ProductSummaryResponse;
import com.mvp.ecommercebackend.catalog.entity.Product;
import com.mvp.ecommercebackend.catalog.repository.ProductRepository;
import com.mvp.ecommercebackend.common.PageResponse;
import com.mvp.ecommercebackend.common.ResourceNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

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
        // 404, not 410 or an empty body: to a customer an archived product does not exist, and the
        // same message an unknown id gets means the endpoint does not leak that it ever did.
        if (product.getArchivedAt() != null) {
            throw new ResourceNotFoundException("Product Not Found!");
        }
        ProductDto productDto = productMapper.mapProductToDto(product);
        productDto.setCategoryId(product.getCategory().getId());
        productDto.setCategoryName(product.getCategory().getName());
        productDto.setCategoryTypeId(product.getCategoryType().getId());
        productDto.setCategoryTypeName(product.getCategoryType().getName());
        // Archived variants are filtered out here rather than in the mapper: this list is what a
        // storefront renders as buyable options, so an unbuyable one has no business being in it.
        productDto.setVariants(productMapper.mapProductVariantListToDto(
                product.getProductVariants().stream()
                        .filter(variant -> variant.getArchivedAt() == null)
                        .toList()));
        productDto.setProductResources(
                productMapper.mapProductResourcesListDto(product.getResources()));
        return productDto;
    }

    /**
     * A page of summary rows, optionally filtered.
     *
     * <p>Callers get an empty page rather than a 404 when nothing matches: no products in a
     * category is a legitimate answer to a legitimate question, not a missing resource.
     *
     * <p>{@code sortProperty} and {@code direction} are already constrained to a whitelist by the
     * controller. That matters — handing an arbitrary string to {@link Sort} lets a caller probe
     * the entity model, and an unknown property surfaces as a 500.
     */
    @Transactional(readOnly = true)
    public PageResponse<ProductSummaryResponse> list(UUID categoryId, UUID categoryTypeId,
                                                     String searchTerm, int page, int size,
                                                     String sortProperty, String direction) {
        PageRequest pageRequest = PageRequest.of(page, size,
                Sort.by(Sort.Direction.fromString(direction), sortProperty));
        Page<Product> products = productRepository.findAll(
                filters(categoryId, categoryTypeId, searchTerm), pageRequest);

        Map<UUID, String> thumbnails = thumbnailsFor(products.getContent());
        List<ProductSummaryResponse> rows = products.getContent().stream()
                .map(product -> toSummary(product, thumbnails.get(product.getId())))
                .toList();
        return PageResponse.of(products, rows);
    }

    private Specification<Product> filters(UUID categoryId, UUID categoryTypeId, String searchTerm) {
        // Always first and never optional: archived products are not for sale.
        List<Specification<Product>> filters = new ArrayList<>();
        filters.add(ProductSpecifications.notArchived());
        if (categoryId != null) {
            filters.add(ProductSpecifications.inCategory(categoryId));
        }
        if (categoryTypeId != null) {
            filters.add(ProductSpecifications.inCategoryType(categoryTypeId));
        }
        if (searchTerm != null && !searchTerm.isBlank()) {
            filters.add(ProductSpecifications.nameContains(searchTerm.trim()));
        }
        return Specification.allOf(filters);
    }

    /**
     * Primary image urls for a page of products, keyed by product id.
     *
     * <p>The merge function keeps the first url: nothing in the schema stops two rows of the same
     * product being flagged primary, and a listing must not blow up over it.
     */
    private Map<UUID, String> thumbnailsFor(List<Product> products) {
        if (products.isEmpty()) {
            return Map.of();
        }
        List<UUID> productIds = products.stream().map(Product::getId).toList();
        return productRepository.findPrimaryThumbnails(productIds).stream()
                .collect(Collectors.toMap(ProductRepository.ProductThumbnail::getProductId,
                        ProductRepository.ProductThumbnail::getUrl,
                        (first, duplicate) -> first));
    }

    private static ProductSummaryResponse toSummary(Product product, String thumbnail) {
        return new ProductSummaryResponse(
                product.getId(),
                product.getName(),
                product.getPrice(),
                thumbnail,
                product.getCategory().getId(),
                product.getCategory().getName(),
                product.getCategoryType().getId(),
                product.getCategoryType().getName());
    }
}
