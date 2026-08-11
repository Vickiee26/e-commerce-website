package com.mvp.ecommercebackend.common;

/**
 * The order cannot do what was asked of it in its current state — paying an order that is already
 * paid, cancelling one that is paid, checking out an empty cart.
 *
 * <p>Answered as 409: the request is well formed and the caller is entitled to make it, but the
 * resource is not in a state that permits it.
 */
public class InvalidOrderStateException extends RuntimeException {

    public InvalidOrderStateException(String message) {
        super(message);
    }
}
