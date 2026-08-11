package com.mvp.ecommercebackend.user.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The profile as the owner sees it. A hand-written projection rather than the entity, so a field
 * added to {@code User} later — a password hash, a lockout counter — cannot leak by default.
 */
public record UserProfileResponse(
        UUID id,
        String email,
        String fullName,
        String phone,
        boolean emailVerified,
        List<String> roles,
        Instant createdAt) {
}
