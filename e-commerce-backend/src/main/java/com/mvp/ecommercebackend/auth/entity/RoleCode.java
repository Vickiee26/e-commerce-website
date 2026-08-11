package com.mvp.ecommercebackend.auth.entity;

/**
 * The role codes seeded by {@code V2__seed_roles.sql}. Registration may only ever grant
 * {@link #CUSTOMER}.
 */
public enum RoleCode {
    CUSTOMER,
    ADMIN
}
