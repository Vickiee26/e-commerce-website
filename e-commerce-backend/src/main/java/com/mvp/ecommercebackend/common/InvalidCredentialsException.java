package com.mvp.ecommercebackend.common;

/**
 * Authentication failed. Mapped to 401 with a deliberately generic body: the message must never
 * reveal whether the email exists.
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException(String message) {
        super(message);
    }
}
