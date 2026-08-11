package com.mvp.ecommercebackend.common;

/** Mapped to 429. */
public class RateLimitExceededException extends RuntimeException {

    public RateLimitExceededException(String message) {
        super(message);
    }
}
