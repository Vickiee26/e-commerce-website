package com.mvp.ecommercebackend.auth.dto;

/**
 * The only place a raw refresh token ever appears. It is not stored in this form and is never
 * logged.
 *
 * @param expiresIn access token lifetime in seconds, so a client need not parse the JWT
 */
public record TokenPairResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn) {
}
