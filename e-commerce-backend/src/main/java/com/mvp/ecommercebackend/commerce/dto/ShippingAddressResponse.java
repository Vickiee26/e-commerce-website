package com.mvp.ecommercebackend.commerce.dto;

/** The address an order was shipped to, as recorded at placement. Has no id: it is not a row. */
public record ShippingAddressResponse(
        String recipientName,
        String phone,
        String line1,
        String line2,
        String city,
        String state,
        String postalCode,
        String country) {
}
