package com.mvp.ecommercebackend.admin.entity;

/**
 * What an administrator did. Stored as a string, so the trail stays readable in SQL and adding a
 * value never renumbers the rows already written.
 */
public enum AdminEventType {

    CATEGORY_CREATED,
    CATEGORY_UPDATED,
    CATEGORY_DELETED,
    CATEGORY_TYPE_CREATED,
    CATEGORY_TYPE_UPDATED,
    CATEGORY_TYPE_DELETED,
    PRODUCT_CREATED,
    PRODUCT_UPDATED,
    PRODUCT_ARCHIVED,
    PRODUCT_RESTORED,
    VARIANT_CREATED,
    VARIANT_UPDATED,
    VARIANT_ARCHIVED,
    VARIANT_RESTORED,
    STOCK_ADJUSTED,
    RESOURCE_CREATED,
    RESOURCE_UPDATED,
    RESOURCE_DELETED,
    ORDER_SHIPPED,
    ORDER_DELIVERED,
    ORDER_CANCELLED
}
