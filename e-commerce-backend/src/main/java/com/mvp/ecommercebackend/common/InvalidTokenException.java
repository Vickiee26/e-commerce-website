package com.mvp.ecommercebackend.common;

/** A token was missing, malformed, expired, revoked, or signed with the wrong key. Mapped to 401. */
public class InvalidTokenException extends RuntimeException {

    public InvalidTokenException(String message) {
        super(message);
    }
}
