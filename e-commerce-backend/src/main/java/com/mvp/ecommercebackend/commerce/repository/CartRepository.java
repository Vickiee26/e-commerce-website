package com.mvp.ecommercebackend.commerce.repository;

import com.mvp.ecommercebackend.commerce.entity.Cart;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CartRepository extends JpaRepository<Cart, UUID> {

    /**
     * The caller's cart with everything a response needs.
     *
     * <p>{@code items} is the only collection in the graph; the rest of the path is to-one hops, so
     * there is no second bag to trip {@code MultipleBagFetchException}.
     */
    @EntityGraph(attributePaths = {"items", "items.variant", "items.variant.product"})
    Optional<Cart> findByUserId(UUID userId);
}
