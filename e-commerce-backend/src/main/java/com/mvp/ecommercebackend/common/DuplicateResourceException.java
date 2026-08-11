package com.mvp.ecommercebackend.common;

/** Something already exists that must be unique. Mapped to 409. */
public class DuplicateResourceException extends RuntimeException {

    public DuplicateResourceException(String message) {
        super(message);
    }
}
