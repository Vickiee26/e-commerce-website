package com.mvp.ecommercebackend.commerce.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param paymentMethodToken an opaque token standing in for the customer's instrument. Card details
 *                           never reach this application, which is the point of a token: there is
 *                           nothing here worth stealing and nothing to store. The token itself is
 *                           not persisted or logged.
 */
public record PayOrderRequest(
        @NotBlank(message = "must not be blank")
        @Size(max = 100, message = "must not exceed 100 characters") String paymentMethodToken) {
}
