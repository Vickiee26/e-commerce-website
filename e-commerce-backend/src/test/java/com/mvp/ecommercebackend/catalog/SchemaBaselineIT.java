package com.mvp.ecommercebackend.catalog;

import com.mvp.ecommercebackend.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the schema contract. If this class loads at all, Hibernate's ddl-auto=validate has
 * already confirmed that every entity mapping matches a Flyway-created column, which is the
 * drift check the design doc asks for.
 */
class SchemaBaselineIT extends AbstractIntegrationTest {

    @Test
    void flywayCreatesEveryBaselineTable() {
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                String.class);

        assertThat(tables).contains(
                "roles", "users", "user_roles", "addresses", "refresh_tokens",
                "password_reset_tokens", "auth_events",
                "categories", "category_types", "products", "product_variants",
                "product_resources", "admin_events");
    }

    @Test
    void seedsTheTwoRoles() {
        List<String> codes = jdbcTemplate.queryForList(
                "SELECT code FROM roles ORDER BY code", String.class);

        assertThat(codes).containsExactly("ADMIN", "CUSTOMER");
    }

    @Test
    void enforcesCaseInsensitiveEmailUniqueness() {
        List<String> indexes = jdbcTemplate.queryForList(
                "SELECT indexdef FROM pg_indexes WHERE tablename = 'users'", String.class);

        // Postgres normalises lower(email) on a varchar column to lower((email)::text) when it
        // renders the stored index definition, so assert against that canonical form.
        assertThat(indexes).anyMatch(definition ->
                definition.contains("UNIQUE") && definition.contains("lower((email)"));
    }

    @Test
    void allPrimaryKeysAreUuid() {
        List<String> nonUuidKeys = jdbcTemplate.queryForList("""
                SELECT c.table_name
                FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage k
                  ON tc.constraint_name = k.constraint_name
                JOIN information_schema.columns c
                  ON c.table_name = k.table_name AND c.column_name = k.column_name
                WHERE tc.constraint_type = 'PRIMARY KEY'
                  AND tc.table_schema = 'public'
                  AND c.table_name <> 'flyway_schema_history'
                  AND c.data_type <> 'uuid'
                """, String.class);

        assertThat(nonUuidKeys).isEmpty();
    }

    @Test
    void addsArchiveAndFulfilmentColumns() {
        assertThat(columnsOf("products")).contains("archived_at");
        assertThat(columnsOf("product_variants")).contains("archived_at");
        assertThat(columnsOf("orders"))
                .contains("shipped_at", "delivered_at", "tracking_reference");
    }

    @Test
    void allowsTheTwoNewOrderStatuses() {
        String check = jdbcTemplate.queryForObject("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                WHERE conname = 'ck_orders_status'
                """, String.class);

        assertThat(check).contains("SHIPPED").contains("DELIVERED");
    }

    /**
     * The two uniqueness rules V1 was missing. Asserted here rather than left to a service-level
     * check, because only the database can stop two concurrent inserts.
     */
    @Test
    void enforcesUniquenessOnCategoryTypeCodesAndLiveVariants() {
        assertThat(jdbcTemplate.queryForObject("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                WHERE conname = 'uq_category_types_category_code'
                """, String.class))
                .isEqualTo("UNIQUE (category_id, code)");

        // A partial index, so archiving a variant frees its colour and size for reuse.
        assertThat(jdbcTemplate.queryForObject("""
                SELECT indexdef FROM pg_indexes WHERE indexname = 'uq_product_variants_live'
                """, String.class))
                .contains("UNIQUE")
                .contains("product_id, color, size")
                .contains("WHERE (archived_at IS NULL)");
    }

    private List<String> columnsOf(String table) {
        return jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = ?",
                String.class, table);
    }
}
