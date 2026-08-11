package com.mvp.ecommercebackend.common;

/**
 * Not enough stock to satisfy a request. Answered as 409, because the request is well formed and
 * the resource exists — it is the state of the world that refuses it, and retrying later may work.
 */
public class InsufficientStockException extends RuntimeException {

    public InsufficientStockException(String message) {
        super(message);
    }
}
