package com.mvp.ecommercebackend.admin;

import com.mvp.ecommercebackend.admin.dto.AdminProductResponse;
import com.mvp.ecommercebackend.admin.dto.AdminProductSummaryResponse;
import com.mvp.ecommercebackend.admin.dto.AdminVariantResponse;
import com.mvp.ecommercebackend.admin.dto.CreateProductRequest;
import com.mvp.ecommercebackend.admin.dto.UpdateProductRequest;
import com.mvp.ecommercebackend.admin.entity.AdminEventType;
import com.mvp.ecommercebackend.admin.entity.AdminTargetType;
import com.mvp.ecommercebackend.catalog.dto.ProductResourceDto;
import com.mvp.ecommercebackend.catalog.entity.Category;
import com.mvp.ecommercebackend.catalog.entity.CategoryType;
import com.mvp.ecommercebackend.catalog.entity.Product;
import com.mvp.ecommercebackend.catalog.entity.ProductVariant;
import com.mvp.ecommercebackend.catalog.entity.Resource;
import com.mvp.ecommercebackend.catalog.repository.CategoryRepository;
import com.mvp.ecommercebackend.catalog.repository.CategoryTypeRepository;
import com.mvp.ecommercebackend.catalog.repository.ProductRepository;
import com.mvp.ecommercebackend.common.PageResponse;
import com.mvp.ecommercebackend.common.ResourceNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Catalogue writes for administrators.
 *
 * <p>Deliberately not part of {@code ProductService}: that class is entirely
 * {@code @Transactional(readOnly = true)}, and a public browsing path that provably cannot write is
 * worth more than the handful of lines this duplicates.
 */
