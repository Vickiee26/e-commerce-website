package com.mvp.ecommercebackend.auth;

import com.mvp.ecommercebackend.auth.entity.Role;
import com.mvp.ecommercebackend.auth.entity.RoleCode;
import com.mvp.ecommercebackend.auth.entity.User;
import com.mvp.ecommercebackend.auth.entity.UserStatus;
import com.mvp.ecommercebackend.common.InvalidTokenException;
import com.mvp.ecommercebackend.config.JwtProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenServiceTest {

    private static final String SECRET = "unit-test-signing-key-at-least-32-chars";
    private static final Instant NOW = Instant.parse("2026-08-11T10:00:00Z");

    private final JwtProperties properties = new JwtProperties(
            SECRET, Duration.ofMinutes(15), Duration.ofDays(30), "shopflow");

    private TokenService serviceAt(Instant instant) {
        return new TokenService(properties, Clock.fixed(instant, ZoneOffset.UTC));
    }

    private User customer() {
        Role role = new Role();
        role.setId(UUID.randomUUID());
        role.setCode(RoleCode.CUSTOMER);
        role.setName("Customer");

        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("person@example.com");
        user.setPasswordHash("irrelevant");
        user.setFullName("Test Person");
        user.setStatus(UserStatus.ACTIVE);
        user.addRole(role);
        return user;
    }

    @Test
    void roundTripsIdentityEmailAndRoles() {
        TokenService service = serviceAt(NOW);
        User user = customer();

        AuthenticatedUser parsed = service.parseAccessToken(service.generateAccessToken(user));

        assertThat(parsed.id()).isEqualTo(user.getId());
        assertThat(parsed.email()).isEqualTo("person@example.com");
        assertThat(parsed.roles()).containsExactly("CUSTOMER");
    }

    @Test
    void acceptsATokenStillInsideItsFifteenMinuteWindow() {
        String token = serviceAt(NOW).generateAccessToken(customer());

        AuthenticatedUser parsed = serviceAt(NOW.plus(Duration.ofMinutes(14))).parseAccessToken(token);

        assertThat(parsed.email()).isEqualTo("person@example.com");
    }

    @Test
    void rejectsAnExpiredToken() {
        String token = serviceAt(NOW).generateAccessToken(customer());
        TokenService later = serviceAt(NOW.plus(Duration.ofMinutes(16)));

        assertThatThrownBy(() -> later.parseAccessToken(token))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void rejectsATokenSignedWithAnotherKey() {
        SecretKey attackerKey = Keys.hmacShaKeyFor(
                "a-completely-different-key-of-32-plus".getBytes(StandardCharsets.UTF_8));
        String forged = Jwts.builder()
                .issuer("shopflow")
                .subject(UUID.randomUUID().toString())
                .claim("email", "attacker@example.com")
                .claim("roles", java.util.List.of("ADMIN"))
                .issuedAt(Date.from(NOW))
                .expiration(Date.from(NOW.plus(Duration.ofMinutes(15))))
                .signWith(attackerKey)
                .compact();

        assertThatThrownBy(() -> serviceAt(NOW).parseAccessToken(forged))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void rejectsATokenWhoseSignatureHasBeenTamperedWith() {
        TokenService service = serviceAt(NOW);
        String token = service.generateAccessToken(customer());
        String tampered = token.substring(0, token.length() - 2)
                + (token.endsWith("A") ? "B" : "A");

        assertThatThrownBy(() -> service.parseAccessToken(tampered))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void rejectsATokenWhosePayloadHasBeenTamperedWith() {
        TokenService service = serviceAt(NOW);
        String token = service.generateAccessToken(customer());
        String[] parts = token.split("\\.");
        String tamperedPayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"sub\":\"" + UUID.randomUUID() + "\",\"roles\":[\"ADMIN\"]}")
                        .getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() ->
                service.parseAccessToken(parts[0] + "." + tamperedPayload + "." + parts[2]))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void rejectsAnUnsignedToken() {
        String unsigned = Jwts.builder()
                .issuer("shopflow")
                .subject(UUID.randomUUID().toString())
                .claim("roles", java.util.List.of("ADMIN"))
                .compact();

        assertThatThrownBy(() -> serviceAt(NOW).parseAccessToken(unsigned))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void rejectsATokenIssuedByAnotherSystem() {
        SecretKey ourKey = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        String wrongIssuer = Jwts.builder()
                .issuer("someone-else")
                .subject(UUID.randomUUID().toString())
                .claim("email", "person@example.com")
                .claim("roles", java.util.List.of("CUSTOMER"))
                .issuedAt(Date.from(NOW))
                .expiration(Date.from(NOW.plus(Duration.ofMinutes(15))))
                .signWith(ourKey)
                .compact();

        assertThatThrownBy(() -> serviceAt(NOW).parseAccessToken(wrongIssuer))
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    void rejectsGarbageAndEmptyInput() {
        TokenService service = serviceAt(NOW);

        assertThatThrownBy(() -> service.parseAccessToken("not-a-jwt"))
                .isInstanceOf(InvalidTokenException.class);
        assertThatThrownBy(() -> service.parseAccessToken(""))
                .isInstanceOf(InvalidTokenException.class);
        assertThatThrownBy(() -> service.parseAccessToken(null))
                .isInstanceOf(InvalidTokenException.class);
    }
}
