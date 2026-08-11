package com.mvp.ecommercebackend.auth;

import com.mvp.ecommercebackend.auth.dto.PasswordResetConfirmRequest;
import com.mvp.ecommercebackend.auth.dto.PasswordResetRequest;
import com.mvp.ecommercebackend.auth.entity.AuthEventType;
import com.mvp.ecommercebackend.auth.entity.PasswordResetToken;
import com.mvp.ecommercebackend.auth.entity.User;
import com.mvp.ecommercebackend.auth.repository.PasswordResetTokenRepository;
import com.mvp.ecommercebackend.auth.repository.UserRepository;
import com.mvp.ecommercebackend.common.InvalidTokenException;
import com.mvp.ecommercebackend.common.RequestContext;
import com.mvp.ecommercebackend.common.TokenHasher;
import com.mvp.ecommercebackend.notification.EmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

@Service
public class PasswordResetService {

    private static final Duration TOKEN_TTL = Duration.ofHours(1);
    private static final int TOKEN_BYTES = 32;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** Uniform for unknown, expired, and already-used tokens. */
    private static final String REJECTION_MESSAGE = "Password reset token is not valid";

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final RefreshTokenService refreshTokenService;
    private final AuthEventService authEventService;
    private final PasswordEncoder passwordEncoder;
    private final EmailSender emailSender;
    private final Clock clock;

    public PasswordResetService(UserRepository userRepository,
                                PasswordResetTokenRepository passwordResetTokenRepository,
                                RefreshTokenService refreshTokenService,
                                AuthEventService authEventService,
                                PasswordEncoder passwordEncoder,
                                EmailSender emailSender,
                                Clock clock) {
        this.userRepository = userRepository;
        this.passwordResetTokenRepository = passwordResetTokenRepository;
        this.refreshTokenService = refreshTokenService;
        this.authEventService = authEventService;
        this.passwordEncoder = passwordEncoder;
        this.emailSender = emailSender;
        this.clock = clock;
    }

    /**
     * Never signals whether the email is registered. The caller always answers 202, so an unknown
     * address and a suspended account both simply do nothing here.
     */
    @Transactional
    public void requestReset(PasswordResetRequest request, RequestContext context) {
        Optional<User> found = userRepository.findByEmailIgnoreCase(request.email().trim());
        if (found.isEmpty() || !found.get().isActive()) {
            log.info("Password reset requested for an address with no eligible account");
            return;
        }

        User user = found.get();
        UUID userId = user.getId();
        // Captured before the bulk update below clears the persistence context and detaches user.
        String recipient = user.getEmail();

        Instant now = clock.instant();
        passwordResetTokenRepository.invalidateAllUnusedForUser(user, now);

        String rawToken = generateRawToken();
        PasswordResetToken token = new PasswordResetToken();
        token.setUser(userRepository.getReferenceById(userId));
        token.setTokenHash(TokenHasher.sha256Hex(rawToken));
        token.setExpiresAt(now.plus(TOKEN_TTL));
        passwordResetTokenRepository.saveAndFlush(token);

        authEventService.record(userId, AuthEventType.PASSWORD_RESET_REQUESTED, context);

        // The raw value leaves the application only here, and is never persisted or logged.
        emailSender.sendPasswordReset(recipient, rawToken);
    }

    @Transactional
    public void confirmReset(PasswordResetConfirmRequest request, RequestContext context) {
        PasswordResetToken token = passwordResetTokenRepository
                .findByTokenHash(TokenHasher.sha256Hex(request.token()))
                .orElseThrow(() -> new InvalidTokenException(REJECTION_MESSAGE));

        Instant now = clock.instant();
        if (!token.isUsable(now)) {
            throw new InvalidTokenException(REJECTION_MESSAGE);
        }

        User user = token.getUser();
        UUID userId = user.getId();
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.saveAndFlush(user);

        // Consumes the presented token and any siblings. flushAutomatically has already pushed the
        // new password out; clearAutomatically then detaches user, so nothing below reuses it.
        passwordResetTokenRepository.invalidateAllUnusedForUser(user, now);

        // A reset implies the account may be compromised, so every existing session dies with it.
        refreshTokenService.revokeAllForUser(userRepository.getReferenceById(userId));

        authEventService.record(userId, AuthEventType.PASSWORD_RESET_COMPLETED, context);
    }

    private String generateRawToken() {
        byte[] entropy = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(entropy);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
    }
}