@Service
public class AdminProductService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final CategoryTypeRepository categoryTypeRepository;
    private final AdminEventService adminEventService;
    private final Clock clock;

    public AdminProductService(ProductRepository productRepository,
                               CategoryRepository categoryRepository,
                               CategoryTypeRepository categoryTypeRepository,
                               AdminEventService adminEventService,
                               Clock clock) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.categoryTypeRepository = categoryTypeRepository;
        this.adminEventService = adminEventService;
        this.clock = clock;
    }

    /**
     * @param archived {@code exclude} (the default), {@code only}, or {@code all}; already
     *                 constrained to that set by the controller
     */
    @Transactional(readOnly = true)
    public PageResponse<AdminProductSummaryResponse> list(String archived, UUID categoryId,
                                                          String searchTerm, int page, int size,
                                                          String sortProperty, String direction) {
        PageRequest pageRequest = PageRequest.of(page, size,
                Sort.by(Sort.Direction.fromString(direction), sortProperty));
        Page<Product> products = productRepository.findAll(
                filters(archived, categoryId, searchTerm), pageRequest);

        Map<UUID, String> thumbnails = thumbnailsFor(products.getContent());
        Map<UUID, ProductRepository.ProductStockSummary> stock = stockFor(products.getContent());
        List<AdminProductSummaryResponse> rows = products.getContent().stream()
                .map(product -> toSummary(product, thumbnails.get(product.getId()),
                        stock.get(product.getId())))
                .toList();
        return PageResponse.of(products, rows);
    }

    /**
     * The administrative detail view.
     *
     * <p>An archived product answers normally rather than 404: an administrator must be able to look
     * at what they retired in order to decide whether to restore it. {@code @Transactional} is
     * load-bearing — this walks four lazy associations and {@code open-in-view} is off.
     */
    @Transactional(readOnly = true)
    public AdminProductResponse getProduct(UUID productId) {
        return toResponse(requireProduct(productId));
    }

    @Transactional
    public AdminProductResponse createProduct(UUID actorUserId, CreateProductRequest request) {
        Category category = requireCategory(request.categoryId());
        CategoryType type = requireTypeOfCategory(request.categoryTypeId(), request.categoryId());

        Product product = new Product();
        product.setName(request.name().trim());
        product.setDescription(request.description());
        product.setPrice(request.price());
        product.setCategory(category);
        product.setCategoryType(type);
        Product saved = productRepository.saveAndFlush(product);

        adminEventService.record(actorUserId, AdminEventType.PRODUCT_CREATED,
                AdminTargetType.PRODUCT, saved.getId(), "name=" + saved.getName());
        return toResponse(saved);
    }

    @Transactional
    public AdminProductResponse updateProduct(UUID actorUserId, UUID productId,
                                              UpdateProductRequest request) {
        Product product = requireProduct(productId);
        if (request.name() != null) {
            product.setName(request.name().trim());
        }
        if (request.description() != null) {
            product.setDescription(request.description());
        }
        if (request.price() != null) {
            product.setPrice(request.price());
        }
        // If either is supplied, validate the pair; the omitted one falls back to the current value.
        if (request.categoryId() != null || request.categoryTypeId() != null) {
            UUID categoryId = request.categoryId() == null
                    ? product.getCategory().getId() : request.categoryId();
            UUID typeId = request.categoryTypeId() == null
                    ? product.getCategoryType().getId() : request.categoryTypeId();
            product.setCategory(requireCategory(categoryId));
            product.setCategoryType(requireTypeOfCategory(typeId, categoryId));
        }

        adminEventService.record(actorUserId, AdminEventType.PRODUCT_UPDATED,
                AdminTargetType.PRODUCT, productId, null);
        return toResponse(product);
    }

    /**
     * Retires a product without deleting it.
     *
     * <p>Idempotent: archiving an already-archived product changes nothing and still succeeds, which
     * is what {@code DELETE} promises. Variants keep their own flags, so restoring later does not
     * resurrect one that was retired separately.
     */
    @Transactional
    public void archiveProduct(UUID actorUserId, UUID productId) {
        Product product = requireProduct(productId);
        if (product.getArchivedAt() != null) {
            return;
        }
        product.setArchivedAt(clock.instant());
        adminEventService.record(actorUserId, AdminEventType.PRODUCT_ARCHIVED,
                AdminTargetType.PRODUCT, productId, "name=" + product.getName());
    }

    /** Idempotent in the same way: restoring a live product is a no-op. */
    @Transactional
    public AdminProductResponse restoreProduct(UUID actorUserId, UUID productId) {
        Product product = requireProduct(productId);
        if (product.getArchivedAt() != null) {
            product.setArchivedAt(null);
            adminEventService.record(actorUserId, AdminEventType.PRODUCT_RESTORED,
                    AdminTargetType.PRODUCT, productId, "name=" + product.getName());
        }
        return toResponse(product);
    }

    /** Shared with {@link AdminVariantService} and {@link AdminResourceService}. */
    Product requireProduct(UUID productId) {
        return productRepository.findWithCategoriesById(productId).orElseThrow(
                () -> new ResourceNotFoundException("Product " + productId + " was not found"));
    }

    private Category requireCategory(UUID categoryId) {
        return categoryRepository.findById(categoryId).orElseThrow(
                () -> new ResourceNotFoundException("Category " + categoryId + " was not found"));
    }

    /**
     * 404 rather than a validation error when the type belongs to a different category: the type does
     * not exist *within the category named by the request*, and the two ids are only meaningful
     * together.
     */
    private CategoryType requireTypeOfCategory(UUID categoryTypeId, UUID categoryId) {
        CategoryType type = categoryTypeRepository.findById(categoryTypeId).orElseThrow(
                () -> new ResourceNotFoundException(
                        "Category type " + categoryTypeId + " was not found"));
        if (!type.getCategory().getId().equals(categoryId)) {
            throw new ResourceNotFoundException("Category type " + categoryTypeId
                    + " does not belong to category " + categoryId);
        }
        return type;
    }

    private Specification<Product> filters(String archived, UUID categoryId, String searchTerm) {
        List<Specification<Product>> filters = new ArrayList<>();
        switch (archived.toLowerCase(Locale.ROOT)) {
            case "only" -> filters.add(AdminProductSpecifications.archivedOnly());
            case "all" -> { /* no predicate: archived and live rows both included */ }
            default -> filters.add(AdminProductSpecifications.notArchived());
        }
        if (categoryId != null) {
            filters.add(AdminProductSpecifications.inCategory(categoryId));
        }
        if (searchTerm != null && !searchTerm.isBlank()) {
            filters.add(AdminProductSpecifications.nameContains(searchTerm.trim()));
        }
        return filters.isEmpty() ? Specification.unrestricted() : Specification.allOf(filters);
    }

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

    private Map<UUID, ProductRepository.ProductStockSummary> stockFor(List<Product> products) {
        if (products.isEmpty()) {
            return Map.of();
        }
        List<UUID> productIds = products.stream().map(Product::getId).toList();
        return productRepository.findStockSummaries(productIds).stream()
                .collect(Collectors.toMap(
                        ProductRepository.ProductStockSummary::getProductId, summary -> summary));
    }

    private static AdminProductSummaryResponse toSummary(
            Product product, String thumbnail, ProductRepository.ProductStockSummary stock) {
        return new AdminProductSummaryResponse(
                product.getId(),
                product.getName(),
                product.getPrice(),
                thumbnail,
                product.getCategory().getId(),
                product.getCategory().getName(),
                product.getCategoryType().getId(),
                product.getCategoryType().getName(),
                // Null when a product has no unarchived variant: the grouped query returns no row.
                stock == null ? 0L : stock.getVariantCount(),
                stock == null ? 0L : stock.getTotalStock(),
                product.getArchivedAt());
    }

    /** Shared with {@link AdminVariantService} and {@link AdminResourceService}. */
    static AdminProductResponse toResponse(Product product) {
        List<AdminVariantResponse> variants = product.getProductVariants().stream()
                .map(AdminProductService::toVariantResponse)
                .toList();
        List<ProductResourceDto> resources = product.getResources().stream()
                .map(AdminProductService::toResourceResponse)
                .toList();

        return new AdminProductResponse(product.getId(), product.getName(),
                product.getDescription(), product.getPrice(),
                product.getCategory().getId(), product.getCategory().getName(),
                product.getCategoryType().getId(), product.getCategoryType().getName(),
                product.getArchivedAt(), variants, resources);
    }

    static AdminVariantResponse toVariantResponse(ProductVariant variant) {
        return new AdminVariantResponse(variant.getId(), variant.getColor(), variant.getSize(),
                variant.getStockQuantity(), variant.getArchivedAt());
    }

    /** Kept beside the response mapping so the two shapes cannot drift apart. */
    static ProductResourceDto toResourceResponse(Resource resource) {
        return ProductResourceDto.builder()
                .id(resource.getId())
                .name(resource.getName())
                .url(resource.getUrl())
                .type(resource.getType())
                .isPrimary(resource.getIsPrimary())
                .build();
    }
}
