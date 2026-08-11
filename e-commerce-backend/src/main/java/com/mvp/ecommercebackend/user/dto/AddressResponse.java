package com.mvp.ecommercebackend.user.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * An address as its owner sees it. Deliberately carries no user id: the owner is always the caller,
 * so echoing it back would only invite a client to treat it as a parameter.
 */
public record AddressResponse(
        UUID id,
        String recipientName,
        String phone,
        String line1,
        String line2,
        String city,
        String state,
        String postalCode,
        String country,
        boolean defaultShipping,
        boolean defaultBilling,
        Instant createdAt) {
}
