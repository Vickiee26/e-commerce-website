package com.mvp.ecommercebackend.catalog.repository;

import com.mvp.ecommercebackend.catalog.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProductRepository extends JpaRepository<Product, UUID>,
        JpaSpecificationExecutor<Product> {

    List<Product> findAllByCategoryId(UUID categoryId);

    /**
     * A product with its category and type already loaded, so naming them costs no extra query.
     *
     * <p>The two collections are deliberately left out of the graph: they are both {@code List}s,
     * and join-fetching two bags in one query throws {@code MultipleBagFetchException}. They load
     * lazily inside the caller's transaction instead.
     */
    @EntityGraph(attributePaths = {"category", "categoryType"})
    Optional<Product> findWithCategoriesById(UUID id);

    /**
     * Overridden purely to attach the entity graph, so a page of products arrives with the parents
     * a summary row needs to name. Both are to-one associations, so this is a join rather than a
     * collection fetch and pagination stays in the database.
     */
    @Override
    @EntityGraph(attributePaths = {"category", "categoryType"})
    Page<Product> findAll(Specification<Product> specification, Pageable pageable);

    /**
     * The primary image url for each of the given products, in one query.
     *
     * <p>Reading {@code product.getResources()} per row instead would be N+1, and would pull every
     * image of every product to pick one url from each.
     */
    @Query("""
            select resource.product.id as productId, resource.url as url
            from Resource resource
            where resource.product.id in :productIds and resource.isPrimary = true
            """)
    List<ProductThumbnail> findPrimaryThumbnails(@Param("productIds") Collection<UUID> productIds);

    /** Projection for {@link #findPrimaryThumbnails}. */
    interface ProductThumbnail {

        UUID getProductId();

        String getUrl();
    }
}
