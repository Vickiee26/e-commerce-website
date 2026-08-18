package com.mvp.ecommercebackend.catalog.repository;

import com.mvp.ecommercebackend.catalog.entity.CategoryType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Types are read through their category for public navigation; administration needs them by id, and
 * needs to know whether a code is already taken inside a category.
 */
@Repository
public interface CategoryTypeRepository extends JpaRepository<CategoryType, UUID> {

    /**
     * The uniqueness check for a type code.
     *
     * <p>Scoped to the parent because {@code category_types.code} carries no unique constraint in
     * the schema, and two categories may legitimately each hold a "running-shoes" type.
     */
    boolean existsByCategoryIdAndCode(UUID categoryId, String code);
}
