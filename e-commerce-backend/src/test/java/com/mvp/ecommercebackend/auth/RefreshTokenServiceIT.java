package com.mvp.ecommercebackend.auth;

import com.mvp.ecommercebackend.auth.entity.AuthEventType;
import com.mvp.ecommercebackend.auth.entity.RefreshToken;
import com.mvp.ecommercebackend.auth.entity.Role;
import com.mvp.ecommercebackend.auth.entity.RoleCode;
import com.mvp.ecommercebackend.auth.entity.User;
import com.mvp.ecommercebackend.auth.entity.UserStatus;
import com.mvp.ecommercebackend.auth.repository.AuthEventRepository;
import com.mvp.ecommercebackend.auth.repository.RefreshTokenRepository;
import com.mvp.ecommercebackend.auth.repository.RoleRepository;
import com.mvp.ecommercebackend.auth.repository.UserRepository;
import com.mvp.ecommercebackend.common.InvalidTokenException;
import com.mvp.ecommercebackend.common.RequestContext;
import com.mvp.ecommercebackend.common.TokenHasher;
import com.mvp.ecommercebackend.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefreshTokenServiceIT extends AbstractIntegrationTest {

    private static final RequestContext CONTEXT = new RequestContext("203.0.113.7", "JUnit/1.0");

    @Autowired
    private RefreshTokenService refreshTokenService;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private AuthEventRepository authEventRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    private User persistUser(String email, UserStatus status) {
        Role customer = roleRepository.findByCode(RoleCode.CUSTOMER).orElseThrow();
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash("not-a-real-hash");
        user.setFullName("Test Person");
        user.setStatus(status);
        user.addRole(customer);
        return userRepository.saveAndFlush(user);
    }

    private User persistUser(String email) {
        return persistUser(email, UserStatus.ACTIVE);
    }

    @Test
    void storesOnlyTheHashOfTheIssuedToken() {
        User user = persistUser("person@example.com");

        RefreshTokenService.IssuedRefreshToken issued = refreshTokenService.issue(user, CONTEXT);

        List<String> storedHashes = jdbcTemplate.queryForList(
                "SELECT token_hash FROM refresh_tokens", String.class);
        assertThat(storedHashes).containsExactly(TokenHasher.sha256Hex(issued.rawValue()));
        // get(0), not getFirst(): SequencedCollection is Java 21 and this project targets 17.
        assertThat(storedHashes.get(0)).isNotEqualTo(issued.rawValue());
        assertThat(issued.rawValue()).hasSizeGreaterThanOrEqualTo(40);
        assertThat(issued.expiresAt()).isAfter(Instant.now().plus(29, ChronoUnit.DAYS));
    }

    @Test
    void issuesDistinctRawValues() {
        User user = persistUser("person@example.com");

        String first = refreshTokenService.issue(user, CONTEXT).rawValue();
        String second = refreshTokenService.issue(user, CONTEXT).rawValue();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void recordsTheUserAgentAlongsideTheToken() {
        User user = persistUser("person@example.com");

        refreshTokenService.issue(user, CONTEXT);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT user_agent FROM refresh_tokens", String.class)).isEqualTo("JUnit/1.0");
    }

    @Test
    void rotationRevokesThePresentedTokenAndChainsToTheReplacement() {
        User user = persistUser("person@example.com");
        String original = refreshTokenService.issue(user, CONTEXT).rawValue();

        RefreshTokenService.RotationResult result = refreshTokenService.rotate(original, CONTEXT);

        assertThat(result.user().getId()).isEqualTo(user.getId());
        assertThat(result.refreshToken().rawValue()).isNotEqualTo(original);

        RefreshToken old = refreshTokenRepository
                .findByTokenHash(TokenHasher.sha256Hex(original)).orElseThrow();
        RefreshToken replacement = refreshTokenRepository
                .findByTokenHash(TokenHasher.sha256Hex(result.refreshToken().rawValue()))
                .orElseThrow();

        assertThat(old.getRevokedAt()).isNotNull();
        assertThat(old.getReplacedBy().getId()).isEqualTo(replacement.getId());
        assertThat(replacement.getRevokedAt()).isNull();
    }

    @Test
    void rotationLogsATokenRefreshEvent() {
        User user = persistUser("person@example.com");
        String original = refreshTokenService.issue(user, CONTEXT).rawValue();

        refreshTokenService.rotate(original, CONTEXT);

        assertThat(authEventRepository.findAllByEventType(AuthEventType.TOKEN_REFRESH))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getUser().getId()).isEqualTo(user.getId());
                    assertThat(event.getIpAddress()).isEqualTo("203.0.113.7");
                });
    }

    @Test
    void replayingARotatedTokenRevokesTheWholeFamilyAndIsAudited() {
        User user = persistUser("person@example.com");
        User bystander = persistUser("bystander@example.com");
        String stolen = refreshTokenService.issue(user, CONTEXT).rawValue();
        String otherSession = refreshTokenService.issue(user, CONTEXT).rawValue();
        String untouched = refreshTokenService.issue(bystander, CONTEXT).rawValue();

        // The legitimate client rotates once; the attacker then replays the old value.
        String rotated = refreshTokenService.rotate(stolen, CONTEXT).refreshToken().rawValue();

        assertThatThrownBy(() -> refreshTokenService.rotate(stolen, CONTEXT))
                .isInstanceOf(InvalidTokenException.class);

        // The revocations and the audit row must have survived the 401.
        assertThat(activeTokenCountFor(user.getId())).isZero();
        assertThat(activeTokenCountFor(bystander.getId())).isEqualTo(1);
        assertThat(authEventRepository.findAllByEventType(AuthEventType.TOKEN_REUSE_DETECTED))
                .singleElement()
                .satisfies(event ->
                        assertThat(event.getUser().getId()).isEqualTo(user.getId()));

        // Every token in the revoked family is now dead, including the newest one.
        assertThatThrownBy(() -> refreshTokenService.rotate(rotated, CONTEXT))
                .isInstanceOf(InvalidTokenException.class);
        assertThatThrownBy(() -> refreshTokenService.rotate(otherSession, CONTEXT))
                .isInstanceOf(InvalidTokenException.class);
        assertThat(refreshTokenService.rotate(untouched, CONTEXT)).isNotNull();
    }

    @Test
    void rejectsAnExpiredTokenWithoutRevokingTheFamily() {
        User user = persistUser("person@example.com");
        String live = refreshTokenService.issue(user, CONTEXT).rawValue();
        String expiredRaw = "expired-token-value";
        RefreshToken expired = new RefreshToken();
        expired.setUser(user);
        expired.setTokenHash(TokenHasher.sha256Hex(expiredRaw));
        expired.setExpiresAt(Instant.now().minus(1, ChronoUnit.DAYS));
        refreshTokenRepository.saveAndFlush(expired);

        assertThatThrownBy(() -> refreshTokenService.rotate(expiredRaw, CONTEXT))
                .isInstanceOf(InvalidTokenException.class);

        // Expiry is ordinary, not evidence of theft, so the live session keeps working.
        assertThat(authEventRepository.findAllByEventType(AuthEventType.TOKEN_REUSE_DETECTED))
                .isEmpty();
        assertThat(refreshTokenService.rotate(live, CONTEXT)).isNotNull();
    }

    @Test
    void rejectsUnknownBlankAndNullTokens() {
        assertThatThrownBy(() -> refreshTokenService.rotate("never-issued", CONTEXT))
                .isInstanceOf(InvalidTokenException.class);
        assertThatThrownBy(() -> refreshTokenService.rotate("", CONTEXT))
                .isInstanceOf(InvalidTokenException.class);
        assertThatThrownBy(() -> refreshTokenService.rotate(null, CONTEXT))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void rejectsATokenBelongingToASuspendedUser() {
        User user = persistUser("suspended@example.com", UserStatus.SUSPENDED);
        String token = refreshTokenService.issue(user, CONTEXT).rawValue();

        assertThatThrownBy(() -> refreshTokenService.rotate(token, CONTEXT))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void revokeMakesTheTokenUnusableAndIsIdempotent() {
        User user = persistUser("person@example.com");
        String token = refreshTokenService.issue(user, CONTEXT).rawValue();

        refreshTokenService.revoke(token, user.getId());
        refreshTokenService.revoke(token, user.getId());
        refreshTokenService.revoke("never-issued", user.getId());

        assertThat(activeTokenCountFor(user.getId())).isZero();
        assertThatThrownBy(() -> refreshTokenService.rotate(token, CONTEXT))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void revokeIgnoresATokenOwnedBySomeoneElse() {
        User owner = persistUser("owner@example.com");
        User attacker = persistUser("attacker@example.com");
        String ownersToken = refreshTokenService.issue(owner, CONTEXT).rawValue();

        refreshTokenService.revoke(ownersToken, attacker.getId());

        assertThat(activeTokenCountFor(owner.getId())).isEqualTo(1);
    }

    @Test
    void revokeAllForUserKillsEverySessionForThatUserOnly() {
        User user = persistUser("person@example.com");
        User bystander = persistUser("bystander@example.com");
        refreshTokenService.issue(user, CONTEXT);
        refreshTokenService.issue(user, CONTEXT);
        refreshTokenService.issue(bystander, CONTEXT);

        int revoked = refreshTokenService.revokeAllForUser(user);

        assertThat(revoked).isEqualTo(2);
        assertThat(activeTokenCountFor(user.getId())).isZero();
        assertThat(activeTokenCountFor(bystander.getId())).isEqualTo(1);
    }

    private int activeTokenCountFor(UUID userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL",
                Integer.class, userId);
        return count == null ? 0 : count;
    }
}
