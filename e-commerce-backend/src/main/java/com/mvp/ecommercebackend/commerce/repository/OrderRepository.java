package com.mvp.ecommercebackend.commerce.repository;

import com.mvp.ecommercebackend.commerce.entity.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrderRepository extends JpaRepository<Order, UUID> {

    /**
     * Scoped by owner in the query, so another user's order is a 404 rather than a 403 — the API
     * never confirms that an order id belongs to someone.
     */
    @EntityGraph(attributePaths = "items")
    Optional<Order> findWithItemsByIdAndUserId(UUID id, UUID userId);

    /**
     * Deliberately no entity graph on the items: combining a collection fetch with a {@code Pageable}
     * makes Hibernate load every matching row and paginate in memory. The summary rows do not need
     * the lines.
     */
    Page<Order> findByUserId(UUID userId, Pageable pageable);

    boolean existsByOrderNumber(String orderNumber);
}
