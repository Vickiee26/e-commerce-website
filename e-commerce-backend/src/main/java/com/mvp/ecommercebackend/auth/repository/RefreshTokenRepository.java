package com.mvp.ecommercebackend.auth.repository;

import com.mvp.ecommercebackend.auth.entity.RefreshToken;
import com.mvp.ecommercebackend.auth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    List<RefreshToken> findAllByUser(User user);

    /**
     * Revokes the whole active token family for one user. Used both on password reset and when
     * replay of a rotated token is detected.
     *
     * <p>{@code @Transactional} is required: Spring Data does not open a writable transaction for
     * interface-declared query methods, so {@code flushAutomatically} would fail with "No
     * EntityManager with actual transaction available". Propagation is REQUIRED, so a calling
     * service's transaction is joined rather than replaced.
     *
     * @return how many rows were revoked
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RefreshToken t set t.revokedAt = :now, t.updatedAt = :now "
            + "where t.user = :user and t.revokedAt is null")
    int revokeAllActiveForUser(@Param("user") User user, @Param("now") Instant now);
}
