package com.mvp.ecommercebackend.auth;

import java.util.List;
import java.util.UUID;

/**
 * The authenticated principal, built purely from verified token claims.
 *
 * <p>Holding the roles here is what lets a request be authorised without touching the database.
 * The trade-off is that a role change does not take effect until the current access token expires,
 * which is bounded by the fifteen-minute lifetime.
 */
public record AuthenticatedUser(UUID id, String email, List<String> roles) {

    public boolean hasRole(String role) {
        return roles.contains(role);
    }
}
