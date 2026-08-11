package com.mvp.ecommercebackend.catalog.repository;

import com.mvp.ecommercebackend.catalog.entity.Category;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CategoryRepository extends JpaRepository<Category, UUID> {

    /**
     * The navigation query: every category with its types in one round trip.
     *
     * <p>{@code @EntityGraph} rather than a lazy walk because the caller always renders the types,
     * and rather than {@code JOIN FETCH} in HQL because there is no pagination here to conflict
     * with — the category list is small and returned whole.
     */
    @EntityGraph(attributePaths = "categoryTypes")
    List<Category> findAllByOrderByNameAsc();
}
