package com.mvp.ecommercebackend.auth.repository;

import com.mvp.ecommercebackend.auth.entity.PasswordResetToken;
import com.mvp.ecommercebackend.auth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {

    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    /**
     * Marks every outstanding reset token for a user as used, so only the newest one works.
     *
     * <p>{@code @Transactional} is required for the same reason as
     * {@link RefreshTokenRepository#revokeAllActiveForUser}: interface-declared query methods get
     * no writable transaction of their own.
     */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update PasswordResetToken t set t.usedAt = :now, t.updatedAt = :now "
            + "where t.user = :user and t.usedAt is null")
    int invalidateAllUnusedForUser(@Param("user") User user, @Param("now") Instant now);
}
