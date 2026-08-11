package com.mvp.ecommercebackend.commerce.repository;

import com.mvp.ecommercebackend.commerce.entity.CartItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CartItemRepository extends JpaRepository<CartItem, UUID> {

    /**
     * Scoped by owner in the query itself, so a line belonging to someone else is indistinguishable
     * from one that does not exist. That is deliberate: the caller gets a 404, never a 403, so the
     * API never confirms that another user's cart line exists.
     */
    Optional<CartItem> findByIdAndCartUserId(UUID id, UUID userId);
}
