package com.mvp.ecommercebackend.admin.entity;

/**
 * What the action was performed on.
 *
 * <p>An enum rather than a free-text string: {@code target_id} has no foreign key (the row it names
 * may since have been deleted), so this is the only thing that says which table to look in, and a
 * typo would quietly orphan an entry from every search.
 */
public enum AdminTargetType {

    CATEGORY,
    CATEGORY_TYPE,
    PRODUCT,
    PRODUCT_VARIANT,
    PRODUCT_RESOURCE,
    ORDER
}
