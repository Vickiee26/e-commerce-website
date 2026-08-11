package com.mvp.ecommercebackend.auth;

import com.mvp.ecommercebackend.auth.entity.Role;
import com.mvp.ecommercebackend.auth.entity.User;
import com.mvp.ecommercebackend.common.InvalidTokenException;
import com.mvp.ecommercebackend.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Issues and verifies stateless HS256 access tokens.
 *
 * <p>Verification is signature-only, so no database read happens per request. Every failure mode —
 * expired, tampered, unsigned, foreign issuer, garbage — collapses to
 * {@link InvalidTokenException}, because telling a caller <em>why</em> a token was rejected only
 * helps an attacker.
 */
@Service
public class TokenService {

    private final JwtProperties properties;
    private final Clock clock;
    private final SecretKey signingKey;

    public TokenService(JwtProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.signingKey = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
    }

    public String generateAccessToken(User user) {
        Instant now = clock.instant();
        return Jwts.builder()
                .issuer(properties.issuer())
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .claim("roles", user.getRoles().stream()
                        .map(Role::getCode)
                        .map(Enum::name)
                        .toList())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(properties.accessTokenTtl())))
                .signWith(signingKey)
                .compact();
    }

    public AuthenticatedUser parseAccessToken(String token) {
        if (token == null || token.isBlank()) {
            throw new InvalidTokenException("Access token is not valid");
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(properties.issuer())
                    // Bridge java.time.Clock onto JJWT's own Clock so expiry honours the injected one.
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            return new AuthenticatedUser(
                    UUID.fromString(claims.getSubject()),
                    claims.get("email", String.class),
                    readRoles(claims));
        } catch (JwtException | IllegalArgumentException exception) {
            throw new InvalidTokenException("Access token is not valid");
        }
    }

    private List<String> readRoles(Claims claims) {
        Object raw = claims.get("roles");
        if (!(raw instanceof List<?> values)) {
            return List.of();
        }
        return values.stream().map(String::valueOf).toList();
    }
}
