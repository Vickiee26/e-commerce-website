package com.mvp.ecommercebackend.catalog;

import com.mvp.ecommercebackend.catalog.entity.Product;
import org.springframework.data.jpa.domain.Specification;

import java.util.Locale;
import java.util.UUID;

/**
 * The optional filters behind {@code GET /api/products}.
 *
 * <p>Specifications rather than a query with nullable parameters: {@code (:categoryId is null or
 * ...)} makes Postgres guess the type of an untyped null and fail with "could not determine data
 * type of parameter". Composing predicates only for the filters actually supplied avoids the
 * question entirely, and keeps the generated SQL to what was asked for.
 */
final class ProductSpecifications {

    private static final char ESCAPE = '\\';

    private ProductSpecifications() {
    }

    static Specification<Product> inCategory(UUID categoryId) {
        return (root, query, builder) -> builder.equal(root.get("category").get("id"), categoryId);
    }

    static Specification<Product> inCategoryType(UUID categoryTypeId) {
        return (root, query, builder) ->
                builder.equal(root.get("categoryType").get("id"), categoryTypeId);
    }

    /**
     * Case-insensitive substring match on the name. Wildcards in the search term are escaped, so a
     * term of "%" matches a literal percent sign rather than every product.
     */
    static Specification<Product> nameContains(String term) {
        String pattern = "%" + escapeWildcards(term.toLowerCase(Locale.ROOT)) + "%";
        return (root, query, builder) ->
                builder.like(builder.lower(root.get("name")), pattern, ESCAPE);
    }

    /**
     * Excludes archived products.
     *
     * <p>Applied unconditionally by {@code ProductService.filters}, not as an optional filter: the
     * public listing has no legitimate reason to show a retired product, and making it opt-out would
     * put one query parameter between a customer and products that are not for sale.
     */
    static Specification<Product> notArchived() {
        return (root, query, builder) -> builder.isNull(root.get("archivedAt"));
    }

    private static String escapeWildcards(String term) {
        return term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
