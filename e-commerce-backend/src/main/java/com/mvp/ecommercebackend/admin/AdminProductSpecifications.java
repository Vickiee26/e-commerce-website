package com.mvp.ecommercebackend.admin;

import com.mvp.ecommercebackend.catalog.entity.Product;
import org.springframework.data.jpa.domain.Specification;

import java.util.Locale;
import java.util.UUID;

/**
 * Filters behind {@code GET /api/admin/products}.
 *
 * <p>Separate from the catalogue's {@code ProductSpecifications}, which is package-private and has
 * no archive predicates: widening it to public would put an internal query helper on the
 * {@code catalog} package's API for the sake of one caller in another package.
 */
final class AdminProductSpecifications {

    private static final char ESCAPE = '\\';

    private AdminProductSpecifications() {
    }

    static Specification<Product> notArchived() {
        return (root, query, builder) -> builder.isNull(root.get("archivedAt"));
    }

    static Specification<Product> archivedOnly() {
        return (root, query, builder) -> builder.isNotNull(root.get("archivedAt"));
    }

    static Specification<Product> inCategory(UUID categoryId) {
        return (root, query, builder) -> builder.equal(root.get("category").get("id"), categoryId);
    }

    /** Case-insensitive substring match. Wildcards in the term are escaped, so "%" is a literal. */
    static Specification<Product> nameContains(String term) {
        String pattern = "%" + escapeWildcards(term.toLowerCase(Locale.ROOT)) + "%";
        return (root, query, builder) ->
                builder.like(builder.lower(root.get("name")), pattern, ESCAPE);
    }

    private static String escapeWildcards(String term) {
        return term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
