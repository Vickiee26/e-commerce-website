package com.mvp.ecommercebackend.auth;

import com.mvp.ecommercebackend.auth.entity.AuthEventType;
import com.mvp.ecommercebackend.auth.entity.RefreshToken;
import com.mvp.ecommercebackend.auth.entity.User;
import com.mvp.ecommercebackend.auth.repository.RefreshTokenRepository;
import com.mvp.ecommercebackend.common.InvalidTokenException;
import com.mvp.ecommercebackend.common.RequestContext;
import com.mvp.ecommercebackend.common.TokenHasher;
import com.mvp.ecommercebackend.config.JwtProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues, rotates, and revokes refresh tokens.
 *
 * <p>Every rejection raises the same {@link InvalidTokenException} message. A caller must not be
 * able to tell "this token expired" from "this token was revoked because your session was stolen".
 */
@Service
public class RefreshTokenService {

    /** Deliberately uniform: the reason for rejection is not the client's business. */
    private static final String REJECTION_MESSAGE = "Refresh token is not valid";

    private static final int TOKEN_BYTES = 32;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final RefreshTokenRepository refreshTokenRepository;
    private final RefreshTokenReuseHandler reuseHandler;
    private final AuthEventService authEventService;
    private final JwtProperties jwtProperties;
    private final Clock clock;

    public RefreshTokenService(RefreshTokenRepository refreshTokenRepository,
                               RefreshTokenReuseHandler reuseHandler,
                               AuthEventService authEventService,
                               JwtProperties jwtProperties,
                               Clock clock) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.reuseHandler = reuseHandler;
        this.authEventService = authEventService;
        this.jwtProperties = jwtProperties;
        this.clock = clock;
    }

    /** The raw value is returned here and never again — only its hash is stored. */
    public record IssuedRefreshToken(String rawValue, Instant expiresAt) {
    }

    public record RotationResult(User user, IssuedRefreshToken refreshToken) {
    }

    private record NewToken(String rawValue, RefreshToken entity) {
    }

    @Transactional
    public IssuedRefreshToken issue(User user, RequestContext context) {
        NewToken created = createToken(user, context);
        return new IssuedRefreshToken(created.rawValue(), created.entity().getExpiresAt());
    }

    @Transactional
    public RotationResult rotate(String rawToken, RequestContext context) {
        RefreshToken presented = lookup(rawToken)
                .orElseThrow(() -> new InvalidTokenException(REJECTION_MESSAGE));
        Instant now = clock.instant();

        if (presented.getRevokedAt() != null) {
            // The value was already rotated away, so this presentation is a replay of a leaked
            // token. Kill every session for the user; getId() on the lazy proxy reads no rows.
            reuseHandler.handleReuse(presented.getUser().getId(), context);
            throw new InvalidTokenException(REJECTION_MESSAGE);
        }
        if (!presented.isActive(now)) {
            throw new InvalidTokenException(REJECTION_MESSAGE);
        }

        User user = presented.getUser();
        if (!user.isActive()) {
            throw new InvalidTokenException(REJECTION_MESSAGE);
        }

        NewToken replacement = createToken(user, context);
        presented.setRevokedAt(now);
        presented.setReplacedBy(replacement.entity());
        refreshTokenRepository.save(presented);

        authEventService.record(user.getId(), AuthEventType.TOKEN_REFRESH, context);

        return new RotationResult(user, new IssuedRefreshToken(
                replacement.rawValue(), replacement.entity().getExpiresAt()));
    }

    /**
     * Logout. Silently does nothing for an unknown, already-revoked, or foreign token: a caller
     * must not be able to probe token validity or revoke someone else's session.
     */
    @Transactional
    public void revoke(String rawToken, UUID ownerId) {
        lookup(rawToken)
                .filter(token -> token.getRevokedAt() == null)
                .filter(token -> token.getUser().getId().equals(ownerId))
                .ifPresent(token -> {
                    token.setRevokedAt(clock.instant());
                    refreshTokenRepository.save(token);
                });
    }

    /** Used on password reset, where every existing session must be assumed compromised. */
    @Transactional
    public int revokeAllForUser(User user) {
        return refreshTokenRepository.revokeAllActiveForUser(user, clock.instant());
    }

    private Optional<RefreshToken> lookup(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        return refreshTokenRepository.findByTokenHash(TokenHasher.sha256Hex(rawToken));
    }

    private NewToken createToken(User user, RequestContext context) {
        byte[] entropy = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(entropy);
        String rawValue = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);

        RefreshToken token = new RefreshToken();
        token.setUser(user);
        token.setTokenHash(TokenHasher.sha256Hex(rawValue));
        token.setExpiresAt(clock.instant().plus(jwtProperties.refreshTokenTtl()));
        token.setUserAgent(context == null ? null : context.userAgent());

        return new NewToken(rawValue, refreshTokenRepository.saveAndFlush(token));
    }
}
