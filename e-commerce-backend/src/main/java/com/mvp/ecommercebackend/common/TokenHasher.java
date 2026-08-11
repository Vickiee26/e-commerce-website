package com.mvp.ecommercebackend.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Hashes opaque bearer tokens for storage.
 *
 * <p>Plain SHA-256 with no salt or work factor is the right primitive here, and deliberately not
 * BCrypt. These values are 256 bits of {@link java.security.SecureRandom} output, so there is no
 * dictionary to defend against, and lookup happens <em>by hash</em> against a unique index — which
 * a per-row salt would make impossible without scanning the table.
 */
public final class TokenHasher {

    private TokenHasher() {
    }

    public static String sha256Hex(String rawValue) {
        if (rawValue == null) {
            throw new IllegalArgumentException("Token value must not be null");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawValue.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            // SHA-256 is mandated by the JLS; this cannot happen on a conforming JVM.
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
