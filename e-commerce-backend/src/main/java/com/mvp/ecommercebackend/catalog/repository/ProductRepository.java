package com.mvp.ecommercebackend.catalog.repository;

import com.mvp.ecommercebackend.catalog.entity.Product;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProductRepository extends JpaRepository<Product, UUID> {

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
}
