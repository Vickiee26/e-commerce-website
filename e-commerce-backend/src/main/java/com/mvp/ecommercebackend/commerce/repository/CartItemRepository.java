package com.mvp.ecommercebackend.commerce.repository;

import com.mvp.ecommercebackend.commerce.entity.CartItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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

    /**
     * The cart as scalars, for checkout.
     *
     * <p>A projection rather than the entities on purpose. Checkout must read stock through a
     * {@code SELECT ... FOR UPDATE}, and Hibernate keeps the copy of an entity it already has in the
     * persistence context: if loading the cart had pulled in {@code ProductVariant}, the locking
     * query would take the row lock but hand back the stale stock figure it read before waiting,
     * and two concurrent orders could both pass a check on the last unit. Selecting scalars means
     * nothing is cached to go stale.
     *
     * <p>Ordered so the lines of an order match the order they were added to the cart.
     */
    @Query("""
            select variant.id as variantId,
                   product.id as productId,
                   product.name as productName,
                   product.price as unitPrice,
                   variant.color as color,
                   variant.size as size,
                   item.quantity as quantity,
                   product.archivedAt as productArchivedAt,
                   variant.archivedAt as variantArchivedAt
            from CartItem item
            join item.variant variant
            join variant.product product
            where item.cart.user.id = :userId
            order by item.createdAt, item.id
            """)
    List<CheckoutLine> findCheckoutLines(@Param("userId") UUID userId);

    /**
     * Empties the cart in one statement, without loading the lines.
     *
     * <p>{@code flushAutomatically} matters: the stock decrements and the new order are pending in
     * the persistence context when this runs, and a bulk statement bypasses it. Without the flush
     * the delete could reach the database before the inserts.
     *
     * <p>Not {@code clearAutomatically}: clearing would detach the locked, dirty
     * {@code ProductVariant} instances and throw the stock decrements away.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            delete from CartItem item
            where item.cart.id in (select cart.id from Cart cart where cart.user.id = :userId)
            """)
    void deleteByCartUserId(@Param("userId") UUID userId);

    /** One cart line, flattened. */
    interface CheckoutLine {
        UUID getVariantId();

        UUID getProductId();

        String getProductName();

        BigDecimal getUnitPrice();

        String getColor();

        String getSize();

        int getQuantity();

        /**
         * Archive flags, so checkout can refuse a variant retired after it was added to the cart.
         * Scalars for the same reason as everything else here: nothing may be cached to go stale.
         */
        Instant getProductArchivedAt();

        Instant getVariantArchivedAt();
    }
}
