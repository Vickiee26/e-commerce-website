package com.mvp.ecommercebackend.user.repository;

import com.mvp.ecommercebackend.user.entity.Address;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AddressRepository extends JpaRepository<Address, UUID> {

    List<Address> findByUserIdOrderByCreatedAtAsc(UUID userId);

    /**
     * The single lookup every endpoint uses. Scoping by owner in the query rather than checking
     * afterwards means a caller cannot reach another user's row at all, so the "not yours" and
     * "does not exist" cases are the same empty Optional — and therefore the same 404.
     */
    Optional<Address> findByIdAndUserId(UUID id, UUID userId);
}
