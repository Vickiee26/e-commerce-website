package com.mvp.ecommercebackend.catalog.repository;

import com.mvp.ecommercebackend.catalog.entity.Resource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Product images and other attachments.
 *
 * <p>New in the admin slice: the catalogue only ever reached resources through
 * {@code Product.getResources()} or {@code ProductRepository.findPrimaryThumbnails}, and neither can
 * address one by its own id.
 */
@Repository
public interface ResourceRepository extends JpaRepository<Resource, UUID> {

    /**
     * Fetches the owning product too. Every write here needs it — to demote the product's other
     * primaries, and to keep the in-memory collection consistent with the row being deleted.
     */
    @Query("""
            select resource from Resource resource
            join fetch resource.product
            where resource.id = :id
            """)
    Optional<Resource> findWithProductById(@Param("id") UUID id);
}
