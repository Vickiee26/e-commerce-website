package com.mvp.ecommercebackend.auth;

import com.mvp.ecommercebackend.auth.entity.RefreshToken;
import com.mvp.ecommercebackend.auth.entity.Role;
import com.mvp.ecommercebackend.auth.entity.RoleCode;
import com.mvp.ecommercebackend.auth.entity.User;
import com.mvp.ecommercebackend.auth.entity.UserStatus;
import com.mvp.ecommercebackend.auth.repository.RefreshTokenRepository;
import com.mvp.ecommercebackend.auth.repository.RoleRepository;
import com.mvp.ecommercebackend.auth.repository.UserRepository;
import com.mvp.ecommercebackend.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserRepositoryIT extends AbstractIntegrationTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    private User persistUser(String email) {
        Role customer = roleRepository.findByCode(RoleCode.CUSTOMER).orElseThrow();
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash("not-a-real-hash");
        user.setFullName("Test Person");
        user.setStatus(UserStatus.ACTIVE);
        user.addRole(customer);
        return userRepository.saveAndFlush(user);
    }

    @Test
    void assignsGeneratedUuidAndTimestampsOnSave() {
        User saved = persistUser("person@example.com");

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    void findsSeededRolesByCode() {
        assertThat(roleRepository.findByCode(RoleCode.CUSTOMER)).isPresent();
        assertThat(roleRepository.findByCode(RoleCode.ADMIN)).isPresent();
    }

    @Test
    void associatesRolesThroughTheJoinTable() {
        User saved = persistUser("person@example.com");

        User reloaded = userRepository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getRoles())
                .extracting(Role::getCode)
                .containsExactly(RoleCode.CUSTOMER);
    }

    @Test
    void looksUpUsersIgnoringEmailCase() {
        persistUser("Person@Example.com");

        assertThat(userRepository.findByEmailIgnoreCase("person@example.com")).isPresent();
        assertThat(userRepository.existsByEmailIgnoreCase("PERSON@EXAMPLE.COM")).isTrue();
        assertThat(userRepository.findByEmailIgnoreCase("someone.else@example.com")).isEmpty();
    }

    @Test
    void rejectsTwoAccountsWhoseEmailsDifferOnlyByCase() {
        persistUser("person@example.com");

        assertThatThrownBy(() -> persistUser("PERSON@example.com"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsDuplicateRefreshTokenHashes() {
        User user = persistUser("person@example.com");
        Instant expiry = Instant.now().plus(30, ChronoUnit.DAYS);

        refreshTokenRepository.saveAndFlush(refreshToken(user, "a".repeat(64), expiry));

        assertThatThrownBy(() ->
                refreshTokenRepository.saveAndFlush(refreshToken(user, "a".repeat(64), expiry)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void revokesEveryActiveRefreshTokenForOneUser() {
        User user = persistUser("person@example.com");
        User other = persistUser("other@example.com");
        Instant expiry = Instant.now().plus(30, ChronoUnit.DAYS);
        refreshTokenRepository.save(refreshToken(user, "b".repeat(64), expiry));
        refreshTokenRepository.save(refreshToken(user, "c".repeat(64), expiry));
        refreshTokenRepository.save(refreshToken(other, "d".repeat(64), expiry));
        refreshTokenRepository.flush();

        int revoked = refreshTokenRepository.revokeAllActiveForUser(user, Instant.now());

        assertThat(revoked).isEqualTo(2);
        assertThat(refreshTokenRepository.findByTokenHash("d".repeat(64)).orElseThrow().getRevokedAt())
                .isNull();
    }

    @Test
    void treatsExpiredAndRevokedTokensAsInactive() {
        User user = persistUser("person@example.com");
        Instant now = Instant.now();

        RefreshToken live = refreshToken(user, "e".repeat(64), now.plus(1, ChronoUnit.DAYS));
        RefreshToken expired = refreshToken(user, "f".repeat(64), now.minus(1, ChronoUnit.DAYS));
        RefreshToken revoked = refreshToken(user, "0".repeat(64), now.plus(1, ChronoUnit.DAYS));
        revoked.setRevokedAt(now.minusSeconds(1));

        assertThat(live.isActive(now)).isTrue();
        assertThat(expired.isActive(now)).isFalse();
        assertThat(revoked.isActive(now)).isFalse();
    }

    private RefreshToken refreshToken(User user, String hash, Instant expiresAt) {
        RefreshToken token = new RefreshToken();
        token.setUser(user);
        token.setTokenHash(hash);
        token.setExpiresAt(expiresAt);
        token.setUserAgent("JUnit");
        return token;
    }
}
