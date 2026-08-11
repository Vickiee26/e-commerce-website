package com.mvp.ecommercebackend.catalog.repository;

import com.mvp.ecommercebackend.catalog.entity.ProductVariant;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProductVariantRepository extends JpaRepository<ProductVariant, UUID> {

    /** Fetches the parent product too, since a cart line needs its name. */
    @Query("""
            select variant from ProductVariant variant
            join fetch variant.product
            where variant.id = :id
            """)
    Optional<ProductVariant> findWithProductById(@Param("id") UUID id);

    /**
     * Locks the given variants for update, so a stock check and the decrement that follows cannot
     * be interleaved by a second order. Without the lock two concurrent checkouts both read the
     * same stock, both pass, and the shop oversells.
     *
     * <p>{@code order by variant.id} is not cosmetic. Postgres takes the row locks in the order the
     * rows come back, so two orders sharing two variants would deadlock if each locked them in a
     * different order. A total order on the id makes that impossible.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select variant from ProductVariant variant
            where variant.id in :ids
            order by variant.id
            """)
    List<ProductVariant> lockAllByIdIn(@Param("ids") Collection<UUID> ids);
}
