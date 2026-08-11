package com.mvp.ecommercebackend.auth.repository;

import com.mvp.ecommercebackend.auth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * The lookups are written as explicit {@code lower(...)} queries rather than relying on Spring
 * Data's {@code IgnoreCase} keyword, because that keyword generates {@code upper(email) =
 * upper(?)}, which cannot use the {@code uq_users_email_lower} index. {@code @Query} takes
 * precedence over name derivation, so the method names stay readable.
 */
@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    @Query("select u from User u where lower(u.email) = lower(:email)")
    Optional<User> findByEmailIgnoreCase(@Param("email") String email);

    @Query("select count(u) > 0 from User u where lower(u.email) = lower(:email)")
    boolean existsByEmailIgnoreCase(@Param("email") String email);
}
