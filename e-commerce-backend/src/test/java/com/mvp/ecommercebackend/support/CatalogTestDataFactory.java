package com.mvp.ecommercebackend.support;

import com.mvp.ecommercebackend.catalog.entity.Category;
import com.mvp.ecommercebackend.catalog.entity.CategoryType;
import com.mvp.ecommercebackend.catalog.entity.Product;
import com.mvp.ecommercebackend.catalog.entity.ProductVariant;
import com.mvp.ecommercebackend.catalog.entity.Resource;
import com.mvp.ecommercebackend.catalog.repository.CategoryRepository;
import com.mvp.ecommercebackend.catalog.repository.ProductRepository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Builds committed catalog fixtures.
 *
 * <p>Everything is saved through the real repositories with real cascades, so a test that passes
 * here is passing against the same persistence behaviour production gets.
 */
public class CatalogTestDataFactory {

    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;

    public CatalogTestDataFactory(CategoryRepository categoryRepository,
                                  ProductRepository productRepository) {
        this.categoryRepository = categoryRepository;
        this.productRepository = productRepository;
    }

    /**
     * Creates a category holding a single type and returns that type, since a product needs both
     * and the pair is almost always created together.
     */
    public CategoryType createCategoryWithType(String categoryName, String typeName) {
        Category category = new Category();
        category.setName(categoryName);
        category.setCode(slug(categoryName));
        category.setDescription(categoryName + " description");

        CategoryType type = new CategoryType();
        type.setName(typeName);
        type.setCode(slug(typeName));
        type.setCategory(category);
        category.getCategoryTypes().add(type);

        // Cascade ALL from Category persists the type in the same flush.
        // get(0), not getFirst(): SequencedCollection is Java 21 and this project targets 17.
        return categoryRepository.saveAndFlush(category).getCategoryTypes().get(0);
    }

    /**
     * Adds a second (or third) type to an existing category.
     *
     * <p>Needed because {@code categories.code} is unique, so calling
     * {@link #createCategoryWithType} twice with the same category name violates that constraint.
     * See {@link #addImage} for why the owner is re-read.
     */
    @Transactional
    public CategoryType addCategoryType(Category category, String typeName) {
        Category owner = categoryRepository.findById(category.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "Fixture category " + category.getId() + " is not in the database"));
        CategoryType type = new CategoryType();
        type.setName(typeName);
        type.setCode(slug(typeName));
        type.setDescription(typeName + " description");
        type.setCategory(owner);
        owner.getCategoryTypes().add(type);

        return categoryRepository.saveAndFlush(owner).getCategoryTypes().stream()
                .filter(saved -> slug(typeName).equals(saved.getCode()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Type " + typeName + " was not saved"));
    }

    public Product createProduct(CategoryType type, String name, String price) {
        Product product = new Product();
        product.setName(name);
        product.setDescription(name + " description");
        product.setPrice(new BigDecimal(price));
        product.setCategory(type.getCategory());
        product.setCategoryType(type);
        return productRepository.saveAndFlush(product);
    }

    /**
     * Adds an image. Cascade ALL from Product persists it on save.
     *
     * <p>Only {@code product.getId()} is read from the argument; the owner is re-read inside this
     * method's transaction. That matters. Mutating the caller's detached instance and merging it
     * would insert every previously added child a second time, because merge copies the generated
     * ids onto its own managed instance and leaves the caller's copy with null ids. Re-reading also
     * means the lazy collections are initialised, so a test can add an image and a variant in
     * either order.
     */
    @Transactional
    public Product addImage(Product product, String url, boolean primary) {
        Product owner = reload(product);
        Resource resource = new Resource();
        resource.setName(url.substring(url.lastIndexOf('/') + 1));
        resource.setUrl(url);
        resource.setType("IMAGE");
        resource.setIsPrimary(primary);
        resource.setProduct(owner);
        owner.getResources().add(resource);
        return productRepository.saveAndFlush(owner);
    }

    /** Adds a variant. See {@link #addImage} for why the owner is re-read. */
    @Transactional
    public Product addVariant(Product product, String color, String size, int stockQuantity) {
        Product owner = reload(product);
        ProductVariant variant = new ProductVariant();
        variant.setColor(color);
        variant.setSize(size);
        variant.setStockQuantity(stockQuantity);
        variant.setProduct(owner);
        owner.getProductVariants().add(variant);
        return productRepository.saveAndFlush(owner);
    }

    private Product reload(Product product) {
        return productRepository.findById(product.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "Fixture product " + product.getId() + " is not in the database"));
    }

    private static String slug(String value) {
        return value.toLowerCase().replace(' ', '-');
    }
}
