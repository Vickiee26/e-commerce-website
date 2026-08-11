package com.mvp.ecommercebackend.commerce.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.Getter;
import lombok.Setter;

/**
 * Where an order was shipped, copied from the customer's address book at placement.
 *
 * <p>A copy rather than a foreign key on purpose: an order is a historical record. If it pointed at
 * {@code addresses}, correcting a typo in the address book tomorrow would silently change where
 * last month's order says it went, and deleting the address would erase it.
 */
@Embeddable
@Getter
@Setter
public class ShippingAddressSnapshot {

    @Column(name = "ship_recipient_name", nullable = false)
    private String recipientName;

    @Column(name = "ship_phone", length = 30)
    private String phone;

    @Column(name = "ship_line1", nullable = false)
    private String line1;

    @Column(name = "ship_line2")
    private String line2;

    @Column(name = "ship_city", nullable = false, length = 120)
    private String city;

    @Column(name = "ship_state", length = 120)
    private String state;

    @Column(name = "ship_postal_code", nullable = false, length = 20)
    private String postalCode;

    /** ISO 3166-1 alpha-2. */
    @Column(name = "ship_country", nullable = false, length = 2)
    private String country;
}
