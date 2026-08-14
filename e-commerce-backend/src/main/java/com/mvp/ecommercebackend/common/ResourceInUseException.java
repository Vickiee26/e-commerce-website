package com.mvp.ecommercebackend.common;

/**
 * Something else still references this row, so it cannot be removed. Mapped to 409.
 *
 * <p>Distinct from {@link DuplicateResourceException}: "already exists" and "still in use" are
 * opposite problems, and sharing one exception would make both call sites read wrongly.
 */
public class ResourceInUseException extends RuntimeException {

    public ResourceInUseException(String message) {
        super(message);
    }
}
