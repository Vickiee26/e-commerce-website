package com.mvp.ecommercebackend.auth;

import com.mvp.ecommercebackend.auth.dto.LoginRequest;
import com.mvp.ecommercebackend.auth.dto.RegisterRequest;
import com.mvp.ecommercebackend.auth.dto.TokenPairResponse;
import com.mvp.ecommercebackend.auth.entity.AuthEventType;
import com.mvp.ecommercebackend.auth.entity.Role;
import com.mvp.ecommercebackend.auth.entity.RoleCode;
import com.mvp.ecommercebackend.auth.entity.User;
import com.mvp.ecommercebackend.auth.entity.UserStatus;
import com.mvp.ecommercebackend.auth.repository.RoleRepository;
import com.mvp.ecommercebackend.auth.repository.UserRepository;
import com.mvp.ecommercebackend.common.DuplicateResourceException;
import com.mvp.ecommercebackend.common.InvalidCredentialsException;
import com.mvp.ecommercebackend.common.RequestContext;
import com.mvp.ecommercebackend.config.JwtProperties;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class AuthService {

    /** One message for every credential failure, so the endpoint reveals nothing. */
    private static final String CREDENTIAL_FAILURE = "Email or password is incorrect";

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final RefreshTokenService refreshTokenService;
    private final AuthEventService authEventService;
    private final JwtProperties jwtProperties;

    /**
     * A hash of a value nobody knows, verified against when the email is unknown so that path costs
     * the same BCrypt work as a wrong password. Without it, the difference between ~100 ms and ~0 ms
     * is a reliable account-existence oracle. Generated at startup rather than committed, so no hash
     * of a real credential lands in the repository.
     */
    private final String timingEqualiserHash;

    public AuthService(UserRepository userRepository,
                       RoleRepository roleRepository,
                       PasswordEncoder passwordEncoder,
                       TokenService tokenService,
                       RefreshTokenService refreshTokenService,
                       AuthEventService authEventService,
                       JwtProperties jwtProperties) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.refreshTokenService = refreshTokenService;
        this.authEventService = authEventService;
        this.jwtProperties = jwtProperties;
        this.timingEqualiserHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public TokenPairResponse register(RegisterRequest request, RequestContext context) {
        String email = request.email().trim();
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new DuplicateResourceException("Email is already registered");
        }

        Role customer = roleRepository.findByCode(RoleCode.CUSTOMER).orElseThrow(
                () -> new IllegalStateException("CUSTOMER role is missing; V2 seed did not run"));

        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setFullName(request.fullName().trim());
        user.setPhone(request.phone() == null || request.phone().isBlank()
                ? null : request.phone().trim());
        // Never taken from the request: the client cannot make itself an active, verified admin.
        user.setStatus(UserStatus.ACTIVE);
        user.setEmailVerified(false);
        user.addRole(customer);

        User saved;
        try {
            // Flush inside the try: two concurrent registrations both pass the existence check
            // above, and only the unique index on lower(email) settles it.
            saved = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            throw new DuplicateResourceException("Email is already registered");
        }

        // No REGISTER event type exists in the design; a session was created, so it is a login.
        authEventService.record(saved.getId(), AuthEventType.LOGIN_SUCCESS, context);
        return issueTokenPair(saved, context);
    }

    @Transactional
    public TokenPairResponse login(LoginRequest request, RequestContext context) {
        Optional<User> found = userRepository.findByEmailIgnoreCase(request.email().trim());

        if (found.isEmpty()) {
            // Spend the same time as a real verification before failing.
            passwordEncoder.matches(request.password(), timingEqualiserHash);
            authEventService.recordAndCommit(null, AuthEventType.LOGIN_FAILURE, context);
            throw new InvalidCredentialsException(CREDENTIAL_FAILURE);
        }

        User user = found.get();
        boolean passwordMatches = passwordEncoder.matches(request.password(), user.getPasswordHash());
        if (!passwordMatches || !user.isActive()) {
            // A suspended account is reported exactly like a bad password.
            authEventService.recordAndCommit(user.getId(), AuthEventType.LOGIN_FAILURE, context);
            throw new InvalidCredentialsException(CREDENTIAL_FAILURE);
        }

        authEventService.record(user.getId(), AuthEventType.LOGIN_SUCCESS, context);
        return issueTokenPair(user, context);
    }

    @Transactional
    public void logout(UUID userId, String rawRefreshToken, RequestContext context) {
        refreshTokenService.revoke(rawRefreshToken, userId);
        authEventService.record(userId, AuthEventType.LOGOUT, context);
    }

    TokenPairResponse issueTokenPair(User user, RequestContext context) {
        RefreshTokenService.IssuedRefreshToken refreshToken =
                refreshTokenService.issue(user, context);
        return new TokenPairResponse(
                tokenService.generateAccessToken(user),
                refreshToken.rawValue(),
                "Bearer",
                jwtProperties.accessTokenTtl().toSeconds());
    }
}
