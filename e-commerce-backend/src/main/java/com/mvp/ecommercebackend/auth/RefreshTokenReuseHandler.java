package com.mvp.ecommercebackend.auth;

import com.mvp.ecommercebackend.auth.entity.AuthEventType;
import com.mvp.ecommercebackend.auth.entity.User;
import com.mvp.ecommercebackend.auth.repository.RefreshTokenRepository;
import com.mvp.ecommercebackend.auth.repository.UserRepository;
import com.mvp.ecommercebackend.common.RequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * Handles a replayed refresh token by revoking the user's entire active token family.
 *
 * <p>This is a separate bean purely so the work runs in its own transaction. The request that
 * triggered it ends in a 401, and a 401 is produced by throwing — which would roll back the
 * revocations and the audit row if they shared that transaction. Spring's {@code @Transactional}
 * only applies through the proxy, so this cannot be a private method on
 * {@link RefreshTokenService}.
 */
@Service
public class RefreshTokenReuseHandler {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenReuseHandler.class);

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final AuthEventService authEventService;
    private final Clock clock;

    public RefreshTokenReuseHandler(RefreshTokenRepository refreshTokenRepository,
                                    UserRepository userRepository,
                                    AuthEventService authEventService,
                                    Clock clock) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.userRepository = userRepository;
        this.authEventService = authEventService;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleReuse(UUID userId, RequestContext context) {
        User user = userRepository.getReferenceById(userId);
        int revoked = refreshTokenRepository.revokeAllActiveForUser(user, clock.instant());

        log.warn("Refresh token reuse detected for user {}; revoked {} active token(s) from ip {}",
                userId, revoked, context == null ? "unknown" : context.ipAddress());

        authEventService.record(userId, AuthEventType.TOKEN_REUSE_DETECTED, context);
    }
}
