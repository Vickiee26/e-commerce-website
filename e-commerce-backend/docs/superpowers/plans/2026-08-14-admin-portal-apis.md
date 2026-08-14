# Admin Portal APIs Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give administrators an HTTP surface under `/api/admin/**` for managing the catalogue (categories, products, variants, images, stock) and progressing orders through fulfilment, with a soft-delete archive model and an audit trail.

**Architecture:** A new `admin` package holds its own controllers, services, DTOs and Specifications so the public `catalog` package stays read-only. Deletes are soft (`archived_at`) because `cart_items.product_variant_id` is `ON DELETE CASCADE` and a hard delete would silently empty live carts. Stock only ever changes through a signed-delta endpoint that reads the row under the same `FOR UPDATE` lock `OrderService.placeOrder` uses, so no code path can overwrite stock from a stale read. Every mutation writes one `admin_events` row inside the caller's transaction.

**Tech Stack:** Spring Boot 4.1.0 (Spring Framework 7), Java 17, Spring Data JPA + Hibernate, Flyway 12, Postgres 16, springdoc-openapi 3.1.0, Lombok, JUnit 5 + AssertJ + MockMvc + Testcontainers 2.x.

**Spec:** `docs/superpowers/specs/2026-08-14-admin-portal-design.md`

## Global Constraints

Every task's requirements implicitly include this section.

- **Java 17** target (`<java.version>17</java.version>`). No `SequencedCollection` methods — `List.getFirst()` does not compile. Use `list.get(0)`.
- **Spring Boot 4.1.0.** springdoc is the 3.x line; `io.swagger.v3.oas.annotations.*` for `@Tag` and `@Operation`.
- **Jackson 3.** The injectable mapper is `tools.jackson.databind.ObjectMapper`. Jackson 2 (`com.fasterxml.jackson`) is on the classpath transitively but has **no bean** in Boot 4.
- **Testcontainers 2.x.** `org.testcontainers.postgresql.PostgreSQLContainer` is **not generic**, and is bound with `@ServiceConnection` — never `@Container`, never `@Testcontainers`. Already handled by `AbstractIntegrationTest`; do not change it.
- **`spring.jpa.hibernate.ddl-auto=validate`.** Every entity field must match a Flyway-created column exactly, or the whole context fails to start. Migrations and entities change in the same commit.
- **`spring.jpa.open-in-view=false`.** There is no Hibernate session outside a transaction. Any method walking a lazy association needs `@Transactional`, or it throws `LazyInitializationException`.
- **Flyway is forward-only.** Add `V5__admin.sql`; never edit `V1`–`V4`. Never set `baselineOnMigrate`.
- **Lombok on entities: `@Getter` and `@Setter` only.** Never `@Data` — it generates `equals`/`hashCode` that override `BaseEntity`'s id-only implementation.
- **Entities extend `com.mvp.ecommercebackend.common.BaseEntity`**, which supplies `UUID id`, `createdAt`, `updatedAt` and the `@PrePersist`/`@PreUpdate` hooks. Never declare those fields again.
- **Errors leave as RFC 7807 `ProblemDetail`** from `GlobalExceptionHandler`. Controllers never build error bodies.
- **Pagination convention:** return `PageResponse<T>`; bound `page` with `@Min(0)`, `size` with `@Min(1) @Max(100)`, and restrict `sort` with an `@Pattern` whitelist so no caller can sort by an arbitrary entity path.
- **Admin controllers carry no `@PreAuthorize`.** `SecurityConfig:66` already has `.requestMatchers("/api/admin/**").hasRole("ADMIN")` above `.anyRequest().authenticated()`. Do not touch `SecurityConfig`.
- **The audit actor comes from the authenticated principal, never from the request body.**
- **`OpenApiIT.EVERY_PATH` asserts the path list *exactly*** (`containsExactlyInAnyOrderElementsOf`). Any task that adds an endpoint must add its path to `src/test/java/com/mvp/ecommercebackend/config/OpenApiIT.java:24-43` **in the same commit**, or `mvn verify` fails. Each new controller method also carries `@Operation(summary = ...)`, and each new controller carries `@Tag(name = "Admin ...")` and `@SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME)`.
- **Local database is on port 5433** (`DB_PORT=5433` in the gitignored `.env`); a native Postgres owns 5432 on this machine. Tests use Testcontainers and are unaffected.
- **Payments stay simulated.** Do not touch `PaymentGateway`, `SimulatedPaymentGateway`, or `OrderService.payOrder`.
- **Package root:** `com.mvp.ecommercebackend`. Source root `src/main/java/com/mvp/ecommercebackend`, test root `src/test/java/com/mvp/ecommercebackend`.
- **Build command:** `mvn verify` (Failsafe runs `*IT`, Surefire runs `*Test`). A single IT: `mvn verify -Dit.test=ClassName`. A single unit test: `mvn test -Dtest=ClassName`.

## File Structure

New package `com.mvp.ecommercebackend.admin`. Split by responsibility: each aggregate gets a controller (HTTP shape, validation, OpenAPI) plus a service (transaction boundary, invariants, audit).

| File | Responsibility |
|---|---|
| `admin/AdminCategoryController.java` | Category and category-type endpoints |
| `admin/AdminCategoryService.java` | Category/type writes, duplicate-code and in-use checks |
| `admin/AdminProductController.java` | Product list, detail, create, patch, archive, restore |
| `admin/AdminProductService.java` | Product writes; admin projection including archived rows |
| `admin/AdminProductSpecifications.java` | Admin-only product filters (the `catalog` one is package-private) |
| `admin/AdminVariantController.java` | Variant create/patch/archive/restore and the stock delta |
| `admin/AdminVariantService.java` | Variant writes; the locked stock adjustment |
| `admin/AdminResourceController.java` | Product image/resource endpoints |
| `admin/AdminResourceService.java` | Resource writes and `isPrimary` reassignment |
| `admin/AdminOrderController.java` | Order search, detail, ship, deliver, cancel |
| `admin/AdminOrderService.java` | Fulfilment state machine |
| `admin/AdminOrderSpecifications.java` | Order search filters |
| `admin/AdminEventController.java` | Read-only audit search |
| `admin/AdminEventService.java` | Writes the audit trail; also serves the search |
| `admin/AdminEventSpecifications.java` | Audit search filters |
| `admin/entity/AdminEvent.java`, `admin/entity/AdminEventType.java`, `admin/entity/AdminTargetType.java` | Audit row and its two enums |
| `admin/repository/AdminEventRepository.java` | `JpaRepository` + `JpaSpecificationExecutor` |
| `admin/dto/*.java` | Request and response records, one per file |
| `catalog/repository/CategoryTypeRepository.java`, `catalog/repository/ResourceRepository.java` | New — admin needs to load a type and a resource by id |
| `src/main/resources/db/migration/V5__admin.sql` | Archive columns, fulfilment columns, status CHECK, `admin_events` |

Resources get their own controller and service rather than living in `AdminVariantController` where the spec's API listing grouped them: variants and images share only a parent product, and one file per aggregate keeps each under a screen.

---

### Task 1: Schema and entity changes for archive, fulfilment, and audit

**Files:**
- Create: `src/main/resources/db/migration/V5__admin.sql`
- Modify: `src/main/java/com/mvp/ecommercebackend/catalog/entity/Product.java`
- Modify: `src/main/java/com/mvp/ecommercebackend/catalog/entity/ProductVariant.java`
- Modify: `src/main/java/com/mvp/ecommercebackend/commerce/entity/Order.java`
- Modify: `src/main/java/com/mvp/ecommercebackend/commerce/entity/OrderStatus.java`
- Modify: `src/test/java/com/mvp/ecommercebackend/support/AbstractIntegrationTest.java:66-85`
- Test: `src/test/java/com/mvp/ecommercebackend/catalog/SchemaBaselineIT.java:17-28`

**Interfaces:**
- Consumes: nothing.
- Produces: `Product.getArchivedAt()/setArchivedAt(Instant)`, `ProductVariant.getArchivedAt()/setArchivedAt(Instant)`, `Order.getShippedAt()/setShippedAt(Instant)`, `Order.getDeliveredAt()/setDeliveredAt(Instant)`, `Order.getTrackingReference()/setTrackingReference(String)`, `OrderStatus.SHIPPED`, `OrderStatus.DELIVERED`, the `admin_events` table, and two uniqueness rules later tasks rely on: `uq_category_types_category_code` on `(category_id, code)` and the partial unique index `uq_product_variants_live` on `(product_id, color, size) WHERE archived_at IS NULL`.

- [ ] **Step 1: Write the failing test**

In `SchemaBaselineIT`, add `"admin_events"` to the existing `assertThat(tables).contains(...)` list so it reads:

```java
        assertThat(tables).contains(
                "roles", "users", "user_roles", "addresses", "refresh_tokens",
                "password_reset_tokens", "auth_events",
                "categories", "category_types", "products", "product_variants",
                "product_resources", "admin_events");
```

Then append three new tests to the same class:

```java
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
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn verify -Dit.test=SchemaBaselineIT`
Expected: FAIL. `flywayCreatesEveryBaselineTable` reports `admin_events` missing, `addsArchiveAndFulfilmentColumns` reports `archived_at` missing, `allowsTheTwoNewOrderStatuses` fails because the constraint lists only three statuses, and `enforcesUniquenessOnCategoryTypeCodesAndLiveVariants` fails with an `EmptyResultDataAccessException` because neither rule exists yet.

If the `indexdef` assertion fails on exact text rather than absence, print the actual value and match it — Postgres normalises index definitions and the whitespace in `product_id, color, size` is version-dependent. Assert on the substance, not on Postgres's formatting.

- [ ] **Step 3: Write the migration**

Create `src/main/resources/db/migration/V5__admin.sql`:

```sql
-- Administration: soft-delete archive, order fulfilment, and an audit trail.
--
-- Catalogue rows are archived rather than deleted. `cart_items.product_variant_id` is
-- ON DELETE CASCADE, so a hard delete of a variant silently empties every live cart holding it.
-- `order_items` needs no such protection: it snapshots the product name, price and variant
-- attributes, and its foreign keys are ON DELETE SET NULL.
--
-- Categories and category_types deliberately get no archived_at. Deleting a category a product
-- still references is blocked by the products.category_id foreign key, which is the right answer;
-- the service turns that into a deliberate 409 instead of letting it surface as a 500.
ALTER TABLE products         ADD COLUMN archived_at timestamptz;
ALTER TABLE product_variants ADD COLUMN archived_at timestamptz;

ALTER TABLE orders ADD COLUMN shipped_at         timestamptz,
                   ADD COLUMN delivered_at       timestamptz,
                   ADD COLUMN tracking_reference varchar(100);

-- ck_orders_status is pinned to three literal values, so it has to be replaced rather than
-- extended. The service enforces the legal transitions; this constraint only bounds the column.
ALTER TABLE orders DROP CONSTRAINT ck_orders_status;
ALTER TABLE orders ADD  CONSTRAINT ck_orders_status
    CHECK (status IN ('PENDING_PAYMENT', 'PAID', 'SHIPPED', 'DELIVERED', 'CANCELLED'));

-- actor_user_id is ON DELETE SET NULL, matching auth_events: removing an administrator must not
-- erase the record that an action took place.
CREATE TABLE admin_events (
    id            uuid          PRIMARY KEY,
    actor_user_id uuid          REFERENCES users (id) ON DELETE SET NULL,
    action        varchar(40)   NOT NULL,
    target_type   varchar(30)   NOT NULL,
    target_id     uuid,
    detail        varchar(1000),
    created_at    timestamptz   NOT NULL,
    updated_at    timestamptz   NOT NULL
);

-- "what happened to this product" and "what did this admin do", the two ways the trail is read.
CREATE INDEX idx_admin_events_target ON admin_events (target_type, target_id);
CREATE INDEX idx_admin_events_actor  ON admin_events (actor_user_id, created_at DESC);

-- The admin order search pages by status, newest first.
CREATE INDEX idx_orders_status ON orders (status, placed_at DESC);

-- Partial index: the public catalogue only ever asks for unarchived products.
CREATE INDEX idx_products_active ON products (id) WHERE archived_at IS NULL;

-- Uniqueness the schema was missing. Without these, two concurrent admin requests both pass a
-- service-level duplicate check and both insert; the service check stays as the fast path that
-- produces a readable 409, but the guarantee lives here.
--
-- `categories.code` already has uq_categories_code; `category_types.code` had nothing. Scoped to
-- the parent category, because "RUNNING" under Footwear and "RUNNING" under Apparel are different
-- types and both are legitimate.
ALTER TABLE category_types
    ADD CONSTRAINT uq_category_types_category_code UNIQUE (category_id, code);

-- Partial, so archiving a variant frees its colour and size for reuse. A plain unique constraint
-- would let a retired "Black / 42" block its own replacement forever.
CREATE UNIQUE INDEX uq_product_variants_live
    ON product_variants (product_id, color, size)
    WHERE archived_at IS NULL;
```

The two uniqueness rules are new to this migration, not carried over from `V1__init.sql`. If either
statement fails on the dev database, duplicate rows already exist there — inspect them with the
grouped query below and delete the extras before re-running, rather than dropping the constraint:

```sql
SELECT category_id, code, count(*) FROM category_types
 GROUP BY category_id, code HAVING count(*) > 1;
SELECT product_id, color, size, count(*) FROM product_variants
 WHERE archived_at IS NULL GROUP BY product_id, color, size HAVING count(*) > 1;
```

- [ ] **Step 4: Add the entity fields**

In `Product.java`, add the import `java.time.Instant` and this field after `resources`:

```java
    /**
     * Set instead of deleting the row. Archiving hides the product from the public catalogue while
     * leaving order history and live carts intact.
     */
    @Column(name = "archived_at")
    private Instant archivedAt;
```

In `ProductVariant.java`, add the import `java.time.Instant` and this field after `product`:

```java
    /** Independent of the parent product's flag: restoring a product must not resurrect a variant
     * that was retired separately. */
    @Column(name = "archived_at")
    private Instant archivedAt;
```

In `Order.java`, add these fields after `cancelledAt`:

```java
    @Column(name = "shipped_at")
    private Instant shippedAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    /** The carrier's consignment number. Free text: every carrier formats it differently. */
    @Column(name = "tracking_reference", length = 100)
    private String trackingReference;
```

Replace the body of `OrderStatus.java` with:

```java
package com.mvp.ecommercebackend.commerce.entity;

/**
 * The order lifecycle.
 *
 * <p>Legal transitions are {@code PENDING_PAYMENT → PAID → SHIPPED → DELIVERED} plus
 * {@code PENDING_PAYMENT → CANCELLED}. A paid order cannot be cancelled and a shipment cannot be
 * reversed: both mean returning money, which needs its own record of who authorised it and how
 * much came back, and that is a later slice. {@code DELIVERED} and {@code CANCELLED} are terminal.
 */
public enum OrderStatus {

    PENDING_PAYMENT,
    PAID,
    SHIPPED,
    DELIVERED,
    CANCELLED
}
```

- [ ] **Step 5: Add `admin_events` to the test truncation list**

In `AbstractIntegrationTest.resetDatabase()`, add `admin_events` as the first table in the `TRUNCATE`:

```java
        jdbcTemplate.execute("""
                TRUNCATE TABLE admin_events,
                               auth_events,
                               password_reset_tokens,
                               refresh_tokens,
                               addresses,
                               cart_items,
                               carts,
                               order_items,
                               orders,
                               user_roles,
                               users,
                               product_resources,
                               product_variants,
                               products,
                               category_types,
                               categories
                RESTART IDENTITY CASCADE
                """);
```

- [ ] **Step 6: Run the full suite to verify it passes**

Run: `mvn verify`
Expected: PASS. `ddl-auto=validate` starting the context at all proves every new entity field matches a migrated column; the three `SchemaBaselineIT` assertions prove the migration ran.

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/db/migration/V5__admin.sql \
        src/main/java/com/mvp/ecommercebackend/catalog/entity/Product.java \
        src/main/java/com/mvp/ecommercebackend/catalog/entity/ProductVariant.java \
        src/main/java/com/mvp/ecommercebackend/commerce/entity/Order.java \
        src/main/java/com/mvp/ecommercebackend/commerce/entity/OrderStatus.java \
        src/test/java/com/mvp/ecommercebackend/catalog/SchemaBaselineIT.java \
        src/test/java/com/mvp/ecommercebackend/support/AbstractIntegrationTest.java
git commit -m "feat(admin): schema for archiving, fulfilment, and the audit trail"
```

---

### Task 2: Audit trail infrastructure

Every later task calls `AdminEventService.record(...)`, so it lands first. Modelled on
`AuthEventService`: the audit write joins the caller's transaction, so a mutation and its audit row
commit together or roll back together.

**Files:**
- Create: `src/main/java/com/mvp/ecommercebackend/admin/entity/AdminEvent.java`
- Create: `src/main/java/com/mvp/ecommercebackend/admin/entity/AdminEventType.java`
- Create: `src/main/java/com/mvp/ecommercebackend/admin/entity/AdminTargetType.java`
- Create: `src/main/java/com/mvp/ecommercebackend/admin/repository/AdminEventRepository.java`
- Create: `src/main/java/com/mvp/ecommercebackend/admin/AdminEventService.java`
- Modify: `src/test/java/com/mvp/ecommercebackend/support/AbstractIntegrationTest.java`
- Test: `src/test/java/com/mvp/ecommercebackend/admin/AdminEventServiceIT.java`

**Interfaces:**
- Consumes: `admin_events` table from Task 1.
- Produces:
  - `AdminEventService.record(UUID actorUserId, AdminEventType action, AdminTargetType targetType, UUID targetId, String detail)` — `void`, `@Transactional`, joins the caller's transaction.
  - `AdminEventType` — enum: `CATEGORY_CREATED`, `CATEGORY_UPDATED`, `CATEGORY_DELETED`, `CATEGORY_TYPE_CREATED`, `CATEGORY_TYPE_UPDATED`, `CATEGORY_TYPE_DELETED`, `PRODUCT_CREATED`, `PRODUCT_UPDATED`, `PRODUCT_ARCHIVED`, `PRODUCT_RESTORED`, `VARIANT_CREATED`, `VARIANT_UPDATED`, `VARIANT_ARCHIVED`, `VARIANT_RESTORED`, `STOCK_ADJUSTED`, `RESOURCE_CREATED`, `RESOURCE_UPDATED`, `RESOURCE_DELETED`, `ORDER_SHIPPED`, `ORDER_DELIVERED`, `ORDER_CANCELLED`.
  - `AdminTargetType` — enum: `CATEGORY`, `CATEGORY_TYPE`, `PRODUCT`, `PRODUCT_VARIANT`, `PRODUCT_RESOURCE`, `ORDER`.
  - `AdminEventRepository extends JpaRepository<AdminEvent, UUID>, JpaSpecificationExecutor<AdminEvent>`.
  - `AbstractIntegrationTest.bearer(User user)` — `protected String`, returns `"Bearer " + <access token>`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/mvp/ecommercebackend/admin/AdminEventServiceIT.java`:

```java
package com.mvp.ecommercebackend.admin;

import com.mvp.ecommercebackend.admin.entity.AdminEventType;
import com.mvp.ecommercebackend.admin.entity.AdminTargetType;
import com.mvp.ecommercebackend.auth.entity.User;
import com.mvp.ecommercebackend.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AdminEventServiceIT extends AbstractIntegrationTest {

    @Autowired
    private AdminEventService adminEventService;

    @Test
    void recordsTheActorTheActionAndTheTarget() {
        User admin = testData.createAdmin("auditor@example.com", "correct-horse-battery");
        UUID targetId = UUID.randomUUID();

        adminEventService.record(admin.getId(), AdminEventType.PRODUCT_ARCHIVED,
                AdminTargetType.PRODUCT, targetId, "Discontinued");

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT actor_user_id, action, target_type, target_id, detail FROM admin_events");
        assertThat(row.get("actor_user_id")).isEqualTo(admin.getId());
        assertThat(row.get("action")).isEqualTo("PRODUCT_ARCHIVED");
        assertThat(row.get("target_type")).isEqualTo("PRODUCT");
        assertThat(row.get("target_id")).isEqualTo(targetId);
        assertThat(row.get("detail")).isEqualTo("Discontinued");
    }

    /** A detail longer than the column is truncated rather than throwing at the database. */
    @Test
    void truncatesAnOverlongDetail() {
        User admin = testData.createAdmin("auditor@example.com", "correct-horse-battery");

        adminEventService.record(admin.getId(), AdminEventType.PRODUCT_UPDATED,
                AdminTargetType.PRODUCT, UUID.randomUUID(), "x".repeat(1500));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT length(detail) FROM admin_events", Integer.class)).isEqualTo(1000);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn verify -Dit.test=AdminEventServiceIT`
Expected: FAIL at compilation — `package com.mvp.ecommercebackend.admin.entity does not exist`.

- [ ] **Step 3: Write the two enums**

`admin/entity/AdminEventType.java`:

```java
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
```

`admin/entity/AdminTargetType.java`:

```java
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
```

- [ ] **Step 4: Write the entity and repository**

`admin/entity/AdminEvent.java`:

```java
package com.mvp.ecommercebackend.admin.entity;

import com.mvp.ecommercebackend.auth.entity.User;
import com.mvp.ecommercebackend.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * One administrative action.
 *
 * <p>{@code actor} is nullable and its foreign key is {@code ON DELETE SET NULL}: removing an
 * administrator must not erase the record that the action took place.
 *
 * <p>{@code targetId} is a plain {@code UUID}, not an association. The row it names can be a
 * category, a product, a variant, a resource or an order, and it may have been deleted since —
 * neither of which a foreign key can express.
 */
@Entity
@Table(name = "admin_events")
@Getter
@Setter
public class AdminEvent extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_user_id")
    private User actor;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 40)
    private AdminEventType action;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 30)
    private AdminTargetType targetType;

    @Column(name = "target_id")
    private UUID targetId;

    /** Free text for context a client would otherwise have to diff two rows to recover. */
    @Column(name = "detail", length = 1000)
    private String detail;
}
```

`admin/repository/AdminEventRepository.java`:

```java
package com.mvp.ecommercebackend.admin.repository;

import com.mvp.ecommercebackend.admin.entity.AdminEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface AdminEventRepository extends JpaRepository<AdminEvent, UUID>,
        JpaSpecificationExecutor<AdminEvent> {
}
```

- [ ] **Step 5: Write the service**

`admin/AdminEventService.java`:

```java
package com.mvp.ecommercebackend.admin;

import com.mvp.ecommercebackend.admin.entity.AdminEvent;
import com.mvp.ecommercebackend.admin.entity.AdminEventType;
import com.mvp.ecommercebackend.admin.entity.AdminTargetType;
import com.mvp.ecommercebackend.admin.repository.AdminEventRepository;
import com.mvp.ecommercebackend.auth.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Writes the administrative audit trail. */
@Service
public class AdminEventService {

    /** Matches admin_events.detail. */
    private static final int DETAIL_LIMIT = 1000;

    private final AdminEventRepository adminEventRepository;
    private final UserRepository userRepository;

    public AdminEventService(AdminEventRepository adminEventRepository,
                             UserRepository userRepository) {
        this.adminEventRepository = adminEventRepository;
        this.userRepository = userRepository;
    }

    /**
     * Joins the caller's transaction, deliberately. A mutation and the record of it must commit
     * together: a trail that survives a rolled-back change would claim something happened that did
     * not, which is worse than no trail at all.
     *
     * @param actorUserId the authenticated administrator, taken from the principal and never from a
     *                    request body
     * @param detail      optional context; truncated rather than allowed to fail the insert
     */
    @Transactional
    public void record(UUID actorUserId, AdminEventType action, AdminTargetType targetType,
                       UUID targetId, String detail) {
        AdminEvent event = new AdminEvent();
        // A reference, not a fetch: only the foreign key is needed.
        event.setActor(actorUserId == null ? null : userRepository.getReferenceById(actorUserId));
        event.setAction(action);
        event.setTargetType(targetType);
        event.setTargetId(targetId);
        event.setDetail(truncate(detail));
        adminEventRepository.save(event);
    }

    private static String truncate(String detail) {
        if (detail == null || detail.length() <= DETAIL_LIMIT) {
            return detail;
        }
        return detail.substring(0, DETAIL_LIMIT);
    }
}
```

- [ ] **Step 6: Add the shared bearer-token helper to the test base class**

Every admin IT needs an `Authorization` header. Add to `AbstractIntegrationTest`, after the
`catalogData` field (imports: `com.mvp.ecommercebackend.auth.TokenService`,
`com.mvp.ecommercebackend.auth.entity.User`):

```java
    @Autowired
    protected TokenService tokenService;

    /** The {@code Authorization} header value for {@code user}, ready to pass to MockMvc. */
    protected String bearer(User user) {
        return "Bearer " + tokenService.generateAccessToken(user);
    }
```

- [ ] **Step 7: Run the test to verify it passes**

Run: `mvn verify -Dit.test=AdminEventServiceIT`
Expected: PASS, both tests.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/mvp/ecommercebackend/admin \
        src/test/java/com/mvp/ecommercebackend/admin/AdminEventServiceIT.java \
        src/test/java/com/mvp/ecommercebackend/support/AbstractIntegrationTest.java
git commit -m "feat(admin): audit trail entity, repository, and service"
```

---

### Task 3: Category and category-type administration

**Files:**
- Create: `src/main/java/com/mvp/ecommercebackend/common/ResourceInUseException.java`
- Create: `src/main/java/com/mvp/ecommercebackend/catalog/repository/CategoryTypeRepository.java`
- Create: `src/main/java/com/mvp/ecommercebackend/admin/dto/CreateCategoryRequest.java`
- Create: `src/main/java/com/mvp/ecommercebackend/admin/dto/UpdateCategoryRequest.java`
- Create: `src/main/java/com/mvp/ecommercebackend/admin/dto/CreateCategoryTypeRequest.java`
- Create: `src/main/java/com/mvp/ecommercebackend/admin/dto/UpdateCategoryTypeRequest.java`
- Create: `src/main/java/com/mvp/ecommercebackend/admin/AdminCategoryService.java`
- Create: `src/main/java/com/mvp/ecommercebackend/admin/AdminCategoryController.java`
- Modify: `src/main/java/com/mvp/ecommercebackend/common/GlobalExceptionHandler.java` (add two handlers: `ResourceInUseException` and `DataIntegrityViolationException`, both 409)
- Modify: `src/main/java/com/mvp/ecommercebackend/catalog/CategoryService.java:37` (make `toResponse` public static)
- Modify: `src/main/java/com/mvp/ecommercebackend/catalog/repository/ProductRepository.java` (two `exists` queries)
- Modify: `src/test/java/com/mvp/ecommercebackend/config/OpenApiIT.java:24-43`
- Test: `src/test/java/com/mvp/ecommercebackend/admin/AdminCategoryIT.java`

**Interfaces:**
- Consumes: `AdminEventService.record(...)`, `AdminEventType.CATEGORY_*`/`CATEGORY_TYPE_*`, `AdminTargetType.CATEGORY`/`CATEGORY_TYPE`, `AbstractIntegrationTest.bearer(User)`.
- Produces:
  - `ResourceInUseException(String message)` — `RuntimeException`, mapped to 409.
  - A `DataIntegrityViolationException` → 409 mapping in `GlobalExceptionHandler`, which every later task's unique constraints rely on to avoid surfacing a lost race as a 500.
  - `CategoryTypeRepository extends JpaRepository<CategoryType, UUID>` with `boolean existsByCategoryIdAndCode(UUID categoryId, String code)`.
  - `ProductRepository.existsByCategoryId(UUID)` and `ProductRepository.existsByCategoryTypeId(UUID)`.
  - `CategoryService.toResponse(Category)` — now `public static CategoryResponse`.
  - `AdminCategoryService`: `CategoryResponse createCategory(UUID actorUserId, CreateCategoryRequest request)`, `CategoryResponse updateCategory(UUID actorUserId, UUID categoryId, UpdateCategoryRequest request)`, `void deleteCategory(UUID actorUserId, UUID categoryId)`, `CategoryTypeResponse createCategoryType(UUID actorUserId, UUID categoryId, CreateCategoryTypeRequest request)`, `CategoryTypeResponse updateCategoryType(UUID actorUserId, UUID categoryTypeId, UpdateCategoryTypeRequest request)`, `void deleteCategoryType(UUID actorUserId, UUID categoryTypeId)`.
  - Records: `CreateCategoryRequest(String name, String code, String description)`, `UpdateCategoryRequest(String name, String description)`, `CreateCategoryTypeRequest(String name, String code, String description)`, `UpdateCategoryTypeRequest(String name, String description)`.
  - Endpoints `/api/admin/categories`, `/api/admin/categories/{id}`, `/api/admin/categories/{id}/types`, `/api/admin/category-types/{id}`.

**Two facts about the existing schema that shape this task:**

1. `categories.code` has `uq_categories_code` from `V1__init.sql`; **`category_types.code` had nothing** until Task 1 added `uq_category_types_category_code` on `(category_id, code)`. The service still runs an `exists` query first, because that is what produces a readable 409 naming the duplicate code — but the constraint is the actual guarantee, and it is what stops two simultaneous creates from both passing the check. The scope is `(category_id, code)`, not `code` alone: two categories may each have a `running-shoes` type.
2. Categories get no `archived_at`. A category still referenced by a product cannot be deleted, and the service says so with a 409 instead of letting `products.category_id` raise a constraint violation that would surface as a 500.
3. A unique constraint that fires anyway — a genuine race, or a code path that forgot its `exists` check — arrives as Spring's `DataIntegrityViolationException`, which the current `GlobalExceptionHandler` catches only in its `Exception` fallback and turns into a 500. Step 3 maps it to 409, so the race the constraint now closes reports as a conflict rather than as a server fault.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/mvp/ecommercebackend/admin/AdminCategoryIT.java`:

```java
package com.mvp.ecommercebackend.admin;

import com.mvp.ecommercebackend.auth.entity.User;
import com.mvp.ecommercebackend.catalog.entity.CategoryType;
import com.mvp.ecommercebackend.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminCategoryIT extends AbstractIntegrationTest {

    private String admin;

    @BeforeEach
    void authenticate() {
        User user = testData.createAdmin("cat-admin@example.com", "correct-horse-battery");
        admin = bearer(user);
    }

    @Test
    void createsACategoryThatAppearsInThePublicNavigation() throws Exception {
        mockMvc.perform(post("/api/admin/categories")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Footwear","code":"footwear","description":"Shoes and boots"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Footwear"))
                .andExpect(jsonPath("$.code").value("footwear"))
                .andExpect(jsonPath("$.types").isEmpty());

        mockMvc.perform(get("/api/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("footwear"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT action FROM admin_events", String.class)).isEqualTo("CATEGORY_CREATED");
    }

    @Test
    void rejectsADuplicateCategoryCode() throws Exception {
        catalogData.createCategoryWithType("Footwear", "Running Shoes");

        mockMvc.perform(post("/api/admin/categories")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Other Footwear","code":"footwear"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Conflict"));

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM categories", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void rejectsABlankName() throws Exception {
        mockMvc.perform(post("/api/admin/categories")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"  ","code":"footwear"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }

    @Test
    void patchesOnlyTheFieldsSupplied() throws Exception {
        CategoryType type = catalogData.createCategoryWithType("Footwear", "Running Shoes");

        mockMvc.perform(patch("/api/admin/categories/" + type.getCategory().getId())
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"description":"Everything you wear on your feet"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Footwear"))
                .andExpect(jsonPath("$.description").value("Everything you wear on your feet"))
                .andExpect(jsonPath("$.types[0].name").value("Running Shoes"));
    }

    @Test
    void refusesToDeleteACategoryAProductStillUses() throws Exception {
        CategoryType type = catalogData.createCategoryWithType("Footwear", "Running Shoes");
        catalogData.createProduct(type, "Trail Runner", "129.99");

        mockMvc.perform(delete("/api/admin/categories/" + type.getCategory().getId())
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("product")));

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM categories", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void deletesAnUnusedCategoryAndItsTypes() throws Exception {
        CategoryType type = catalogData.createCategoryWithType("Footwear", "Running Shoes");

        mockMvc.perform(delete("/api/admin/categories/" + type.getCategory().getId())
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isNoContent());

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM categories", Integer.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM category_types", Integer.class))
                .isZero();
    }

    @Test
    void addsPatchesAndDeletesACategoryType() throws Exception {
        CategoryType existing = catalogData.createCategoryWithType("Footwear", "Running Shoes");

        String created = mockMvc.perform(
                        post("/api/admin/categories/" + existing.getCategory().getId() + "/types")
                                .header(HttpHeaders.AUTHORIZATION, admin)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"name":"Hiking Boots","code":"hiking-boots"}
                                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Hiking Boots"))
                .andReturn().getResponse().getContentAsString();
        String typeId = objectMapper.readTree(created).get("id").asString();

        mockMvc.perform(patch("/api/admin/category-types/" + typeId)
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Walking Boots"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Walking Boots"))
                .andExpect(jsonPath("$.code").value("hiking-boots"));

        mockMvc.perform(delete("/api/admin/category-types/" + typeId)
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isNoContent());

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM category_types", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void rejectsADuplicateTypeCodeWithinTheSameCategory() throws Exception {
        CategoryType existing = catalogData.createCategoryWithType("Footwear", "Running Shoes");

        mockMvc.perform(post("/api/admin/categories/" + existing.getCategory().getId() + "/types")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Running Shoes Again","code":"running-shoes"}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void refusesToDeleteATypeAProductStillUses() throws Exception {
        CategoryType type = catalogData.createCategoryWithType("Footwear", "Running Shoes");
        catalogData.createProduct(type, "Trail Runner", "129.99");

        mockMvc.perform(delete("/api/admin/category-types/" + type.getId())
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isConflict());
    }

    @Test
    void answers404ForAnUnknownCategory() throws Exception {
        mockMvc.perform(delete("/api/admin/categories/" + java.util.UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn verify -Dit.test=AdminCategoryIT`
Expected: FAIL — every request answers 403 (no controller matches, and `/api/admin/**` denies the unmatched path) or the run fails at compilation once the DTO imports are added. Either way, nothing passes.

- [ ] **Step 3: Add the exception, its handler, and the two repository queries**

`common/ResourceInUseException.java`:

```java
package com.mvp.ecommercebackend.common;

/**
 * Something else still references this row, so it cannot be removed. Mapped to 409.
 *
 * <p>Distinct from {@link DuplicateResourceException}: "already exists" and "still in use" are
 * opposite problems, and sharing one exception would make both call sites read wrongly.
 */
public class ResourceInUseException extends RuntimeException {

    public ResourceInUseException(String message) {
        super(message);
    }
}
```

In `GlobalExceptionHandler`, add after `handleDuplicate`:

```java
    /** Conflict, not 400: the request is valid and the row exists — something else depends on it. */
    @ExceptionHandler(ResourceInUseException.class)
    ProblemDetail handleResourceInUse(ResourceInUseException exception, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "Conflict", exception.getMessage(), request);
    }

    /**
     * A database constraint that the service-level check did not catch first — a genuine race between
     * two concurrent writes, or a write path that forgot to check.
     *
     * <p>409 rather than the {@code Exception} fallback's 500: the server is working correctly and
     * the caller can succeed by retrying or by choosing a different value. The message is deliberately
     * generic — a raw Postgres constraint violation names tables, columns, and index names, which is
     * internal detail the caller has no use for. The full exception is still logged by the fallback
     * path's logger, so nothing is lost for debugging.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException exception,
                                               HttpServletRequest request) {
        log.warn("Constraint violation on {}: {}", request.getRequestURI(),
                exception.getMostSpecificCause().getMessage());
        return problem(HttpStatus.CONFLICT, "Conflict",
                "The request conflicts with existing data.", request);
    }
```

`DataIntegrityViolationException` is `org.springframework.dao.DataIntegrityViolationException`. Match the
logger to whatever `GlobalExceptionHandler` already declares for its `Exception` fallback — if the class
has no logger field, use the same declaration style the rest of the codebase uses rather than inventing
one. If the fallback handler logs at `error`, keep this one at `warn`: a losing race is expected
behaviour, not a fault, and paging on it would be noise.

In `ProductRepository`, add beside `findAllByCategoryId`:

```java
    /** Whether any product still points at this category. Guards the admin delete. */
    boolean existsByCategoryId(UUID categoryId);

    boolean existsByCategoryTypeId(UUID categoryTypeId);
```

Create `catalog/repository/CategoryTypeRepository.java`:

```java
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
```

In `CategoryService`, change the mapper's visibility so the admin package can reuse it rather than
duplicating it (the method body is unchanged):

```java
    /** Shared with the admin package, which returns the same shape after a write. */
    public static CategoryResponse toResponse(Category category) {
```

- [ ] **Step 4: Write the four request records**

`admin/dto/CreateCategoryRequest.java`:

```java
package com.mvp.ecommercebackend.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * @param code the stable public identifier. Lower-case, hyphenated and immutable once created:
 *             clients and URLs quote it, so a rename would break them.
 */
public record CreateCategoryRequest(
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Size(max = 100)
        @Pattern(regexp = "[a-z0-9]+(-[a-z0-9]+)*",
                message = "must be lower-case letters, digits and single hyphens") String code,
        @Size(max = 2000) String description) {
}
```

`admin/dto/UpdateCategoryRequest.java`:

```java
package com.mvp.ecommercebackend.admin.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A partial update: a null field is left alone, so a client can change the description without
 * resending the name.
 *
 * <p>{@code code} is absent deliberately — see {@link CreateCategoryRequest}. The {@code @Pattern}
 * rejects a supplied-but-blank name; Bean Validation skips {@code @Pattern} for null.
 */
public record UpdateCategoryRequest(
        @Size(max = 255) @Pattern(regexp = ".*\\S.*", message = "must not be blank") String name,
        @Size(max = 2000) String description) {
}
```

`admin/dto/CreateCategoryTypeRequest.java`:

```java
package com.mvp.ecommercebackend.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** The parent category comes from the path, not the body. */
public record CreateCategoryTypeRequest(
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Size(max = 100)
        @Pattern(regexp = "[a-z0-9]+(-[a-z0-9]+)*",
                message = "must be lower-case letters, digits and single hyphens") String code,
        @Size(max = 2000) String description) {
}
```

`admin/dto/UpdateCategoryTypeRequest.java`:

```java
package com.mvp.ecommercebackend.admin.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Partial, like {@link UpdateCategoryRequest}. A type cannot be moved to another category. */
public record UpdateCategoryTypeRequest(
        @Size(max = 255) @Pattern(regexp = ".*\\S.*", message = "must not be blank") String name,
        @Size(max = 2000) String description) {
}
```

- [ ] **Step 5: Write the service**

`admin/AdminCategoryService.java`:

```java
package com.mvp.ecommercebackend.admin;

import com.mvp.ecommercebackend.admin.dto.CreateCategoryRequest;
import com.mvp.ecommercebackend.admin.dto.CreateCategoryTypeRequest;
import com.mvp.ecommercebackend.admin.dto.UpdateCategoryRequest;
import com.mvp.ecommercebackend.admin.dto.UpdateCategoryTypeRequest;
import com.mvp.ecommercebackend.admin.entity.AdminEventType;
import com.mvp.ecommercebackend.admin.entity.AdminTargetType;
import com.mvp.ecommercebackend.catalog.CategoryService;
import com.mvp.ecommercebackend.catalog.dto.CategoryResponse;
import com.mvp.ecommercebackend.catalog.dto.CategoryTypeResponse;
import com.mvp.ecommercebackend.catalog.entity.Category;
import com.mvp.ecommercebackend.catalog.entity.CategoryType;
import com.mvp.ecommercebackend.catalog.repository.CategoryRepository;
import com.mvp.ecommercebackend.catalog.repository.CategoryTypeRepository;
import com.mvp.ecommercebackend.catalog.repository.ProductRepository;
import com.mvp.ecommercebackend.common.DuplicateResourceException;
import com.mvp.ecommercebackend.common.ResourceInUseException;
import com.mvp.ecommercebackend.common.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Writes to the category tree.
 *
 * <p>Separate from {@link CategoryService}, which stays {@code readOnly}: keeping the public
 * navigation query in a service that cannot write is a guarantee worth having, and it is lost the
 * moment a save lands in the same class.
 */
@Service
public class AdminCategoryService {

    private final CategoryRepository categoryRepository;
    private final CategoryTypeRepository categoryTypeRepository;
    private final ProductRepository productRepository;
    private final AdminEventService adminEventService;

    public AdminCategoryService(CategoryRepository categoryRepository,
                                CategoryTypeRepository categoryTypeRepository,
                                ProductRepository productRepository,
                                AdminEventService adminEventService) {
        this.categoryRepository = categoryRepository;
        this.categoryTypeRepository = categoryTypeRepository;
        this.productRepository = productRepository;
        this.adminEventService = adminEventService;
    }

    @Transactional
    public CategoryResponse createCategory(UUID actorUserId, CreateCategoryRequest request) {
        // Checked here rather than caught from uq_categories_code: a constraint violation arrives as
        // an opaque DataIntegrityViolationException, which the handler can only turn into a 500.
        if (categoryRepository.existsByCode(request.code())) {
            throw new DuplicateResourceException(
                    "A category with code " + request.code() + " already exists");
        }

        Category category = new Category();
        category.setName(request.name().trim());
        category.setCode(request.code());
        category.setDescription(request.description());
        Category saved = categoryRepository.saveAndFlush(category);

        adminEventService.record(actorUserId, AdminEventType.CATEGORY_CREATED,
                AdminTargetType.CATEGORY, saved.getId(), "code=" + saved.getCode());
        return CategoryService.toResponse(saved);
    }

    @Transactional
    public CategoryResponse updateCategory(UUID actorUserId, UUID categoryId,
                                           UpdateCategoryRequest request) {
        Category category = requireCategory(categoryId);
        if (request.name() != null) {
            category.setName(request.name().trim());
        }
        if (request.description() != null) {
            category.setDescription(request.description());
        }

        adminEventService.record(actorUserId, AdminEventType.CATEGORY_UPDATED,
                AdminTargetType.CATEGORY, categoryId, null);
        return CategoryService.toResponse(category);
    }

    /**
     * Removes a category and, by cascade, its types.
     *
     * <p>Categories are not archived: nothing references a category except products, and a product
     * must always name a live one. Refusing the delete while products remain is the whole guard.
     */
    @Transactional
    public void deleteCategory(UUID actorUserId, UUID categoryId) {
        Category category = requireCategory(categoryId);
        if (productRepository.existsByCategoryId(categoryId)) {
            throw new ResourceInUseException(
                    "Category " + categoryId + " still has product(s) and cannot be deleted");
        }

        adminEventService.record(actorUserId, AdminEventType.CATEGORY_DELETED,
                AdminTargetType.CATEGORY, categoryId, "code=" + category.getCode());
        // Cascade ALL on Category.categoryTypes removes the types in the same flush.
        categoryRepository.delete(category);
    }

    @Transactional
    public CategoryTypeResponse createCategoryType(UUID actorUserId, UUID categoryId,
                                                   CreateCategoryTypeRequest request) {
        Category category = requireCategory(categoryId);
        if (categoryTypeRepository.existsByCategoryIdAndCode(categoryId, request.code())) {
            throw new DuplicateResourceException("Category " + categoryId
                    + " already has a type with code " + request.code());
        }

        CategoryType type = new CategoryType();
        type.setName(request.name().trim());
        type.setCode(request.code());
        type.setDescription(request.description());
        type.setCategory(category);
        CategoryType saved = categoryTypeRepository.saveAndFlush(type);

        adminEventService.record(actorUserId, AdminEventType.CATEGORY_TYPE_CREATED,
                AdminTargetType.CATEGORY_TYPE, saved.getId(), "code=" + saved.getCode());
        return toResponse(saved);
    }

    @Transactional
    public CategoryTypeResponse updateCategoryType(UUID actorUserId, UUID categoryTypeId,
                                                   UpdateCategoryTypeRequest request) {
        CategoryType type = requireCategoryType(categoryTypeId);
        if (request.name() != null) {
            type.setName(request.name().trim());
        }
        if (request.description() != null) {
            type.setDescription(request.description());
        }

        adminEventService.record(actorUserId, AdminEventType.CATEGORY_TYPE_UPDATED,
                AdminTargetType.CATEGORY_TYPE, categoryTypeId, null);
        return toResponse(type);
    }

    @Transactional
    public void deleteCategoryType(UUID actorUserId, UUID categoryTypeId) {
        CategoryType type = requireCategoryType(categoryTypeId);
        if (productRepository.existsByCategoryTypeId(categoryTypeId)) {
            throw new ResourceInUseException(
                    "Category type " + categoryTypeId + " still has product(s) and cannot be deleted");
        }

        adminEventService.record(actorUserId, AdminEventType.CATEGORY_TYPE_DELETED,
                AdminTargetType.CATEGORY_TYPE, categoryTypeId, "code=" + type.getCode());
        categoryTypeRepository.delete(type);
    }

    private Category requireCategory(UUID categoryId) {
        return categoryRepository.findById(categoryId).orElseThrow(
                () -> new ResourceNotFoundException("Category " + categoryId + " was not found"));
    }

    private CategoryType requireCategoryType(UUID categoryTypeId) {
        return categoryTypeRepository.findById(categoryTypeId).orElseThrow(
                () -> new ResourceNotFoundException(
                        "Category type " + categoryTypeId + " was not found"));
    }

    static CategoryTypeResponse toResponse(CategoryType type) {
        return new CategoryTypeResponse(type.getId(), type.getCode(), type.getName(),
                type.getDescription());
    }
}
```

Add the code-uniqueness query to `CategoryRepository`:

```java
    /** Backs the admin duplicate check, which reports 409 rather than a constraint violation. */
    boolean existsByCode(String code);
```

- [ ] **Step 6: Write the controller**

`admin/AdminCategoryController.java`:

```java
package com.mvp.ecommercebackend.admin;

import com.mvp.ecommercebackend.admin.dto.CreateCategoryRequest;
import com.mvp.ecommercebackend.admin.dto.CreateCategoryTypeRequest;
import com.mvp.ecommercebackend.admin.dto.UpdateCategoryRequest;
import com.mvp.ecommercebackend.admin.dto.UpdateCategoryTypeRequest;
import com.mvp.ecommercebackend.auth.AuthenticatedUser;
import com.mvp.ecommercebackend.catalog.dto.CategoryResponse;
import com.mvp.ecommercebackend.catalog.dto.CategoryTypeResponse;
import com.mvp.ecommercebackend.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Category administration.
 *
 * <p>No {@code @PreAuthorize} here or on any admin controller: {@code SecurityConfig} already gates
 * {@code /api/admin/**} with {@code hasRole("ADMIN")}, and a second overlapping check is one more
 * thing that can drift out of step with the first.
 *
 * <p>Reads stay on the public {@code GET /api/categories}: the navigation tree is the same tree an
 * administrator edits, and duplicating it would let the two answers disagree.
 */
@RestController
@RequestMapping("/api/admin")
@Tag(name = "Admin Catalog")
@SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME)
public class AdminCategoryController {

    private final AdminCategoryService adminCategoryService;

    public AdminCategoryController(AdminCategoryService adminCategoryService) {
        this.adminCategoryService = adminCategoryService;
    }

    @PostMapping("/categories")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a category",
            description = "Answers 409 when the code is already taken.")
    public CategoryResponse createCategory(@AuthenticationPrincipal AuthenticatedUser principal,
                                           @Valid @RequestBody CreateCategoryRequest request) {
        return adminCategoryService.createCategory(principal.id(), request);
    }

    @PatchMapping("/categories/{id}")
    @Operation(summary = "Update a category",
            description = "Partial: an omitted field is left unchanged. The code is immutable.")
    public CategoryResponse updateCategory(@AuthenticationPrincipal AuthenticatedUser principal,
                                           @PathVariable UUID id,
                                           @Valid @RequestBody UpdateCategoryRequest request) {
        return adminCategoryService.updateCategory(principal.id(), id, request);
    }

    @DeleteMapping("/categories/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a category and its types",
            description = "Answers 409 while any product still references it.")
    public void deleteCategory(@AuthenticationPrincipal AuthenticatedUser principal,
                               @PathVariable UUID id) {
        adminCategoryService.deleteCategory(principal.id(), id);
    }

    @PostMapping("/categories/{id}/types")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a type to a category",
            description = "The code must be unique within the category.")
    public CategoryTypeResponse createCategoryType(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID id,
            @Valid @RequestBody CreateCategoryTypeRequest request) {
        return adminCategoryService.createCategoryType(principal.id(), id, request);
    }

    @PatchMapping("/category-types/{id}")
    @Operation(summary = "Update a category type",
            description = "Partial. A type cannot be moved to another category.")
    public CategoryTypeResponse updateCategoryType(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateCategoryTypeRequest request) {
        return adminCategoryService.updateCategoryType(principal.id(), id, request);
    }

    @DeleteMapping("/category-types/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a category type",
            description = "Answers 409 while any product still references it.")
    public void deleteCategoryType(@AuthenticationPrincipal AuthenticatedUser principal,
                                   @PathVariable UUID id) {
        adminCategoryService.deleteCategoryType(principal.id(), id);
    }
}
```

- [ ] **Step 7: Register the new paths in `OpenApiIT`**

Add these four entries to the end of `EVERY_PATH` in `OpenApiIT`:

```java
            "/api/me/orders/{orderId}/cancel",
            "/api/admin/categories",
            "/api/admin/categories/{id}",
            "/api/admin/categories/{id}/types",
            "/api/admin/category-types/{id}");
```

- [ ] **Step 8: Run the tests to verify they pass**

Run: `mvn verify -Dit.test='AdminCategoryIT,OpenApiIT'`
Expected: PASS, all ten `AdminCategoryIT` tests and every `OpenApiIT` test.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/mvp/ecommercebackend/admin \
        src/main/java/com/mvp/ecommercebackend/catalog \
        src/main/java/com/mvp/ecommercebackend/common \
        src/test/java/com/mvp/ecommercebackend/admin/AdminCategoryIT.java \
        src/test/java/com/mvp/ecommercebackend/config/OpenApiIT.java
git commit -m "feat(admin): category and category-type administration"
```

---

### Task 4: Product administration, archive, and restore

**Files:**
- Create: `src/main/java/com/mvp/ecommercebackend/admin/dto/CreateProductRequest.java`
- Create: `src/main/java/com/mvp/ecommercebackend/admin/dto/UpdateProductRequest.java`
- Create: `src/main/java/com/mvp/ecommercebackend/admin/dto/AdminVariantResponse.java`
- Create: `src/main/java/com/mvp/ecommercebackend/admin/dto/AdminProductResponse.java`
- Create: `src/main/java/com/mvp/ecommercebackend/admin/dto/AdminProductSummaryResponse.java`
- Create: `src/main/java/com/mvp/ecommercebackend/admin/AdminProductSpecifications.java`
- Create: `src/main/java/com/mvp/ecommercebackend/admin/AdminProductService.java`
- Create: `src/main/java/com/mvp/ecommercebackend/admin/AdminProductController.java`
- Modify: `src/main/java/com/mvp/ecommercebackend/catalog/repository/ProductRepository.java` (stock projection)
- Modify: `src/test/java/com/mvp/ecommercebackend/config/OpenApiIT.java`
- Test: `src/test/java/com/mvp/ecommercebackend/admin/AdminProductIT.java`

**Interfaces:**
- Consumes: `AdminEventService.record(...)`, `CategoryTypeRepository`, `CategoryRepository`, `ProductRepository`, `java.time.Clock` (the `ClockConfig` bean — inject it rather than calling `Instant.now()`, matching `OrderService`).
- Produces:
  - `AdminVariantResponse(UUID id, String color, String size, Integer stockQuantity, Instant archivedAt)` — reused by Task 5.
  - `AdminProductResponse(UUID id, String name, String description, BigDecimal price, UUID categoryId, String categoryName, UUID categoryTypeId, String categoryTypeName, Instant archivedAt, List<AdminVariantResponse> variants, List<ProductResourceDto> resources)` — reused by Tasks 5 and 6.
  - `AdminProductSummaryResponse(UUID id, String name, BigDecimal price, String thumbnail, UUID categoryId, String categoryName, UUID categoryTypeId, String categoryTypeName, long variantCount, long totalStock, Instant archivedAt)`.
  - `CreateProductRequest(String name, String description, BigDecimal price, UUID categoryId, UUID categoryTypeId)`, `UpdateProductRequest(String name, String description, BigDecimal price, UUID categoryId, UUID categoryTypeId)`.
  - `AdminProductService.requireProduct(UUID productId)` — package-private `Product`, reused by Tasks 5 and 6.
  - `AdminProductService.toResponse(Product product)` — package-private `static AdminProductResponse`, reused by Tasks 5 and 6.
  - `ProductRepository.findStockSummaries(Collection<UUID>)` returning `List<ProductStockSummary>` with `getProductId()`, `getVariantCount()`, `getTotalStock()`.
  - Endpoints `/api/admin/products`, `/api/admin/products/{id}`, `/api/admin/products/{id}/restore`.

**Decisions this task locks in:**

- `DELETE` and `restore` are **idempotent**: archiving an already-archived product succeeds with 204 and changes nothing, restoring a live product succeeds and changes nothing. HTTP defines `DELETE` as idempotent, and inventing a 409 for "already archived" would make a retried request look like a failure.
- `GET /api/admin/products/{id}` returns an archived product **normally, not 404**. An administrator has to be able to inspect what they retired in order to decide whether to restore it. Archived variants are included, each carrying its own `archivedAt`.
- `variantCount` and `totalStock` on a summary row count **unarchived variants only** — they answer "what can I sell", and are fetched with one grouped query per page rather than by walking each product's collection (which would be N+1).
- The catalogue's `ProductSpecifications` is package-private in `catalog`, so `admin` gets its own. The wildcard-escaping logic in `nameContains` is deliberately duplicated rather than the existing class widened: making it public would put an internal query helper on the `catalog` package's API for one caller.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/mvp/ecommercebackend/admin/AdminProductIT.java`:

```java
package com.mvp.ecommercebackend.admin;

import com.mvp.ecommercebackend.auth.entity.User;
import com.mvp.ecommercebackend.catalog.entity.CategoryType;
import com.mvp.ecommercebackend.catalog.entity.Product;
import com.mvp.ecommercebackend.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminProductIT extends AbstractIntegrationTest {

    private String admin;
    private CategoryType type;

    @BeforeEach
    void setUp() {
        User user = testData.createAdmin("product-admin@example.com", "correct-horse-battery");
        admin = bearer(user);
        type = catalogData.createCategoryWithType("Footwear", "Running Shoes");
    }

    @Test
    void createsAProductAndReturnsItInTheAdminDetailView() throws Exception {
        String body = mockMvc.perform(post("/api/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Trail Runner","description":"For rough ground",
                                 "price":"129.99","categoryId":"%s","categoryTypeId":"%s"}
                                """.formatted(type.getCategory().getId(), type.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Trail Runner"))
                .andExpect(jsonPath("$.price").value(129.99))
                .andExpect(jsonPath("$.categoryName").value("Footwear"))
                .andExpect(jsonPath("$.categoryTypeName").value("Running Shoes"))
                .andExpect(jsonPath("$.archivedAt").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String productId = objectMapper.readTree(body).get("id").asString();

        mockMvc.perform(get("/api/admin/products/" + productId)
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Trail Runner"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT action FROM admin_events", String.class)).isEqualTo("PRODUCT_CREATED");
    }

    @Test
    void rejectsATypeThatBelongsToAnotherCategory() throws Exception {
        CategoryType other = catalogData.createCategoryWithType("Outerwear", "Rain Jackets");

        mockMvc.perform(post("/api/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Trail Runner","price":"129.99",
                                 "categoryId":"%s","categoryTypeId":"%s"}
                                """.formatted(type.getCategory().getId(), other.getId())))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsANegativePriceAndTooManyDecimals() throws Exception {
        mockMvc.perform(post("/api/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Trail Runner","price":"-1.00",
                                 "categoryId":"%s","categoryTypeId":"%s"}
                                """.formatted(type.getCategory().getId(), type.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("price"));

        mockMvc.perform(post("/api/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Trail Runner","price":"1.001",
                                 "categoryId":"%s","categoryTypeId":"%s"}
                                """.formatted(type.getCategory().getId(), type.getId())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void patchesOnlyTheFieldsSupplied() throws Exception {
        Product product = catalogData.createProduct(type, "Trail Runner", "129.99");

        mockMvc.perform(patch("/api/admin/products/" + product.getId())
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"price":"99.00"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Trail Runner"))
                .andExpect(jsonPath("$.price").value(99.00));
    }

    @Test
    void listsUnarchivedProductsByDefaultWithStockTotals() throws Exception {
        Product product = catalogData.createProduct(type, "Trail Runner", "129.99");
        catalogData.addVariant(product, "Black", "42", 3);
        catalogData.addVariant(product, "Black", "43", 4);
        Product retired = catalogData.createProduct(type, "Old Runner", "59.99");
        archive(retired);

        mockMvc.perform(get("/api/admin/products").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Trail Runner"))
                .andExpect(jsonPath("$.content[0].variantCount").value(2))
                .andExpect(jsonPath("$.content[0].totalStock").value(7));
    }

    @Test
    void selectsOnlyArchivedOrAllProducts() throws Exception {
        catalogData.createProduct(type, "Trail Runner", "129.99");
        Product retired = catalogData.createProduct(type, "Old Runner", "59.99");
        archive(retired);

        mockMvc.perform(get("/api/admin/products?archived=only")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Old Runner"))
                .andExpect(jsonPath("$.content[0].archivedAt").isNotEmpty());

        mockMvc.perform(get("/api/admin/products?archived=all")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void filtersByCategoryAndSearchTerm() throws Exception {
        catalogData.createProduct(type, "Trail Runner", "129.99");
        catalogData.createProduct(type, "Road Runner", "89.99");

        mockMvc.perform(get("/api/admin/products?q=trail&categoryId=" + type.getCategory().getId())
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Trail Runner"));
    }

    @Test
    void rejectsAnUnknownSortProperty() throws Exception {
        mockMvc.perform(get("/api/admin/products?sort=stockQuantity")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isBadRequest());
    }

    @Test
    void archivesAndRestoresIdempotently() throws Exception {
        Product product = catalogData.createProduct(type, "Trail Runner", "129.99");

        mockMvc.perform(delete("/api/admin/products/" + product.getId())
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isNoContent());
        // Repeating an idempotent DELETE is not an error.
        mockMvc.perform(delete("/api/admin/products/" + product.getId())
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isNoContent());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM products WHERE archived_at IS NOT NULL", Integer.class))
                .isEqualTo(1);

        // The archived product is still visible to an administrator, unlike on the public endpoint.
        mockMvc.perform(get("/api/admin/products/" + product.getId())
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archivedAt").isNotEmpty());

        mockMvc.perform(post("/api/admin/products/" + product.getId() + "/restore")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archivedAt").doesNotExist());
    }

    @Test
    void answers404ForAnUnknownProduct() throws Exception {
        mockMvc.perform(get("/api/admin/products/" + UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isNotFound());
    }

    /** Archives through the API, so the test never writes a column the service owns. */
    private void archive(Product product) throws Exception {
        mockMvc.perform(delete("/api/admin/products/" + product.getId())
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isNoContent());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn verify -Dit.test=AdminProductIT`
Expected: FAIL — no `/api/admin/products` handler exists, so every request answers 403.

- [ ] **Step 3: Write the DTOs**

`admin/dto/CreateProductRequest.java`:

```java
package com.mvp.ecommercebackend.admin.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * @param price bounded to match {@code products.price numeric(12,2)}: ten integer digits and two
 *              decimals. Without {@code @Digits} an over-precise value would reach Postgres and
 *              either round silently or fail the insert as a 500.
 */
public record CreateProductRequest(
        @NotBlank @Size(max = 255) String name,
        @Size(max = 2000) String description,
        @NotNull @DecimalMin(value = "0.00", message = "must not be negative")
        @Digits(integer = 10, fraction = 2) BigDecimal price,
        @NotNull UUID categoryId,
        @NotNull UUID categoryTypeId) {
}
```

`admin/dto/UpdateProductRequest.java`:

```java
package com.mvp.ecommercebackend.admin.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Partial: a null field is left unchanged.
 *
 * <p>{@code categoryId} and {@code categoryTypeId} must be supplied together or not at all — a type
 * always belongs to exactly one category, so moving one without the other would leave the product
 * describing a pairing that does not exist.
 */
public record UpdateProductRequest(
        @Size(max = 255) @Pattern(regexp = ".*\\S.*", message = "must not be blank") String name,
        @Size(max = 2000) String description,
        @DecimalMin(value = "0.00", message = "must not be negative")
        @Digits(integer = 10, fraction = 2) BigDecimal price,
        UUID categoryId,
        UUID categoryTypeId) {
}
```

`admin/dto/AdminVariantResponse.java`:

```java
package com.mvp.ecommercebackend.admin.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * A variant as an administrator sees it.
 *
 * <p>Wider than the public {@code ProductVariantDto}: it carries {@code archivedAt}, because an
 * administrator needs to see what has been retired in order to restore it.
 */
public record AdminVariantResponse(
        UUID id,
        String color,
        String size,
        Integer stockQuantity,
        Instant archivedAt) {
}
```

`admin/dto/AdminProductResponse.java`:

```java
package com.mvp.ecommercebackend.admin.dto;

import com.mvp.ecommercebackend.catalog.dto.ProductResourceDto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The administrative detail view.
 *
 * <p>Distinct from {@code ProductDto} rather than an extension of it: this one includes archived
 * variants and {@code archivedAt}, and the public shape must not start leaking either.
 */
public record AdminProductResponse(
        UUID id,
        String name,
        String description,
        BigDecimal price,
        UUID categoryId,
        String categoryName,
        UUID categoryTypeId,
        String categoryTypeName,
        Instant archivedAt,
        List<AdminVariantResponse> variants,
        List<ProductResourceDto> resources) {
}
```

`admin/dto/AdminProductSummaryResponse.java`:

```java
package com.mvp.ecommercebackend.admin.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One row of the administrative product listing.
 *
 * @param variantCount unarchived variants only
 * @param totalStock   summed over unarchived variants only, so it answers "what can I sell"
 */
public record AdminProductSummaryResponse(
        UUID id,
        String name,
        BigDecimal price,
        String thumbnail,
        UUID categoryId,
        String categoryName,
        UUID categoryTypeId,
        String categoryTypeName,
        long variantCount,
        long totalStock,
        Instant archivedAt) {
}
```

- [ ] **Step 4: Write the specifications and the repository projection**

`admin/AdminProductSpecifications.java`:

```java
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
```

In `ProductRepository`, add beside `findPrimaryThumbnails`:

```java
    /**
     * Variant count and total stock for each of the given products, in one query.
     *
     * <p>Archived variants are excluded: the numbers answer "what can I sell". Reading
     * {@code product.getProductVariants()} per row instead would be N+1 and would pull every variant
     * of every product to add two integers.
     */
    @Query("""
            select variant.product.id as productId,
                   count(variant) as variantCount,
                   sum(variant.stockQuantity) as totalStock
            from ProductVariant variant
            where variant.product.id in :productIds and variant.archivedAt is null
            group by variant.product.id
            """)
    List<ProductStockSummary> findStockSummaries(@Param("productIds") Collection<UUID> productIds);

    /** Projection for {@link #findStockSummaries}. */
    interface ProductStockSummary {

        UUID getProductId();

        long getVariantCount();

        long getTotalStock();
    }
```

- [ ] **Step 5: Write the service**

`admin/AdminProductService.java`:

```java
package com.mvp.ecommercebackend.admin;

import com.mvp.ecommercebackend.admin.dto.AdminProductResponse;
import com.mvp.ecommercebackend.admin.dto.AdminProductSummaryResponse;
import com.mvp.ecommercebackend.admin.dto.AdminVariantResponse;
import com.mvp.ecommercebackend.admin.dto.CreateProductRequest;
import com.mvp.ecommercebackend.admin.dto.UpdateProductRequest;
import com.mvp.ecommercebackend.admin.entity.AdminEventType;
import com.mvp.ecommercebackend.admin.entity.AdminTargetType;
import com.mvp.ecommercebackend.catalog.dto.ProductResourceDto;
import com.mvp.ecommercebackend.catalog.entity.Category;
import com.mvp.ecommercebackend.catalog.entity.CategoryType;
import com.mvp.ecommercebackend.catalog.entity.Product;
import com.mvp.ecommercebackend.catalog.entity.ProductVariant;
import com.mvp.ecommercebackend.catalog.entity.Resource;
import com.mvp.ecommercebackend.catalog.repository.CategoryRepository;
import com.mvp.ecommercebackend.catalog.repository.CategoryTypeRepository;
import com.mvp.ecommercebackend.catalog.repository.ProductRepository;
import com.mvp.ecommercebackend.common.PageResponse;
import com.mvp.ecommercebackend.common.ResourceNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Catalogue writes for administrators.
 *
 * <p>Deliberately not part of {@code ProductService}: that class is entirely
 * {@code @Transactional(readOnly = true)}, and a public browsing path that provably cannot write is
 * worth more than the handful of lines this duplicates.
 */
@Service
public class AdminProductService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final CategoryTypeRepository categoryTypeRepository;
    private final AdminEventService adminEventService;
    private final Clock clock;

    public AdminProductService(ProductRepository productRepository,
                               CategoryRepository categoryRepository,
                               CategoryTypeRepository categoryTypeRepository,
                               AdminEventService adminEventService,
                               Clock clock) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.categoryTypeRepository = categoryTypeRepository;
        this.adminEventService = adminEventService;
        this.clock = clock;
    }

    /**
     * @param archived {@code exclude} (the default), {@code only}, or {@code all}; already
     *                 constrained to that set by the controller
     */
    @Transactional(readOnly = true)
    public PageResponse<AdminProductSummaryResponse> list(String archived, UUID categoryId,
                                                          String searchTerm, int page, int size,
                                                          String sortProperty, String direction) {
        PageRequest pageRequest = PageRequest.of(page, size,
                Sort.by(Sort.Direction.fromString(direction), sortProperty));
        Page<Product> products = productRepository.findAll(
                filters(archived, categoryId, searchTerm), pageRequest);

        Map<UUID, String> thumbnails = thumbnailsFor(products.getContent());
        Map<UUID, ProductRepository.ProductStockSummary> stock = stockFor(products.getContent());
        List<AdminProductSummaryResponse> rows = products.getContent().stream()
                .map(product -> toSummary(product, thumbnails.get(product.getId()),
                        stock.get(product.getId())))
                .toList();
        return PageResponse.of(products, rows);
    }

    /**
     * The administrative detail view.
     *
     * <p>An archived product answers normally rather than 404: an administrator must be able to look
     * at what they retired in order to decide whether to restore it. {@code @Transactional} is
     * load-bearing — this walks four lazy associations and {@code open-in-view} is off.
     */
    @Transactional(readOnly = true)
    public AdminProductResponse getProduct(UUID productId) {
        return toResponse(requireProduct(productId));
    }

    @Transactional
    public AdminProductResponse createProduct(UUID actorUserId, CreateProductRequest request) {
        Category category = requireCategory(request.categoryId());
        CategoryType type = requireTypeOfCategory(request.categoryTypeId(), request.categoryId());

        Product product = new Product();
        product.setName(request.name().trim());
        product.setDescription(request.description());
        product.setPrice(request.price());
        product.setCategory(category);
        product.setCategoryType(type);
        Product saved = productRepository.saveAndFlush(product);

        adminEventService.record(actorUserId, AdminEventType.PRODUCT_CREATED,
                AdminTargetType.PRODUCT, saved.getId(), "name=" + saved.getName());
        return toResponse(saved);
    }

    @Transactional
    public AdminProductResponse updateProduct(UUID actorUserId, UUID productId,
                                              UpdateProductRequest request) {
        Product product = requireProduct(productId);
        if (request.name() != null) {
            product.setName(request.name().trim());
        }
        if (request.description() != null) {
            product.setDescription(request.description());
        }
        if (request.price() != null) {
            product.setPrice(request.price());
        }
        // Both or neither: a type belongs to exactly one category, so moving one alone would leave
        // the product claiming a pairing that does not exist.
        if (request.categoryId() != null || request.categoryTypeId() != null) {
            UUID categoryId = request.categoryId() == null
                    ? product.getCategory().getId() : request.categoryId();
            UUID typeId = request.categoryTypeId() == null
                    ? product.getCategoryType().getId() : request.categoryTypeId();
            product.setCategory(requireCategory(categoryId));
            product.setCategoryType(requireTypeOfCategory(typeId, categoryId));
        }

        adminEventService.record(actorUserId, AdminEventType.PRODUCT_UPDATED,
                AdminTargetType.PRODUCT, productId, null);
        return toResponse(product);
    }

    /**
     * Retires a product without deleting it.
     *
     * <p>Idempotent: archiving an already-archived product changes nothing and still succeeds, which
     * is what {@code DELETE} promises. Variants keep their own flags, so restoring later does not
     * resurrect one that was retired separately.
     */
    @Transactional
    public void archiveProduct(UUID actorUserId, UUID productId) {
        Product product = requireProduct(productId);
        if (product.getArchivedAt() != null) {
            return;
        }
        product.setArchivedAt(clock.instant());
        adminEventService.record(actorUserId, AdminEventType.PRODUCT_ARCHIVED,
                AdminTargetType.PRODUCT, productId, "name=" + product.getName());
    }

    /** Idempotent in the same way: restoring a live product is a no-op. */
    @Transactional
    public AdminProductResponse restoreProduct(UUID actorUserId, UUID productId) {
        Product product = requireProduct(productId);
        if (product.getArchivedAt() != null) {
            product.setArchivedAt(null);
            adminEventService.record(actorUserId, AdminEventType.PRODUCT_RESTORED,
                    AdminTargetType.PRODUCT, productId, "name=" + product.getName());
        }
        return toResponse(product);
    }

    /** Shared with {@link AdminVariantService} and {@link AdminResourceService}. */
    Product requireProduct(UUID productId) {
        return productRepository.findWithCategoriesById(productId).orElseThrow(
                () -> new ResourceNotFoundException("Product " + productId + " was not found"));
    }

    private Category requireCategory(UUID categoryId) {
        return categoryRepository.findById(categoryId).orElseThrow(
                () -> new ResourceNotFoundException("Category " + categoryId + " was not found"));
    }

    /**
     * 404 rather than a validation error when the type belongs to a different category: the type does
     * not exist *within the category named by the request*, and the two ids are only meaningful
     * together.
     */
    private CategoryType requireTypeOfCategory(UUID categoryTypeId, UUID categoryId) {
        CategoryType type = categoryTypeRepository.findById(categoryTypeId).orElseThrow(
                () -> new ResourceNotFoundException(
                        "Category type " + categoryTypeId + " was not found"));
        if (!type.getCategory().getId().equals(categoryId)) {
            throw new ResourceNotFoundException("Category type " + categoryTypeId
                    + " does not belong to category " + categoryId);
        }
        return type;
    }

    private Specification<Product> filters(String archived, UUID categoryId, String searchTerm) {
        List<Specification<Product>> filters = new ArrayList<>();
        switch (archived.toLowerCase(Locale.ROOT)) {
            case "only" -> filters.add(AdminProductSpecifications.archivedOnly());
            case "all" -> { /* no predicate: archived and live rows both included */ }
            default -> filters.add(AdminProductSpecifications.notArchived());
        }
        if (categoryId != null) {
            filters.add(AdminProductSpecifications.inCategory(categoryId));
        }
        if (searchTerm != null && !searchTerm.isBlank()) {
            filters.add(AdminProductSpecifications.nameContains(searchTerm.trim()));
        }
        return filters.isEmpty() ? Specification.unrestricted() : Specification.allOf(filters);
    }

    private Map<UUID, String> thumbnailsFor(List<Product> products) {
        if (products.isEmpty()) {
            return Map.of();
        }
        List<UUID> productIds = products.stream().map(Product::getId).toList();
        return productRepository.findPrimaryThumbnails(productIds).stream()
                .collect(Collectors.toMap(ProductRepository.ProductThumbnail::getProductId,
                        ProductRepository.ProductThumbnail::getUrl,
                        (first, duplicate) -> first));
    }

    private Map<UUID, ProductRepository.ProductStockSummary> stockFor(List<Product> products) {
        if (products.isEmpty()) {
            return Map.of();
        }
        List<UUID> productIds = products.stream().map(Product::getId).toList();
        return productRepository.findStockSummaries(productIds).stream()
                .collect(Collectors.toMap(
                        ProductRepository.ProductStockSummary::getProductId, summary -> summary));
    }

    private static AdminProductSummaryResponse toSummary(
            Product product, String thumbnail, ProductRepository.ProductStockSummary stock) {
        return new AdminProductSummaryResponse(
                product.getId(),
                product.getName(),
                product.getPrice(),
                thumbnail,
                product.getCategory().getId(),
                product.getCategory().getName(),
                product.getCategoryType().getId(),
                product.getCategoryType().getName(),
                // Null when a product has no unarchived variant: the grouped query returns no row.
                stock == null ? 0L : stock.getVariantCount(),
                stock == null ? 0L : stock.getTotalStock(),
                product.getArchivedAt());
    }

    /** Shared with {@link AdminVariantService} and {@link AdminResourceService}. */
    static AdminProductResponse toResponse(Product product) {
        List<AdminVariantResponse> variants = product.getProductVariants().stream()
                .map(AdminProductService::toVariantResponse)
                .toList();
        List<ProductResourceDto> resources = product.getResources().stream()
                .map(AdminProductService::toResourceResponse)
                .toList();

        return new AdminProductResponse(product.getId(), product.getName(),
                product.getDescription(), product.getPrice(),
                product.getCategory().getId(), product.getCategory().getName(),
                product.getCategoryType().getId(), product.getCategoryType().getName(),
                product.getArchivedAt(), variants, resources);
    }

    static AdminVariantResponse toVariantResponse(ProductVariant variant) {
        return new AdminVariantResponse(variant.getId(), variant.getColor(), variant.getSize(),
                variant.getStockQuantity(), variant.getArchivedAt());
    }

    /** Kept beside the response mapping so the two shapes cannot drift apart. */
    static ProductResourceDto toResourceResponse(Resource resource) {
        return ProductResourceDto.builder()
                .id(resource.getId())
                .name(resource.getName())
                .url(resource.getUrl())
                .type(resource.getType())
                .isPrimary(resource.getIsPrimary())
                .build();
    }
}
```

- [ ] **Step 6: Write the controller**

`admin/AdminProductController.java`:

```java
package com.mvp.ecommercebackend.admin;

import com.mvp.ecommercebackend.admin.dto.AdminProductResponse;
import com.mvp.ecommercebackend.admin.dto.AdminProductSummaryResponse;
import com.mvp.ecommercebackend.admin.dto.CreateProductRequest;
import com.mvp.ecommercebackend.admin.dto.UpdateProductRequest;
import com.mvp.ecommercebackend.auth.AuthenticatedUser;
import com.mvp.ecommercebackend.common.PageResponse;
import com.mvp.ecommercebackend.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Product administration. Security comes from the {@code /api/admin/**} path rule. */
@RestController
@RequestMapping("/api/admin/products")
@Tag(name = "Admin Catalog")
@SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME)
public class AdminProductController {

    private final AdminProductService adminProductService;

    public AdminProductController(AdminProductService adminProductService) {
        this.adminProductService = adminProductService;
    }

    /**
     * @param archived {@code exclude}, {@code only} or {@code all}
     * @param sort     whitelisted, so a caller cannot sort by an arbitrary entity path
     */
    @GetMapping
    @Operation(summary = "List products for administration",
            description = "Excludes archived products unless archived=only or archived=all. "
                    + "Each row carries the unarchived variant count and total stock.")
    public PageResponse<AdminProductSummaryResponse> listProducts(
            @RequestParam(defaultValue = "exclude")
            @Pattern(regexp = "(?i)exclude|only|all",
                    message = "must be one of exclude, only, all") String archived,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(defaultValue = "0")
            @Min(value = 0, message = "must not be negative") int page,
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "must be at least 1")
            @Max(value = 100, message = "must not exceed 100") int size,
            @RequestParam(defaultValue = "name")
            @Pattern(regexp = "name|price|createdAt",
                    message = "must be one of name, price, createdAt") String sort,
            @RequestParam(defaultValue = "asc")
            @Pattern(regexp = "(?i)asc|desc", message = "must be asc or desc") String direction) {
        return adminProductService.list(archived, categoryId, q, page, size, sort, direction);
    }

    @GetMapping("/{id}")
    @Operation(summary = "One product, including archived rows",
            description = "Unlike the public endpoint, an archived product answers 200 rather than "
                    + "404, and archived variants are included.")
    public AdminProductResponse getProduct(@PathVariable UUID id) {
        return adminProductService.getProduct(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a product",
            description = "The category type must belong to the category, or the answer is 404.")
    public AdminProductResponse createProduct(@AuthenticationPrincipal AuthenticatedUser principal,
                                              @Valid @RequestBody CreateProductRequest request) {
        return adminProductService.createProduct(principal.id(), request);
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Update a product",
            description = "Partial: an omitted field is left unchanged.")
    public AdminProductResponse updateProduct(@AuthenticationPrincipal AuthenticatedUser principal,
                                              @PathVariable UUID id,
                                              @Valid @RequestBody UpdateProductRequest request) {
        return adminProductService.updateProduct(principal.id(), id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Archive a product",
            description = "Sets archivedAt rather than deleting the row, so order history and live "
                    + "carts survive. Idempotent.")
    public void archiveProduct(@AuthenticationPrincipal AuthenticatedUser principal,
                               @PathVariable UUID id) {
        adminProductService.archiveProduct(principal.id(), id);
    }

    @PostMapping("/{id}/restore")
    @Operation(summary = "Restore an archived product",
            description = "Does not resurrect variants archived separately. Idempotent.")
    public AdminProductResponse restoreProduct(@AuthenticationPrincipal AuthenticatedUser principal,
                                               @PathVariable UUID id) {
        return adminProductService.restoreProduct(principal.id(), id);
    }
}
```

- [ ] **Step 7: Register the new paths in `OpenApiIT`**

Add to `EVERY_PATH`:

```java
            "/api/admin/products",
            "/api/admin/products/{id}",
            "/api/admin/products/{id}/restore",
```

- [ ] **Step 8: Run the tests to verify they pass**

Run: `mvn verify -Dit.test='AdminProductIT,OpenApiIT'`
Expected: PASS, all ten `AdminProductIT` tests and every `OpenApiIT` test.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/mvp/ecommercebackend/admin \
        src/main/java/com/mvp/ecommercebackend/catalog/repository/ProductRepository.java \
        src/test/java/com/mvp/ecommercebackend/admin/AdminProductIT.java \
        src/test/java/com/mvp/ecommercebackend/config/OpenApiIT.java
git commit -m "feat(admin): product administration with archive and restore"
```

---

### Task 5: Variant administration, archive, and restore

**Files:**
- Create: `src/main/java/com/mvp/ecommercebackend/admin/dto/CreateVariantRequest.java`
- Create: `src/main/java/com/mvp/ecommercebackend/admin/dto/UpdateVariantRequest.java`
- Create: `src/main/java/com/mvp/ecommercebackend/admin/AdminVariantService.java`
- Create: `src/main/java/com/mvp/ecommercebackend/admin/AdminVariantController.java`
- Modify: `src/test/java/com/mvp/ecommercebackend/config/OpenApiIT.java`
- Test: `src/test/java/com/mvp/ecommercebackend/admin/AdminVariantIT.java`

**Interfaces:**
- Consumes: `AdminProductService.requireProduct(UUID)` and `AdminProductService.toVariantResponse(ProductVariant)` from Task 4 (both package-private, and `AdminVariantService` lives in the same package); `ProductVariantRepository`; `AdminEventService.record(...)`; `Clock`.
- Produces:
  - `CreateVariantRequest(String color, String size, Integer stockQuantity)`, `UpdateVariantRequest(String color, String size)`.
  - `AdminVariantService.requireVariant(UUID variantId)` — package-private `ProductVariant`, reused by Task 6.
  - Endpoints `/api/admin/products/{id}/variants`, `/api/admin/variants/{id}`, `/api/admin/variants/{id}/restore`.

**Decisions this task locks in:**

- `stockQuantity` is accepted on create as an **opening balance** and is not on `UpdateVariantRequest`. Every later change goes through Task 6's delta endpoint. If `PATCH` could set stock absolutely, an administrator reading 10, a customer buying one, and the administrator then writing 10 would resurrect a sold unit — the whole reason the spec chose a delta.
- A duplicate unarchived `color`/`size` pair on one product is rejected with `DuplicateResourceException` (409). The spec's error table names only category codes, so this is an addition: two identical "Black / 42" rows would show a customer the same option twice and split its stock across two rows. `V1__init.sql` had no constraint here; Task 1 added the partial unique index `uq_product_variants_live` on `(product_id, color, size) WHERE archived_at IS NULL`, so the service's `exists` check is the readable-error fast path and the index is the guarantee. A lost race arrives as `DataIntegrityViolationException`, which Task 3 mapped to 409 — so the caller sees a conflict either way.
- Because that index is **partial**, archiving a variant frees its colour and size for reuse: retiring "Black / 42" and creating a new "Black / 42" is legal, and the archived row keeps its own stock and history. This is the behaviour that a plain unique constraint would have made impossible, and Step 1 tests it.
- Adding a variant to an **archived** product is allowed. An administrator preparing a product for restore should not have to un-archive it first, and the variant stays invisible to customers anyway because an archived product hides regardless of its variants' flags.
- Archive and restore are idempotent, for the same reason as Task 4.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/mvp/ecommercebackend/admin/AdminVariantIT.java`:

```java
package com.mvp.ecommercebackend.admin;

import com.mvp.ecommercebackend.auth.entity.User;
import com.mvp.ecommercebackend.catalog.entity.CategoryType;
import com.mvp.ecommercebackend.catalog.entity.Product;
import com.mvp.ecommercebackend.catalog.entity.ProductVariant;
import com.mvp.ecommercebackend.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminVariantIT extends AbstractIntegrationTest {

    private String admin;
    private Product product;

    @BeforeEach
    void setUp() {
        User user = testData.createAdmin("variant-admin@example.com", "correct-horse-battery");
        admin = bearer(user);
        CategoryType type = catalogData.createCategoryWithType("Footwear", "Running Shoes");
        product = catalogData.createProduct(type, "Trail Runner", "129.99");
    }

    @Test
    void createsAVariantWithAnOpeningStockBalance() throws Exception {
        mockMvc.perform(post("/api/admin/products/" + product.getId() + "/variants")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"color":"Black","size":"42","stockQuantity":5}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.color").value("Black"))
                .andExpect(jsonPath("$.size").value("42"))
                .andExpect(jsonPath("$.stockQuantity").value(5))
                .andExpect(jsonPath("$.archivedAt").doesNotExist());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT action FROM admin_events", String.class)).isEqualTo("VARIANT_CREATED");
    }

    @Test
    void defaultsTheOpeningBalanceToZero() throws Exception {
        mockMvc.perform(post("/api/admin/products/" + product.getId() + "/variants")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"color":"Black","size":"42"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.stockQuantity").value(0));
    }

    @Test
    void rejectsANegativeOpeningBalance() throws Exception {
        mockMvc.perform(post("/api/admin/products/" + product.getId() + "/variants")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"color":"Black","size":"42","stockQuantity":-1}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("stockQuantity"));
    }

    @Test
    void rejectsADuplicateColourAndSizeOnTheSameProduct() throws Exception {
        catalogData.addVariant(product, "Black", "42", 5);

        mockMvc.perform(post("/api/admin/products/" + product.getId() + "/variants")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"color":"black","size":"42","stockQuantity":1}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    void allowsTheColourAndSizeOfAnArchivedVariantToBeReused() throws Exception {
        ProductVariant variant = catalogData.createVariant(product, "Black", "42", 5);
        mockMvc.perform(delete("/api/admin/variants/" + variant.getId())
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/admin/products/" + product.getId() + "/variants")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"color":"Black","size":"42","stockQuantity":2}
                                """))
                .andExpect(status().isCreated());
    }

    @Test
    void patchesColourAndSizeButNotStock() throws Exception {
        ProductVariant variant = catalogData.createVariant(product, "Black", "42", 5);

        mockMvc.perform(patch("/api/admin/variants/" + variant.getId())
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"color":"Charcoal"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.color").value("Charcoal"))
                .andExpect(jsonPath("$.size").value("42"))
                // Stock is untouched, and there is no field on this request that could touch it.
                .andExpect(jsonPath("$.stockQuantity").value(5));

        // An unknown property is not silently ignored; stock only moves through the delta endpoint.
        mockMvc.perform(patch("/api/admin/variants/" + variant.getId())
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"stockQuantity":99}
                                """))
                .andExpect(status().isBadRequest());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM product_variants", Integer.class)).isEqualTo(5);
    }

    @Test
    void archivesAndRestoresAVariantIdempotently() throws Exception {
        ProductVariant variant = catalogData.createVariant(product, "Black", "42", 5);

        mockMvc.perform(delete("/api/admin/variants/" + variant.getId())
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/admin/variants/" + variant.getId())
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isNoContent());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM product_variants WHERE archived_at IS NOT NULL", Integer.class))
                .isEqualTo(1);

        mockMvc.perform(post("/api/admin/variants/" + variant.getId() + "/restore")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archivedAt").doesNotExist());
    }

    @Test
    void keepsAnArchivedVariantOutOfTheStockTotalsButVisibleOnTheDetailView() throws Exception {
        catalogData.addVariant(product, "Black", "42", 5);
        ProductVariant retired = catalogData.createVariant(product, "Black", "43", 7);
        mockMvc.perform(delete("/api/admin/variants/" + retired.getId())
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/admin/products").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].variantCount").value(1))
                .andExpect(jsonPath("$.content[0].totalStock").value(5));

        // The detail view still shows it, so an administrator can find it to restore it.
        mockMvc.perform(get("/api/admin/products/" + product.getId())
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.variants.length()").value(2));
    }

    @Test
    void answers404ForAnUnknownProductOrVariant() throws Exception {
        mockMvc.perform(post("/api/admin/products/" + UUID.randomUUID() + "/variants")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"color":"Black","size":"42"}
                                """))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/admin/variants/" + UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn verify -Dit.test=AdminVariantIT`
Expected: FAIL — no handler for `/api/admin/variants/**`, so every request answers 403.

Note on `patchesColourAndSizeButNotStock`: it asserts 400 for an unknown JSON property, which requires `spring.jackson.deserialization.fail-on-unknown-properties=true`. Check `application.properties` before implementing. If the setting is absent, Jackson's default is to fail on unknown properties for records, so this should already hold — but confirm from the test run rather than assuming, and if it answers 200 instead, change that one assertion to 200 plus the existing `stock_quantity` check rather than turning the setting on globally, which would change every other endpoint's behaviour.

- [ ] **Step 3: Write the request DTOs**

`admin/dto/CreateVariantRequest.java`:

```java
package com.mvp.ecommercebackend.admin.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param stockQuantity an opening balance only, defaulting to zero. Every later change goes through
 *                      {@code POST /api/admin/variants/{id}/stock}, which holds a row lock; an
 *                      absolute setter here would let a stale read undo a concurrent sale.
 */
public record CreateVariantRequest(
        @NotBlank @Size(max = 60) String color,
        @NotBlank @Size(max = 30) String size,
        @Min(value = 0, message = "must not be negative") Integer stockQuantity) {

    public int openingBalance() {
        return stockQuantity == null ? 0 : stockQuantity;
    }
}
```

`admin/dto/UpdateVariantRequest.java`:

```java
package com.mvp.ecommercebackend.admin.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Partial: a null field is left unchanged.
 *
 * <p>There is deliberately no {@code stockQuantity}. Stock moves only through the delta endpoint, and
 * the absence of the field is the enforcement — no validation rule to forget.
 */
public record UpdateVariantRequest(
        @Size(max = 60) @Pattern(regexp = ".*\\S.*", message = "must not be blank") String color,
        @Size(max = 30) @Pattern(regexp = ".*\\S.*", message = "must not be blank") String size) {
}
```

- [ ] **Step 4: Write the service**

`admin/AdminVariantService.java`:

```java
package com.mvp.ecommercebackend.admin;

import com.mvp.ecommercebackend.admin.dto.AdminVariantResponse;
import com.mvp.ecommercebackend.admin.dto.CreateVariantRequest;
import com.mvp.ecommercebackend.admin.dto.UpdateVariantRequest;
import com.mvp.ecommercebackend.admin.entity.AdminEventType;
import com.mvp.ecommercebackend.admin.entity.AdminTargetType;
import com.mvp.ecommercebackend.catalog.entity.Product;
import com.mvp.ecommercebackend.catalog.entity.ProductVariant;
import com.mvp.ecommercebackend.catalog.repository.ProductVariantRepository;
import com.mvp.ecommercebackend.common.DuplicateResourceException;
import com.mvp.ecommercebackend.common.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/** Variant writes for administrators. Stock changes live in {@link AdminStockService}. */
@Service
public class AdminVariantService {

    private final ProductVariantRepository variantRepository;
    private final AdminProductService adminProductService;
    private final AdminEventService adminEventService;
    private final Clock clock;

    public AdminVariantService(ProductVariantRepository variantRepository,
                               AdminProductService adminProductService,
                               AdminEventService adminEventService,
                               Clock clock) {
        this.variantRepository = variantRepository;
        this.adminProductService = adminProductService;
        this.adminEventService = adminEventService;
        this.clock = clock;
    }

    /**
     * Adds a variant, optionally with an opening stock balance.
     *
     * <p>Permitted on an archived product: an administrator preparing a product for restore should
     * not have to un-archive it first, and an archived product hides from the catalogue regardless of
     * its variants' own flags.
     */
    @Transactional
    public AdminVariantResponse createVariant(UUID actorUserId, UUID productId,
                                              CreateVariantRequest request) {
        Product product = adminProductService.requireProduct(productId);
        requireNoLiveDuplicate(product, request.color(), request.size());

        ProductVariant variant = new ProductVariant();
        variant.setColor(request.color().trim());
        variant.setSize(request.size().trim());
        variant.setStockQuantity(request.openingBalance());
        variant.setProduct(product);
        product.getProductVariants().add(variant);
        ProductVariant saved = variantRepository.saveAndFlush(variant);

        adminEventService.record(actorUserId, AdminEventType.VARIANT_CREATED,
                AdminTargetType.PRODUCT_VARIANT, saved.getId(),
                "product=" + productId + " " + saved.getColor() + "/" + saved.getSize()
                        + " opening=" + saved.getStockQuantity());
        return AdminProductService.toVariantResponse(saved);
    }

    @Transactional
    public AdminVariantResponse updateVariant(UUID actorUserId, UUID variantId,
                                              UpdateVariantRequest request) {
        ProductVariant variant = requireVariant(variantId);
        if (request.color() != null) {
            variant.setColor(request.color().trim());
        }
        if (request.size() != null) {
            variant.setSize(request.size().trim());
        }

        adminEventService.record(actorUserId, AdminEventType.VARIANT_UPDATED,
                AdminTargetType.PRODUCT_VARIANT, variantId,
                variant.getColor() + "/" + variant.getSize());
        return AdminProductService.toVariantResponse(variant);
    }

    /** Idempotent, like {@code AdminProductService.archiveProduct}. */
    @Transactional
    public void archiveVariant(UUID actorUserId, UUID variantId) {
        ProductVariant variant = requireVariant(variantId);
        if (variant.getArchivedAt() != null) {
            return;
        }
        variant.setArchivedAt(clock.instant());
        adminEventService.record(actorUserId, AdminEventType.VARIANT_ARCHIVED,
                AdminTargetType.PRODUCT_VARIANT, variantId,
                variant.getColor() + "/" + variant.getSize());
    }

    @Transactional
    public AdminVariantResponse restoreVariant(UUID actorUserId, UUID variantId) {
        ProductVariant variant = requireVariant(variantId);
        if (variant.getArchivedAt() != null) {
            variant.setArchivedAt(null);
            adminEventService.record(actorUserId, AdminEventType.VARIANT_RESTORED,
                    AdminTargetType.PRODUCT_VARIANT, variantId,
                    variant.getColor() + "/" + variant.getSize());
        }
        return AdminProductService.toVariantResponse(variant);
    }

    /** Shared with {@link AdminStockService}. */
    ProductVariant requireVariant(UUID variantId) {
        return variantRepository.findById(variantId).orElseThrow(
                () -> new ResourceNotFoundException("Variant " + variantId + " was not found"));
    }

    /**
     * Two live "Black / 42" rows on one product would show a customer the same option twice and split
     * its stock across two rows.
     *
     * <p>Archived rows are ignored, so retiring a variant frees its colour and size for reuse. There
     * is no database unique constraint behind this — {@code V1__init.sql} has none on
     * {@code (product_id, color, size)} — so two simultaneous creates could both pass. Accepted: this
     * is an admin-only write path, and adding a partial unique index would mean a migration the spec
     * did not call for.
     */
    private static void requireNoLiveDuplicate(Product product, String color, String size) {
        boolean duplicate = product.getProductVariants().stream()
                .filter(existing -> existing.getArchivedAt() == null)
                .anyMatch(existing -> color.trim().equalsIgnoreCase(existing.getColor())
                        && size.trim().equalsIgnoreCase(existing.getSize()));
        if (duplicate) {
            throw new DuplicateResourceException("Product " + product.getId()
                    + " already has a variant " + color.trim() + "/" + size.trim());
        }
    }
}
```

- [ ] **Step 5: Write the controller**

`admin/AdminVariantController.java`:

```java
package com.mvp.ecommercebackend.admin;

import com.mvp.ecommercebackend.admin.dto.AdminVariantResponse;
import com.mvp.ecommercebackend.admin.dto.CreateVariantRequest;
import com.mvp.ecommercebackend.admin.dto.UpdateVariantRequest;
import com.mvp.ecommercebackend.auth.AuthenticatedUser;
import com.mvp.ecommercebackend.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Variant administration.
 *
 * <p>Mapped at {@code /api/admin} rather than at a single resource root because creating a variant is
 * addressed under its product while the rest are addressed by the variant's own id.
 */
@RestController
@RequestMapping("/api/admin")
@Tag(name = "Admin Catalog")
@SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME)
public class AdminVariantController {

    private final AdminVariantService adminVariantService;

    public AdminVariantController(AdminVariantService adminVariantService) {
        this.adminVariantService = adminVariantService;
    }

    @PostMapping("/products/{id}/variants")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a variant to a product",
            description = "stockQuantity is an opening balance and defaults to zero. Later changes "
                    + "go through the stock endpoint.")
    public AdminVariantResponse createVariant(@AuthenticationPrincipal AuthenticatedUser principal,
                                              @PathVariable UUID id,
                                              @Valid @RequestBody CreateVariantRequest request) {
        return adminVariantService.createVariant(principal.id(), id, request);
    }

    @PatchMapping("/variants/{id}")
    @Operation(summary = "Update a variant's colour or size",
            description = "Stock is not patchable; use the stock endpoint.")
    public AdminVariantResponse updateVariant(@AuthenticationPrincipal AuthenticatedUser principal,
                                              @PathVariable UUID id,
                                              @Valid @RequestBody UpdateVariantRequest request) {
        return adminVariantService.updateVariant(principal.id(), id, request);
    }

    @DeleteMapping("/variants/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Archive a variant",
            description = "Sets archivedAt rather than deleting the row, so live carts and order "
                    + "history survive. Idempotent.")
    public void archiveVariant(@AuthenticationPrincipal AuthenticatedUser principal,
                               @PathVariable UUID id) {
        adminVariantService.archiveVariant(principal.id(), id);
    }

    @PostMapping("/variants/{id}/restore")
    @Operation(summary = "Restore an archived variant", description = "Idempotent.")
    public AdminVariantResponse restoreVariant(@AuthenticationPrincipal AuthenticatedUser principal,
                                               @PathVariable UUID id) {
        return adminVariantService.restoreVariant(principal.id(), id);
    }
}
```

- [ ] **Step 6: Register the new paths in `OpenApiIT`**

Add to `EVERY_PATH`:

```java
            "/api/admin/products/{id}/variants",
            "/api/admin/variants/{id}",
            "/api/admin/variants/{id}/restore",
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `mvn verify -Dit.test='AdminVariantIT,AdminProductIT,OpenApiIT'`
Expected: PASS. `AdminProductIT` is re-run because Task 4's stock totals now have to keep ignoring archived variants.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/mvp/ecommercebackend/admin \
        src/test/java/com/mvp/ecommercebackend/admin/AdminVariantIT.java \
        src/test/java/com/mvp/ecommercebackend/config/OpenApiIT.java
git commit -m "feat(admin): variant administration with archive and restore"
```

---

### Task 6: Race-free stock adjustment

**Files:**
- Create: `src/main/java/com/mvp/ecommercebackend/admin/dto/AdjustStockRequest.java`
- Create: `src/main/java/com/mvp/ecommercebackend/admin/dto/StockAdjustmentResponse.java`
- Create: `src/main/java/com/mvp/ecommercebackend/admin/AdminStockService.java`
- Modify: `src/main/java/com/mvp/ecommercebackend/admin/AdminVariantController.java` (one new method)
- Modify: `src/main/java/com/mvp/ecommercebackend/catalog/repository/ProductVariantRepository.java` (add `lockById`)
- Modify: `src/test/java/com/mvp/ecommercebackend/config/OpenApiIT.java`
- Test: `src/test/java/com/mvp/ecommercebackend/admin/AdminStockIT.java`

**Interfaces:**
- Consumes: `ProductVariantRepository`, `AdminEventService.record(...)`, `AdminVariantService.requireVariant(UUID)` (for the 404, before the lock).
- Produces:
  - `AdjustStockRequest(Integer delta, String reason)`, `StockAdjustmentResponse(UUID variantId, int previousQuantity, int newQuantity, int delta, String reason)`.
  - `ProductVariantRepository.lockById(UUID)` returning `Optional<ProductVariant>` under `PESSIMISTIC_WRITE`.
  - Endpoint `/api/admin/variants/{id}/stock`.

**Decisions this task locks in:**

- The row is read **under `SELECT … FOR UPDATE`**, exactly as `OrderService.lockVariants` does, and the new quantity is computed from that locked read. This is the entire justification for a delta API over an absolute setter, and Task 7 proves it.
- `delta` must be non-zero. A zero adjustment would write an audit row asserting a change that did not happen.
- A delta that would leave a negative quantity throws `InsufficientStockException` (409) and the transaction rolls back, so no audit row is written either — the response and the audit trail cannot disagree.
- `reason` is **required**. It is the only field in the system that records *why* stock moved, and an unexplained adjustment is indistinguishable from a mistake. This is where the spec's "gives the stock `reason` a home" lands: it goes into `admin_events.detail`, so no separate table is needed.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/mvp/ecommercebackend/admin/AdminStockIT.java`:

```java
package com.mvp.ecommercebackend.admin;

import com.mvp.ecommercebackend.auth.entity.User;
import com.mvp.ecommercebackend.catalog.entity.CategoryType;
import com.mvp.ecommercebackend.catalog.entity.Product;
import com.mvp.ecommercebackend.catalog.entity.ProductVariant;
import com.mvp.ecommercebackend.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminStockIT extends AbstractIntegrationTest {

    private String admin;
    private ProductVariant variant;

    @BeforeEach
    void setUp() {
        User user = testData.createAdmin("stock-admin@example.com", "correct-horse-battery");
        admin = bearer(user);
        CategoryType type = catalogData.createCategoryWithType("Footwear", "Running Shoes");
        Product product = catalogData.createProduct(type, "Trail Runner", "129.99");
        variant = catalogData.createVariant(product, "Black", "42", 10);
    }

    @Test
    void appliesAPositiveDeltaAndRecordsTheReason() throws Exception {
        adjust("""
                {"delta":5,"reason":"Delivery from supplier, PO 4471"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previousQuantity").value(10))
                .andExpect(jsonPath("$.newQuantity").value(15))
                .andExpect(jsonPath("$.delta").value(5))
                .andExpect(jsonPath("$.reason").value("Delivery from supplier, PO 4471"));

        assertThat(stock()).isEqualTo(15);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT detail FROM admin_events WHERE action = 'STOCK_ADJUSTED'", String.class))
                .contains("delta=5", "10", "15", "Delivery from supplier, PO 4471");
    }

    @Test
    void appliesANegativeDelta() throws Exception {
        adjust("""
                {"delta":-4,"reason":"Damaged in the warehouse"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.newQuantity").value(6));

        assertThat(stock()).isEqualTo(6);
    }

    @Test
    void allowsADeltaThatLandsExactlyOnZero() throws Exception {
        adjust("""
                {"delta":-10,"reason":"Written off"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.newQuantity").value(0));

        assertThat(stock()).isZero();
    }

    /** Acceptance criterion 7: rejected with 409, and nothing changes — including the audit trail. */
    @Test
    void rejectsADeltaThatWouldGoNegativeAndChangesNothing() throws Exception {
        adjust("""
                {"delta":-11,"reason":"Typo"}
                """)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Insufficient stock"));

        assertThat(stock()).isEqualTo(10);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM admin_events", Integer.class)).isZero();
    }

    @Test
    void rejectsAZeroDeltaAndAMissingReason() throws Exception {
        adjust("""
                {"delta":0,"reason":"Nothing happened"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("delta"));

        adjust("""
                {"delta":5}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("reason"));

        adjust("""
                {"delta":5,"reason":"   "}
                """)
                .andExpect(status().isBadRequest());
    }

    @Test
    void adjustsAnArchivedVariantSoStockCanBeCorrectedBeforeRestoring() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/admin/variants/" + variant.getId())
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isNoContent());

        adjust("""
                {"delta":3,"reason":"Recount before restoring"}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.newQuantity").value(13));
    }

    @Test
    void answers404ForAnUnknownVariant() throws Exception {
        mockMvc.perform(post("/api/admin/variants/" + java.util.UUID.randomUUID() + "/stock")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"delta":1,"reason":"Recount"}
                                """))
                .andExpect(status().isNotFound());
    }

    private org.springframework.test.web.servlet.ResultActions adjust(String body) throws Exception {
        return mockMvc.perform(post("/api/admin/variants/" + variant.getId() + "/stock")
                .header(HttpHeaders.AUTHORIZATION, admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private Integer stock() {
        return jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM product_variants WHERE id = ?",
                Integer.class, variant.getId());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn verify -Dit.test=AdminStockIT`
Expected: FAIL — no `/api/admin/variants/{id}/stock` handler.

Check the `title` string asserted in `rejectsADeltaThatWouldGoNegativeAndChangesNothing` against `GlobalExceptionHandler`'s existing `InsufficientStockException` handler and use whatever that handler actually sets. Do not change the handler to match the test.

- [ ] **Step 3: Write the DTOs**

`admin/dto/AdjustStockRequest.java`:

```java
package com.mvp.ecommercebackend.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A signed stock movement.
 *
 * @param delta  non-zero. Zero would write an audit row asserting a change that did not happen.
 * @param reason required: this is the only record of *why* stock moved, and an unexplained
 *               adjustment cannot be told apart from a mistake
 */
public record AdjustStockRequest(
        @NotNull Integer delta,
        @NotBlank @Size(max = 500) String reason) {

    @jakarta.validation.constraints.AssertTrue(message = "must not be zero")
    public boolean isDeltaNonZero() {
        return delta == null || delta != 0;
    }
}
```

Note: the `@AssertTrue` method is named `isDeltaNonZero`, so bean validation reports the violation against the property `deltaNonZero`, not `delta`. If `rejectsAZeroDeltaAndAMissingReason` fails on the field name, either change the test's expected field to `deltaNonZero` or replace `@AssertTrue` with a `@Min(-1_000_000) @Max(1_000_000)` pair plus an explicit service-side zero check that throws `IllegalArgumentException`. Prefer changing the test: the constraint belongs on the request, and the exact property name is not a contract worth contorting the code for.

`admin/dto/StockAdjustmentResponse.java`:

```java
package com.mvp.ecommercebackend.admin.dto;

import java.util.UUID;

/**
 * The result of one adjustment.
 *
 * <p>Both quantities are returned rather than just the new one: an administrator who sees a
 * {@code previousQuantity} they did not expect has just learned that stock moved underneath them,
 * which a bare new total would hide.
 */
public record StockAdjustmentResponse(
        UUID variantId,
        int previousQuantity,
        int newQuantity,
        int delta,
        String reason) {
}
```

- [ ] **Step 4: Add the locking finder**

In `ProductVariantRepository`, add beside `lockAllByIdIn`:

```java
    /**
     * Locks one variant for update.
     *
     * <p>The single-row sibling of {@link #lockAllByIdIn}. An administrator's stock adjustment takes
     * the same lock a checkout does, so the two serialise against each other and a delta applied
     * during a sale cannot be computed from a stale read.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select variant from ProductVariant variant where variant.id = :id")
    Optional<ProductVariant> lockById(@Param("id") UUID id);
```

- [ ] **Step 5: Write the service**

`admin/AdminStockService.java`:

```java
package com.mvp.ecommercebackend.admin;

import com.mvp.ecommercebackend.admin.dto.AdjustStockRequest;
import com.mvp.ecommercebackend.admin.dto.StockAdjustmentResponse;
import com.mvp.ecommercebackend.admin.entity.AdminEventType;
import com.mvp.ecommercebackend.admin.entity.AdminTargetType;
import com.mvp.ecommercebackend.catalog.entity.ProductVariant;
import com.mvp.ecommercebackend.catalog.repository.ProductVariantRepository;
import com.mvp.ecommercebackend.common.InsufficientStockException;
import com.mvp.ecommercebackend.common.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * The only path that changes stock outside checkout.
 *
 * <p>Signed deltas applied under a row lock, never an absolute set. An absolute setter would let an
 * administrator's stale read undo a concurrent sale: read 10, a customer buys one leaving 9, write
 * 10, and a sold unit is back on the shelf. {@code AdminStockConcurrencyIT} proves the lock works.
 */
@Service
public class AdminStockService {

    private final ProductVariantRepository variantRepository;
    private final AdminEventService adminEventService;

    public AdminStockService(ProductVariantRepository variantRepository,
                             AdminEventService adminEventService) {
        this.variantRepository = variantRepository;
        this.adminEventService = adminEventService;
    }

    /**
     * Applies {@code delta} to the variant's stock.
     *
     * <p>Permitted on an archived variant, so a count can be corrected before restoring it.
     *
     * @throws InsufficientStockException when the result would be negative; the transaction rolls
     *                                    back, so no audit row survives either
     */
    @Transactional
    public StockAdjustmentResponse adjustStock(UUID actorUserId, UUID variantId,
                                               AdjustStockRequest request) {
        // The lock, not findById: everything after this reads and writes the row we hold.
        ProductVariant variant = variantRepository.lockById(variantId).orElseThrow(
                () -> new ResourceNotFoundException("Variant " + variantId + " was not found"));

        int previous = variant.getStockQuantity();
        int updated = previous + request.delta();
        if (updated < 0) {
            throw new InsufficientStockException("Variant " + variantId + " holds " + previous
                    + ", so a change of " + request.delta() + " would leave " + updated);
        }
        variant.setStockQuantity(updated);

        adminEventService.record(actorUserId, AdminEventType.STOCK_ADJUSTED,
                AdminTargetType.PRODUCT_VARIANT, variantId,
                "delta=" + request.delta() + " " + previous + "->" + updated
                        + " reason=" + request.reason().trim());
        return new StockAdjustmentResponse(variantId, previous, updated, request.delta(),
                request.reason().trim());
    }
}
```

`InsufficientStockException` lives in `common`, not `commerce`, and takes a single message. It is reused here rather than duplicated: "would leave a negative quantity" and "cannot sell what is not there" are the same condition.

- [ ] **Step 6: Add the endpoint to `AdminVariantController`**

```java
    @PostMapping("/variants/{id}/stock")
    @Operation(summary = "Adjust a variant's stock by a signed delta",
            description = "The row is locked for update, so an adjustment cannot be computed from a "
                    + "stale read while a checkout is in flight. A result below zero answers 409. "
                    + "reason is required and is written to the audit trail.")
    public StockAdjustmentResponse adjustStock(@AuthenticationPrincipal AuthenticatedUser principal,
                                               @PathVariable UUID id,
                                               @Valid @RequestBody AdjustStockRequest request) {
        return adminStockService.adjustStock(principal.id(), id, request);
    }
```

Add `AdminStockService` to the controller's constructor and store it in a `private final` field alongside `adminVariantService`. Add imports for `AdjustStockRequest` and `StockAdjustmentResponse`.

- [ ] **Step 7: Register the new path in `OpenApiIT`**

Add to `EVERY_PATH`:

```java
            "/api/admin/variants/{id}/stock",
```

- [ ] **Step 8: Run the tests to verify they pass**

Run: `mvn verify -Dit.test='AdminStockIT,AdminVariantIT,OpenApiIT'`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/mvp/ecommercebackend/admin \
        src/main/java/com/mvp/ecommercebackend/catalog/repository/ProductVariantRepository.java \
        src/test/java/com/mvp/ecommercebackend/admin/AdminStockIT.java \
        src/test/java/com/mvp/ecommercebackend/config/OpenApiIT.java
git commit -m "feat(admin): race-free stock adjustment by signed delta"
```

---

### Task 7: Prove the stock lock under concurrency

**Files:**
- Test: `src/test/java/com/mvp/ecommercebackend/admin/AdminStockConcurrencyIT.java`

**Interfaces:**
- Consumes: `AdminStockService.adjustStock(UUID, UUID, AdjustStockRequest)` from Task 6, `OrderService.placeOrder(UUID, PlaceOrderRequest)`, `ProductVariantRepository.lockById`.
- Produces: nothing. This task adds no production code.

**Why this is its own task:** the spec calls this "the test this design most depends on". The delta endpoint's entire justification is that it is race-free, and a reviewer should be able to reject this proof while accepting Task 6's happy paths. It also has to be written after Task 6 exists, because it drives the real service from two threads.

Both tests go through the services rather than MockMvc, exactly as `OrderConcurrencyIT` does: the point is two real concurrent transactions racing for one row, and calling through the service proxy from two threads gives that without depending on MockMvc being safe to share.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/mvp/ecommercebackend/admin/AdminStockConcurrencyIT.java`:

```java
package com.mvp.ecommercebackend.admin;

import com.mvp.ecommercebackend.admin.dto.AdjustStockRequest;
import com.mvp.ecommercebackend.auth.entity.User;
import com.mvp.ecommercebackend.catalog.entity.CategoryType;
import com.mvp.ecommercebackend.catalog.entity.Product;
import com.mvp.ecommercebackend.catalog.entity.ProductVariant;
import com.mvp.ecommercebackend.commerce.OrderService;
import com.mvp.ecommercebackend.commerce.dto.PlaceOrderRequest;
import com.mvp.ecommercebackend.common.InsufficientStockException;
import com.mvp.ecommercebackend.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Acceptance criterion 6, and the test the delta endpoint exists for.
 *
 * <p>An absolute {@code PATCH stockQuantity} could not pass either of these: an administrator who
 * reads 10, has a customer buy one, and then writes 10 has put a sold unit back on the shelf. The
 * delta plus the row lock in {@code ProductVariantRepository.lockById} makes that arithmetic
 * impossible.
 *
 * <p>Confirm this test has teeth before committing: delete {@code @Lock} from {@code lockById} and
 * {@code noLostUpdateWhenAnAdjustmentRacesASale} must fail with 6 or 0 rather than 5. Put the
 * annotation back afterwards. A concurrency test that passes without the lock is proving nothing.
 */
class AdminStockConcurrencyIT extends AbstractIntegrationTest {

    private static final String ADDRESS = """
            {"recipientName":"Ada Lovelace","line1":"12 Analytical Way","city":"London",
             "postalCode":"E1 6AN","country":"GB"}
            """;

    @Autowired
    private OrderService orderService;

    @Autowired
    private AdminStockService adminStockService;

    /**
     * Stock 1, a sale of 1 and a write-off of 1 racing. Exactly one wins, and stock lands on 0 — never
     * -1, and never 0 with both having reported success.
     */
    @Test
    void neverLetsASaleAndAWriteOffBothTakeTheLastUnit() throws Exception {
        ProductVariant lastOne = variantWithStock(1);
        User customer = testData.createCustomer("racer@example.com", "correct-horse-battery");
        User admin = testData.createAdmin("stock-racer@example.com", "correct-horse-battery");
        PlaceOrderRequest order = new PlaceOrderRequest(prepareCart(customer, lastOne));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Future<String> sale = pool.submit(sell(start, customer.getId(), order));
            Future<String> writeOff = pool.submit(
                    adjust(start, admin.getId(), lastOne.getId(), -1, "Damaged"));
            start.countDown();

            List<String> results = List.of(
                    sale.get(30, TimeUnit.SECONDS), writeOff.get(30, TimeUnit.SECONDS));
            // Either order is legitimate; what is not legitimate is both succeeding.
            assertThat(results).containsAnyOf("REJECTED");
            assertThat(results.stream().filter(result -> !"REJECTED".equals(result)).count())
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }

        assertThat(stockOf(lastOne)).isZero();
    }

    /**
     * The lost-update case, and the one that actually needs the lock.
     *
     * <p>Stock 1, a delivery of +5 and a sale of 1 racing. Both must succeed and the result must be
     * exactly 5. Without the lock both threads read 1, one computes 6 and the other 0, and whichever
     * flushes last wins — so a passing 5 is only possible if the two serialised.
     */
    @Test
    void noLostUpdateWhenAnAdjustmentRacesASale() throws Exception {
        ProductVariant variant = variantWithStock(1);
        User customer = testData.createCustomer("racer2@example.com", "correct-horse-battery");
        User admin = testData.createAdmin("stock-racer2@example.com", "correct-horse-battery");
        PlaceOrderRequest order = new PlaceOrderRequest(prepareCart(customer, variant));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Future<String> sale = pool.submit(sell(start, customer.getId(), order));
            Future<String> delivery = pool.submit(
                    adjust(start, admin.getId(), variant.getId(), 5, "Delivery, PO 4471"));
            start.countDown();

            assertThat(List.of(sale.get(30, TimeUnit.SECONDS), delivery.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("PLACED", "ADJUSTED");
        } finally {
            pool.shutdownNow();
        }

        // 1 + 5 - 1. Six would mean the sale was lost; zero would mean the delivery was.
        assertThat(stockOf(variant)).isEqualTo(5);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM orders", Integer.class))
                .isEqualTo(1);
    }

    private Callable<String> sell(CountDownLatch start, UUID userId, PlaceOrderRequest request) {
        return () -> {
            start.await(10, TimeUnit.SECONDS);
            try {
                orderService.placeOrder(userId, request);
                return "PLACED";
            } catch (InsufficientStockException expected) {
                return "REJECTED";
            }
        };
    }

    private Callable<String> adjust(CountDownLatch start, UUID actorId, UUID variantId,
                                    int delta, String reason) {
        return () -> {
            start.await(10, TimeUnit.SECONDS);
            try {
                adminStockService.adjustStock(actorId, variantId,
                        new AdjustStockRequest(delta, reason));
                return "ADJUSTED";
            } catch (InsufficientStockException expected) {
                return "REJECTED";
            }
        };
    }

    private ProductVariant variantWithStock(int stockQuantity) {
        CategoryType type = catalogData.createCategoryWithType("Footwear", "Running Shoes");
        Product product = catalogData.createProduct(type, "Trail Runner", "129.99");
        return catalogData.createVariant(product, "Black", "42", stockQuantity);
    }

    private Integer stockOf(ProductVariant variant) {
        return jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM product_variants WHERE id = ?",
                Integer.class, variant.getId());
    }

    /** Gives the user an address and a cart holding one of {@code variant}, and returns the address. */
    private UUID prepareCart(User user, ProductVariant variant) throws Exception {
        String token = bearer(user);
        mockMvc.perform(post("/api/me/cart/items")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variantId\":\"" + variant.getId() + "\",\"quantity\":1}"))
                .andExpect(status().isOk());

        String location = mockMvc.perform(post("/api/me/addresses")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ADDRESS))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader(HttpHeaders.LOCATION);
        return UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
    }
}
```

- [ ] **Step 2: Run the tests to verify they pass**

Run: `mvn verify -Dit.test=AdminStockConcurrencyIT`
Expected: PASS. Unlike the other tasks these tests should pass first time — Task 6 already wrote the locking code. This task's job is to prove it, so if either test *fails* here, the bug is in Task 6's `adjustStock`, not in the test.

Note: `AdminStockService` is package-private-friendly but the class is `public` and in package `com.mvp.ecommercebackend.admin`, and this test is in that same package, so `@Autowired` works without widening anything.

- [ ] **Step 3: Verify the tests have teeth**

Temporarily delete the `@Lock(LockModeType.PESSIMISTIC_WRITE)` line from `ProductVariantRepository.lockById` and run:

Run: `mvn verify -Dit.test=AdminStockConcurrencyIT`
Expected: FAIL — `noLostUpdateWhenAnAdjustmentRacesASale` reports 6 or 0 instead of 5.

Then restore the annotation and re-run to confirm PASS. Do not commit without doing this. A concurrency test that passes with the lock removed is asserting nothing, and the codebase already treats this as the standard — see the docstring on `OrderConcurrencyIT`.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/mvp/ecommercebackend/admin/AdminStockConcurrencyIT.java
git commit -m "test(admin): prove stock adjustment cannot race a sale"
```

---

### Task 8: Product resources

**Files:**
- Create: `src/main/java/com/mvp/ecommercebackend/catalog/repository/ResourceRepository.java`
- Create: `src/main/java/com/mvp/ecommercebackend/admin/dto/CreateResourceRequest.java`
- Create: `src/main/java/com/mvp/ecommercebackend/admin/dto/UpdateResourceRequest.java`
- Create: `src/main/java/com/mvp/ecommercebackend/admin/AdminResourceService.java`
- Create: `src/main/java/com/mvp/ecommercebackend/admin/AdminResourceController.java`
- Modify: `src/test/java/com/mvp/ecommercebackend/config/OpenApiIT.java`
- Test: `src/test/java/com/mvp/ecommercebackend/admin/AdminResourceIT.java`

**Interfaces:**
- Consumes: `AdminProductService.requireProduct(UUID)` and `AdminProductService.toResourceResponse(Resource)` from Task 4; `AdminEventService.record(...)`.
- Produces:
  - `ResourceRepository extends JpaRepository<Resource, UUID>`.
  - `CreateResourceRequest(String name, String url, String type, Boolean isPrimary)`, `UpdateResourceRequest(String name, String url, String type, Boolean isPrimary)`.
  - Endpoints `/api/admin/products/{id}/resources`, `/api/admin/resources/{id}`.

**Decisions this task locks in:**

- Resources are **hard deleted**, unlike products and variants. The spec's reason: a URL carries no history worth preserving. Concretely, nothing snapshots a resource — `order_items` copies the product name and price but no image — so deleting one cannot damage an order.
- Setting `isPrimary: true` clears the flag on the product's other resources **in the same transaction**. There is no database constraint enforcing one primary per product in `V1__init.sql`, and two primaries would make `findPrimaryThumbnails` return two rows for one product, whose `Collectors.toMap` merge function then picks one arbitrarily — a thumbnail that changes between requests.
- Deleting the primary resource does **not** promote another. The product then has no primary, `findPrimaryThumbnails` returns no row for it, and both the public and admin listings show a null thumbnail. Guessing at a replacement would silently put an image on the storefront that nobody chose; a missing thumbnail is visible and fixable.
- `isPrimary` is `NOT NULL DEFAULT false` in the database, so the request treats a missing value as `false`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/mvp/ecommercebackend/admin/AdminResourceIT.java`:

```java
package com.mvp.ecommercebackend.admin;

import com.mvp.ecommercebackend.auth.entity.User;
import com.mvp.ecommercebackend.catalog.entity.CategoryType;
import com.mvp.ecommercebackend.catalog.entity.Product;
import com.mvp.ecommercebackend.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminResourceIT extends AbstractIntegrationTest {

    private String admin;
    private Product product;

    @BeforeEach
    void setUp() {
        User user = testData.createAdmin("resource-admin@example.com", "correct-horse-battery");
        admin = bearer(user);
        CategoryType type = catalogData.createCategoryWithType("Footwear", "Running Shoes");
        product = catalogData.createProduct(type, "Trail Runner", "129.99");
    }

    @Test
    void addsAnImageToAProduct() throws Exception {
        mockMvc.perform(post("/api/admin/products/" + product.getId() + "/resources")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"front.jpg","url":"https://cdn.example.com/front.jpg",
                                 "type":"IMAGE","isPrimary":true}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.url").value("https://cdn.example.com/front.jpg"))
                .andExpect(jsonPath("$.isPrimary").value(true));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT action FROM admin_events", String.class)).isEqualTo("RESOURCE_CREATED");
    }

    @Test
    void defaultsIsPrimaryToFalse() throws Exception {
        mockMvc.perform(post("/api/admin/products/" + product.getId() + "/resources")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":"https://cdn.example.com/side.jpg","type":"IMAGE"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isPrimary").value(false));
    }

    @Test
    void requiresAUrl() throws Exception {
        mockMvc.perform(post("/api/admin/products/" + product.getId() + "/resources")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"front.jpg","type":"IMAGE"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("url"));
    }

    /** Exactly one primary per product, enforced here because no database constraint does. */
    @Test
    void movingThePrimaryFlagClearsItEverywhereElse() throws Exception {
        catalogData.addImage(product, "https://cdn.example.com/front.jpg", true);
        catalogData.addImage(product, "https://cdn.example.com/side.jpg", false);
        UUID side = resourceIdFor("https://cdn.example.com/side.jpg");

        mockMvc.perform(patch("/api/admin/resources/" + side)
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"isPrimary":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isPrimary").value(true));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM product_resources WHERE is_primary = true", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT url FROM product_resources WHERE is_primary = true", String.class))
                .isEqualTo("https://cdn.example.com/side.jpg");
    }

    @Test
    void addingASecondPrimaryDemotesTheFirst() throws Exception {
        catalogData.addImage(product, "https://cdn.example.com/front.jpg", true);

        mockMvc.perform(post("/api/admin/products/" + product.getId() + "/resources")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":"https://cdn.example.com/hero.jpg","type":"IMAGE",
                                 "isPrimary":true}
                                """))
                .andExpect(status().isCreated());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT url FROM product_resources WHERE is_primary = true", String.class))
                .isEqualTo("https://cdn.example.com/hero.jpg");
    }

    @Test
    void patchesOnlyTheFieldsSupplied() throws Exception {
        catalogData.addImage(product, "https://cdn.example.com/front.jpg", true);
        UUID front = resourceIdFor("https://cdn.example.com/front.jpg");

        mockMvc.perform(patch("/api/admin/resources/" + front)
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"hero-shot.jpg"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("hero-shot.jpg"))
                .andExpect(jsonPath("$.url").value("https://cdn.example.com/front.jpg"))
                .andExpect(jsonPath("$.isPrimary").value(true));
    }

    /** Hard delete, unlike products and variants: nothing snapshots a resource. */
    @Test
    void deletesAResourceOutright() throws Exception {
        catalogData.addImage(product, "https://cdn.example.com/front.jpg", true);
        UUID front = resourceIdFor("https://cdn.example.com/front.jpg");

        mockMvc.perform(delete("/api/admin/resources/" + front)
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isNoContent());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM product_resources", Integer.class)).isZero();
        // No replacement is promoted, so the product simply has no thumbnail.
        mockMvc.perform(get("/api/admin/products").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].thumbnail").doesNotExist());
    }

    @Test
    void answers404ForAnUnknownProductOrResource() throws Exception {
        mockMvc.perform(post("/api/admin/products/" + UUID.randomUUID() + "/resources")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":"https://cdn.example.com/front.jpg"}
                                """))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/admin/resources/" + UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isNotFound());
    }

    private UUID resourceIdFor(String url) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM product_resources WHERE url = ?", UUID.class, url);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn verify -Dit.test=AdminResourceIT`
Expected: FAIL — no `/api/admin/resources/**` handler.

- [ ] **Step 3: Write the repository**

`catalog/repository/ResourceRepository.java`:

```java
package com.mvp.ecommercebackend.catalog.repository;

import com.mvp.ecommercebackend.catalog.entity.Resource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Product images and other attachments.
 *
 * <p>New in the admin slice: the catalogue only ever reached resources through
 * {@code Product.getResources()} or {@code ProductRepository.findPrimaryThumbnails}, and neither can
 * address one by its own id.
 */
@Repository
public interface ResourceRepository extends JpaRepository<Resource, UUID> {

    /**
     * Fetches the owning product too. Every write here needs it — to demote the product's other
     * primaries, and to keep the in-memory collection consistent with the row being deleted.
     */
    @Query("""
            select resource from Resource resource
            join fetch resource.product
            where resource.id = :id
            """)
    Optional<Resource> findWithProductById(@Param("id") UUID id);
}
```

- [ ] **Step 4: Write the request DTOs**

`admin/dto/CreateResourceRequest.java`:

```java
package com.mvp.ecommercebackend.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param url       required; capped at 1000 to match {@code product_resources.url varchar(1000)}
 * @param isPrimary optional. The column is {@code NOT NULL DEFAULT false}, so a missing value means
 *                  false. Setting it true demotes the product's other resources.
 */
public record CreateResourceRequest(
        @Size(max = 255) String name,
        @NotBlank @Size(max = 1000) String url,
        @Size(max = 30) String type,
        Boolean isPrimary) {

    public boolean primary() {
        return Boolean.TRUE.equals(isPrimary);
    }
}
```

`admin/dto/UpdateResourceRequest.java`:

```java
package com.mvp.ecommercebackend.admin.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Partial: a null field is left unchanged.
 *
 * <p>{@code isPrimary} is a {@code Boolean} rather than a {@code boolean} precisely so that "not
 * mentioned" and "set to false" stay distinguishable — a primitive would silently demote the primary
 * image on every unrelated rename.
 */
public record UpdateResourceRequest(
        @Size(max = 255) String name,
        @Size(max = 1000) @Pattern(regexp = ".*\\S.*", message = "must not be blank") String url,
        @Size(max = 30) String type,
        Boolean isPrimary) {
}
```

- [ ] **Step 5: Write the service**

`admin/AdminResourceService.java`:

```java
package com.mvp.ecommercebackend.admin;

import com.mvp.ecommercebackend.admin.dto.CreateResourceRequest;
import com.mvp.ecommercebackend.admin.dto.UpdateResourceRequest;
import com.mvp.ecommercebackend.admin.entity.AdminEventType;
import com.mvp.ecommercebackend.admin.entity.AdminTargetType;
import com.mvp.ecommercebackend.catalog.dto.ProductResourceDto;
import com.mvp.ecommercebackend.catalog.entity.Product;
import com.mvp.ecommercebackend.catalog.entity.Resource;
import com.mvp.ecommercebackend.catalog.repository.ResourceRepository;
import com.mvp.ecommercebackend.common.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Product images and attachments.
 *
 * <p>Hard deletes, unlike products and variants: nothing snapshots a resource — {@code order_items}
 * copies a product's name and price but no image — so removing one cannot damage an order.
 */
@Service
public class AdminResourceService {

    private final ResourceRepository resourceRepository;
    private final AdminProductService adminProductService;
    private final AdminEventService adminEventService;

    public AdminResourceService(ResourceRepository resourceRepository,
                                AdminProductService adminProductService,
                                AdminEventService adminEventService) {
        this.resourceRepository = resourceRepository;
        this.adminProductService = adminProductService;
        this.adminEventService = adminEventService;
    }

    @Transactional
    public ProductResourceDto createResource(UUID actorUserId, UUID productId,
                                             CreateResourceRequest request) {
        Product product = adminProductService.requireProduct(productId);

        Resource resource = new Resource();
        resource.setName(request.name());
        resource.setUrl(request.url().trim());
        resource.setType(request.type());
        resource.setIsPrimary(request.primary());
        resource.setProduct(product);
        if (request.primary()) {
            demoteOtherPrimaries(product, null);
        }
        product.getResources().add(resource);
        Resource saved = resourceRepository.saveAndFlush(resource);

        adminEventService.record(actorUserId, AdminEventType.RESOURCE_CREATED,
                AdminTargetType.PRODUCT_RESOURCE, saved.getId(),
                "product=" + productId + " url=" + saved.getUrl());
        return AdminProductService.toResourceResponse(saved);
    }

    @Transactional
    public ProductResourceDto updateResource(UUID actorUserId, UUID resourceId,
                                             UpdateResourceRequest request) {
        Resource resource = requireResource(resourceId);
        if (request.name() != null) {
            resource.setName(request.name());
        }
        if (request.url() != null) {
            resource.setUrl(request.url().trim());
        }
        if (request.type() != null) {
            resource.setType(request.type());
        }
        if (request.isPrimary() != null) {
            resource.setIsPrimary(request.isPrimary());
            if (request.isPrimary()) {
                demoteOtherPrimaries(resource.getProduct(), resourceId);
            }
        }

        adminEventService.record(actorUserId, AdminEventType.RESOURCE_UPDATED,
                AdminTargetType.PRODUCT_RESOURCE, resourceId, "url=" + resource.getUrl());
        return AdminProductService.toResourceResponse(resource);
    }

    /**
     * Deletes the row.
     *
     * <p>No replacement primary is promoted: the product simply shows no thumbnail until an
     * administrator picks one. Guessing would put an image on the storefront that nobody chose, and a
     * missing thumbnail is at least visible.
     */
    @Transactional
    public void deleteResource(UUID actorUserId, UUID resourceId) {
        Resource resource = requireResource(resourceId);
        // Both, deliberately: Product maps resources with cascade ALL and no orphanRemoval, so
        // leaving the deleted child in the loaded collection risks a cascade re-persisting it on
        // flush, while removing it from the collection alone would not delete the row.
        resource.getProduct().getResources().remove(resource);
        resourceRepository.delete(resource);

        adminEventService.record(actorUserId, AdminEventType.RESOURCE_DELETED,
                AdminTargetType.PRODUCT_RESOURCE, resourceId,
                "product=" + resource.getProduct().getId() + " url=" + resource.getUrl());
    }

    private Resource requireResource(UUID resourceId) {
        return resourceRepository.findWithProductById(resourceId).orElseThrow(
                () -> new ResourceNotFoundException("Resource " + resourceId + " was not found"));
    }

    /**
     * Leaves at most one primary on the product.
     *
     * <p>No database constraint enforces this. Two primaries would make
     * {@code ProductRepository.findPrimaryThumbnails} return two rows for one product, and its
     * {@code Collectors.toMap} merge function then picks one arbitrarily — a thumbnail that changes
     * between requests.
     *
     * @param keepId the resource being promoted, or null when it does not exist yet
     */
    private static void demoteOtherPrimaries(Product product, UUID keepId) {
        product.getResources().stream()
                .filter(existing -> !existing.getId().equals(keepId))
                .filter(existing -> Boolean.TRUE.equals(existing.getIsPrimary()))
                .forEach(existing -> existing.setIsPrimary(false));
    }
}
```

- [ ] **Step 6: Write the controller**

`admin/AdminResourceController.java`:

```java
package com.mvp.ecommercebackend.admin;

import com.mvp.ecommercebackend.admin.dto.CreateResourceRequest;
import com.mvp.ecommercebackend.admin.dto.UpdateResourceRequest;
import com.mvp.ecommercebackend.auth.AuthenticatedUser;
import com.mvp.ecommercebackend.catalog.dto.ProductResourceDto;
import com.mvp.ecommercebackend.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Product images and attachments. */
@RestController
@RequestMapping("/api/admin")
@Tag(name = "Admin Catalog")
@SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME)
public class AdminResourceController {

    private final AdminResourceService adminResourceService;

    public AdminResourceController(AdminResourceService adminResourceService) {
        this.adminResourceService = adminResourceService;
    }

    @PostMapping("/products/{id}/resources")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Attach a resource to a product",
            description = "isPrimary defaults to false. Setting it true demotes the product's "
                    + "current primary in the same transaction.")
    public ProductResourceDto createResource(@AuthenticationPrincipal AuthenticatedUser principal,
                                             @PathVariable UUID id,
                                             @Valid @RequestBody CreateResourceRequest request) {
        return adminResourceService.createResource(principal.id(), id, request);
    }

    @PatchMapping("/resources/{id}")
    @Operation(summary = "Update a resource",
            description = "Partial: an omitted field is left unchanged. Promoting one to primary "
                    + "demotes the others.")
    public ProductResourceDto updateResource(@AuthenticationPrincipal AuthenticatedUser principal,
                                             @PathVariable UUID id,
                                             @Valid @RequestBody UpdateResourceRequest request) {
        return adminResourceService.updateResource(principal.id(), id, request);
    }

    @DeleteMapping("/resources/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a resource",
            description = "A hard delete, unlike products and variants: nothing snapshots a "
                    + "resource. No replacement primary is promoted.")
    public void deleteResource(@AuthenticationPrincipal AuthenticatedUser principal,
                               @PathVariable UUID id) {
        adminResourceService.deleteResource(principal.id(), id);
    }
}
```

- [ ] **Step 7: Register the new paths in `OpenApiIT`**

Add to `EVERY_PATH`:

```java
            "/api/admin/products/{id}/resources",
            "/api/admin/resources/{id}",
```

- [ ] **Step 8: Run the tests to verify they pass**

Run: `mvn verify -Dit.test='AdminResourceIT,AdminProductIT,OpenApiIT'`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/mvp/ecommercebackend/admin \
        src/main/java/com/mvp/ecommercebackend/catalog/repository/ResourceRepository.java \
        src/test/java/com/mvp/ecommercebackend/admin/AdminResourceIT.java \
        src/test/java/com/mvp/ecommercebackend/config/OpenApiIT.java
git commit -m "feat(admin): product resource management"
```

---

### Task 9: Make archiving actually hide things

**Files:**
- Modify: `src/main/java/com/mvp/ecommercebackend/catalog/ProductSpecifications.java` (add `notArchived`)
- Modify: `src/main/java/com/mvp/ecommercebackend/catalog/ProductService.java` (`filters`, `getProductById`)
- Modify: `src/main/java/com/mvp/ecommercebackend/commerce/CartService.java` (`addItem`, `updateItem`)
- Modify: `src/main/java/com/mvp/ecommercebackend/commerce/repository/CartItemRepository.java` (`CheckoutLine`)
- Modify: `src/main/java/com/mvp/ecommercebackend/commerce/OrderService.java` (`placeOrder`)
- Test: `src/test/java/com/mvp/ecommercebackend/admin/AdminArchiveIT.java`

**Interfaces:**
- Consumes: the archive endpoints from Tasks 4 and 5; `Product.getArchivedAt()`, `ProductVariant.getArchivedAt()` from Task 1.
- Produces:
  - `ProductSpecifications.notArchived()` — package-private, used by `ProductService.filters`.
  - `CartItemRepository.CheckoutLine.getProductArchivedAt()` and `getVariantArchivedAt()`.
  - `CartService.requireSellable(ProductVariant)` — private static.
- No new endpoints, so `OpenApiIT.EVERY_PATH` is untouched.

**This is the task that makes the previous five worth anything.** Archiving that does not hide is just a column. The spec names five paths; all five change here, in one commit, because leaving any of them out means a half-archived product visible in one place and gone from another.

**Decisions this task locks in:**

- An archived **product** hides regardless of its variants' flags. A variant's own `archivedAt` is independent, so restoring a product does not resurrect a variant retired separately.
- Public product detail answers **404** for an archived product, and omits archived variants from a live product's response. The variant list is what a storefront renders as buyable options, so an archived one appearing there would be an option that cannot be bought.
- `CartService.updateItem` gets the same guard as `addItem`. The spec's table names only `addItem`, but both call `requireStock` and both are ways to put more of a variant in a cart; the check goes into a `requireSellable` sibling called from both rather than being written twice.
- Checkout re-checks rather than trusting the cart. This is the case most easily missed: a variant can be archived *after* it is in a cart, and cart contents are not revalidated by anything else.
- The archive flags reach checkout as **two extra scalars on `CheckoutLine`**, not by loading the variant entity. `findCheckoutLines`'s docstring explains why that projection exists at all: loading `ProductVariant` would put a stale copy in the persistence context, and the later `SELECT … FOR UPDATE` would take the lock but hand back the pre-lock stock figure. Adding scalar columns keeps that property.
- Existing orders referencing an archived product still render. Nothing changes there and nothing needs to: `order_items` snapshots the product name, colour, size, and unit price, and its foreign keys are `ON DELETE SET NULL`. The test asserts it anyway, because acceptance criterion 3 says so and a future refactor could break it silently.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/mvp/ecommercebackend/admin/AdminArchiveIT.java`:

```java
package com.mvp.ecommercebackend.admin;

import com.mvp.ecommercebackend.auth.entity.User;
import com.mvp.ecommercebackend.catalog.entity.CategoryType;
import com.mvp.ecommercebackend.catalog.entity.Product;
import com.mvp.ecommercebackend.catalog.entity.ProductVariant;
import com.mvp.ecommercebackend.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Acceptance criteria 3, 4, and 5: archiving hides a product from customers, an archived variant
 * cannot be bought — including one archived after it was put in a cart — and restoring reverses it
 * without resurrecting separately archived variants.
 */
class AdminArchiveIT extends AbstractIntegrationTest {

    private static final String ADDRESS = """
            {"recipientName":"Ada Lovelace","line1":"12 Analytical Way","city":"London",
             "postalCode":"E1 6AN","country":"GB"}
            """;

    private String admin;
    private String customer;
    private Product product;
    private ProductVariant variant;

    @BeforeEach
    void setUp() {
        admin = bearer(testData.createAdmin("archive-admin@example.com", "correct-horse-battery"));
        customer = bearer(testData.createCustomer("shopper@example.com", "correct-horse-battery"));
        CategoryType type = catalogData.createCategoryWithType("Footwear", "Running Shoes");
        product = catalogData.createProduct(type, "Trail Runner", "129.99");
        catalogData.addImage(product, "https://cdn.example.com/front.jpg", true);
        variant = catalogData.createVariant(product, "Black", "42", 10);
    }

    /** Criterion 3, first half. */
    @Test
    void hidesAnArchivedProductFromThePublicListingAndDetailView() throws Exception {
        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        archiveProduct();

        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.content").isEmpty());
        mockMvc.perform(get("/api/products/" + product.getId()))
                .andExpect(status().isNotFound());
    }

    /** Criterion 3, second half: order history survives archiving. */
    @Test
    void leavesAnExistingOrdersLineItemsIntactAfterTheProductIsArchived() throws Exception {
        UUID addressId = addAddress();
        addToCart(variant, 1);
        String orderId = placeOrder(addressId);

        archiveProduct();

        mockMvc.perform(get("/api/me/orders/" + orderId)
                        .header(HttpHeaders.AUTHORIZATION, customer))
                .andExpect(status().isOk())
                // Rendered from the snapshot columns on order_items, not from the catalogue.
                .andExpect(jsonPath("$.items[0].productName").value("Trail Runner"))
                .andExpect(jsonPath("$.items[0].variantColor").value("Black"))
                .andExpect(jsonPath("$.items[0].unitPrice").value(129.99));
    }

    @Test
    void omitsAnArchivedVariantFromAProductsPublicDetailView() throws Exception {
        ProductVariant other = catalogData.createVariant(product, "Black", "43", 4);
        archiveVariant(other);

        mockMvc.perform(get("/api/products/" + product.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.variants.length()").value(1))
                .andExpect(jsonPath("$.variants[0].size").value("42"));
    }

    /** Criterion 4, first half. */
    @Test
    void refusesToAddAnArchivedVariantToACart() throws Exception {
        archiveVariant(variant);

        mockMvc.perform(post("/api/me/cart/items")
                        .header(HttpHeaders.AUTHORIZATION, customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variantId\":\"" + variant.getId() + "\",\"quantity\":1}"))
                .andExpect(status().isConflict());
    }

    @Test
    void refusesToAddAVariantOfAnArchivedProductToACart() throws Exception {
        archiveProduct();

        mockMvc.perform(post("/api/me/cart/items")
                        .header(HttpHeaders.AUTHORIZATION, customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variantId\":\"" + variant.getId() + "\",\"quantity\":1}"))
                .andExpect(status().isConflict());
    }

    /**
     * Criterion 4, second half, and the case the spec calls easiest to miss: archived *after* it was
     * already in the cart, so placement cannot trust the cart's contents.
     */
    @Test
    void refusesToCheckOutAVariantArchivedAfterItWasAddedToTheCart() throws Exception {
        UUID addressId = addAddress();
        addToCart(variant, 1);

        archiveVariant(variant);

        mockMvc.perform(post("/api/me/orders")
                        .header(HttpHeaders.AUTHORIZATION, customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shippingAddressId\":\"" + addressId + "\"}"))
                .andExpect(status().isConflict());

        // Nothing was taken: no order, stock untouched, and the cart is left for the customer to fix.
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM orders", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM product_variants WHERE id = ?",
                Integer.class, variant.getId())).isEqualTo(10);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM cart_items", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void refusesToCheckOutAVariantWhoseProductWasArchivedAfterItWasAdded() throws Exception {
        UUID addressId = addAddress();
        addToCart(variant, 1);

        archiveProduct();

        mockMvc.perform(post("/api/me/orders")
                        .header(HttpHeaders.AUTHORIZATION, customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shippingAddressId\":\"" + addressId + "\"}"))
                .andExpect(status().isConflict());
    }

    /** Criterion 5. */
    @Test
    void restoringAProductReturnsItWithoutResurrectingASeparatelyArchivedVariant() throws Exception {
        ProductVariant other = catalogData.createVariant(product, "Black", "43", 4);
        archiveVariant(other);
        archiveProduct();

        mockMvc.perform(post("/api/admin/products/" + product.getId() + "/restore")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
        // The variant retired on its own stays retired.
        mockMvc.perform(get("/api/products/" + product.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.variants.length()").value(1))
                .andExpect(jsonPath("$.variants[0].size").value("42"));

        mockMvc.perform(post("/api/admin/variants/" + other.getId() + "/restore")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/products/" + product.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.variants.length()").value(2));
    }

    @Test
    void keepsTheCategoryFilterAndSearchWorkingWithTheArchiveFilterApplied() throws Exception {
        CategoryType type = catalogData.addCategoryType(product.getCategory(), "Trail Shoes");
        Product second = catalogData.createProduct(type, "Road Runner", "89.99");
        archive(second);

        mockMvc.perform(get("/api/products?q=runner"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].name").value("Trail Runner"));
        mockMvc.perform(get("/api/products?categoryId=" + product.getCategory().getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    private void archiveProduct() throws Exception {
        archive(product);
    }

    private void archive(Product target) throws Exception {
        mockMvc.perform(delete("/api/admin/products/" + target.getId())
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isNoContent());
    }

    private void archiveVariant(ProductVariant target) throws Exception {
        mockMvc.perform(delete("/api/admin/variants/" + target.getId())
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isNoContent());
    }

    private void addToCart(ProductVariant target, int quantity) throws Exception {
        mockMvc.perform(post("/api/me/cart/items")
                        .header(HttpHeaders.AUTHORIZATION, customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variantId\":\"" + target.getId()
                                + "\",\"quantity\":" + quantity + "}"))
                .andExpect(status().isOk());
    }

    private UUID addAddress() throws Exception {
        String location = mockMvc.perform(post("/api/me/addresses")
                        .header(HttpHeaders.AUTHORIZATION, customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ADDRESS))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader(HttpHeaders.LOCATION);
        return UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
    }

    private String placeOrder(UUID addressId) throws Exception {
        String body = mockMvc.perform(post("/api/me/orders")
                        .header(HttpHeaders.AUTHORIZATION, customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shippingAddressId\":\"" + addressId + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asString();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn verify -Dit.test=AdminArchiveIT`
Expected: FAIL on most tests — archiving sets a column that nothing reads yet, so the archived product is still listed, still has a reachable detail view, and can still be bought.

Check the public order-detail JSON field names (`items[0].productName`, `variantColor`, `unitPrice`) and the `POST /api/me/orders` status against `OrderCheckoutIT` before implementing, and match whatever that test already asserts.

- [ ] **Step 3: Hide archived products from the public listing**

In `ProductSpecifications`, add:

```java
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
```

In `ProductService.filters`, seed the list with it:

```java
    private Specification<Product> filters(UUID categoryId, UUID categoryTypeId, String searchTerm) {
        // Always first and never optional: archived products are not for sale.
        List<Specification<Product>> filters = new ArrayList<>();
        filters.add(ProductSpecifications.notArchived());
        if (categoryId != null) {
            filters.add(ProductSpecifications.inCategory(categoryId));
        }
        if (categoryTypeId != null) {
            filters.add(ProductSpecifications.inCategoryType(categoryTypeId));
        }
        if (searchTerm != null && !searchTerm.isBlank()) {
            filters.add(ProductSpecifications.nameContains(searchTerm.trim()));
        }
        return Specification.allOf(filters);
    }
```

The `filters.isEmpty()` branch and its `Specification.unrestricted()` fallback go away: the list now always holds at least one predicate. Leave the `Specification.unrestricted()` import removal to the compiler warning if there is one — it is no longer referenced here.

- [ ] **Step 4: 404 the archived product and hide archived variants on the public detail view**

In `ProductService.getProductById`:

```java
    @Transactional(readOnly = true)
    public ProductDto getProductById(UUID id) {
        Product product = productRepository.findWithCategoriesById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product Not Found!"));
        // 404, not 410 or an empty body: to a customer an archived product does not exist, and the
        // same message an unknown id gets means the endpoint does not leak that it ever did.
        if (product.getArchivedAt() != null) {
            throw new ResourceNotFoundException("Product Not Found!");
        }
        ProductDto productDto = productMapper.mapProductToDto(product);
        productDto.setCategoryId(product.getCategory().getId());
        productDto.setCategoryName(product.getCategory().getName());
        productDto.setCategoryTypeId(product.getCategoryType().getId());
        productDto.setCategoryTypeName(product.getCategoryType().getName());
        // Archived variants are filtered out here rather than in the mapper: this list is what a
        // storefront renders as buyable options, so an unbuyable one has no business being in it.
        productDto.setVariants(productMapper.mapProductVariantListToDto(
                product.getProductVariants().stream()
                        .filter(variant -> variant.getArchivedAt() == null)
                        .toList()));
        productDto.setProductResources(
                productMapper.mapProductResourcesListDto(product.getResources()));
        return productDto;
    }
```

- [ ] **Step 5: Reject archived variants at cart-add and cart-update**

In `CartService`, add beside `requireStock`:

```java
    /**
     * Rejects a variant that is no longer for sale.
     *
     * <p>Called from both {@code addItem} and {@code updateItem}: both are ways to put more of a
     * variant in a cart, so guarding only one leaves the other open.
     *
     * <p>An archived product hides its variants regardless of their own flags, which is why both are
     * checked. {@code variant.getProduct()} costs no query — {@code findWithProductById} join-fetches
     * it, and {@code updateItem} reaches it through a variant already in the persistence context.
     */
    private static void requireSellable(ProductVariant variant) {
        if (variant.getArchivedAt() != null || variant.getProduct().getArchivedAt() != null) {
            throw new InvalidOrderStateException(
                    "Variant " + variant.getId() + " is no longer available");
        }
    }
```

Call it from `addItem`, immediately after the variant is loaded and before the stock check:

```java
        ProductVariant variant = variantRepository.findWithProductById(request.variantId())
                .orElseThrow(() -> new ResourceNotFoundException("Product Variant Not Found!"));
        requireSellable(variant);
```

and from `updateItem`, before its stock check:

```java
        CartItem item = requireOwnedItem(userId, itemId);
        requireSellable(item.getVariant());
        requireStock(item.getVariant(), request.quantity());
```

Add the `InvalidOrderStateException` import if `CartService` does not already have it.

- [ ] **Step 6: Re-check at checkout**

Add the two flags to `CartItemRepository.CheckoutLine` and its query. Scalars, not the entity — see the existing docstring on `findCheckoutLines` for why loading `ProductVariant` here would defeat the row lock.

```java
            select variant.id as variantId,
                   product.id as productId,
                   product.name as productName,
                   product.price as unitPrice,
                   variant.color as color,
                   variant.size as size,
                   item.quantity as quantity,
                   product.archivedAt as productArchivedAt,
                   variant.archivedAt as variantArchivedAt
            from CartItem item
            join item.variant variant
            join variant.product product
            where item.cart.user.id = :userId
            order by item.createdAt, item.id
```

and on the interface:

```java
        int getQuantity();

        /**
         * Archive flags, so checkout can refuse a variant retired after it was added to the cart.
         * Scalars for the same reason as everything else here: nothing may be cached to go stale.
         */
        Instant getProductArchivedAt();

        Instant getVariantArchivedAt();
```

Add the `java.time.Instant` import.

In `OrderService.placeOrder`, inside the line loop, immediately before `takeStock`:

```java
            // Re-checked here and not only at cart-add: a variant can be archived while it sits in a
            // cart, and nothing else revalidates cart contents.
            requireSellable(line);
            takeStock(variant, line.getQuantity(), line.getProductName());
```

and add the sibling to `requirePendingPayment`:

```java
    private static void requireSellable(CartItemRepository.CheckoutLine line) {
        if (line.getVariantArchivedAt() != null || line.getProductArchivedAt() != null) {
            // The product name, like takeStock: the customer is looking at a checkout page listing
            // several items and needs to know which one to remove.
            throw new InvalidOrderStateException(
                    line.getProductName() + " is no longer available");
        }
    }
```

The whole method is `@Transactional`, so throwing here rolls back the stock decrements taken for earlier lines — which is why the test can assert that stock is untouched.

- [ ] **Step 7: Run the tests to verify they pass**

Run: `mvn verify`
Expected: PASS, the whole suite. This step runs everything rather than a selection on purpose: it changes four existing production classes, and the tests most likely to break are the ones already covering them — `ProductControllerIT`, `ProductServiceTest`, `CartControllerIT`, `OrderCheckoutIT`, `OrderConcurrencyIT`.

If an existing test fails, read it before changing anything. A fixture that now needs a non-archived variant is a legitimate fixture update. A test asserting an archived product is still listed would mean this task contradicts the spec — that will not happen, since nothing set `archived_at` before Task 1.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/mvp/ecommercebackend/catalog \
        src/main/java/com/mvp/ecommercebackend/commerce \
        src/test/java/com/mvp/ecommercebackend/admin/AdminArchiveIT.java
git commit -m "feat: hide archived products and variants from customers"
```

---

### Task 10: Order administration and the fulfilment lifecycle

**Files:**
- Create: `src/main/java/com/mvp/ecommercebackend/admin/dto/AdminOrderResponse.java`
- Create: `src/main/java/com/mvp/ecommercebackend/admin/dto/AdminOrderSummaryResponse.java`
- Create: `src/main/java/com/mvp/ecommercebackend/admin/dto/ShipOrderRequest.java`
- Create: `src/main/java/com/mvp/ecommercebackend/admin/AdminOrderSpecifications.java`
- Create: `src/main/java/com/mvp/ecommercebackend/admin/AdminOrderService.java`
- Create: `src/main/java/com/mvp/ecommercebackend/admin/AdminOrderController.java`
- Modify: `src/main/java/com/mvp/ecommercebackend/commerce/repository/OrderRepository.java`
- Modify: `src/main/java/com/mvp/ecommercebackend/commerce/OrderService.java` (`requireStatus`, `cancelAsAdministrator`)
- Modify: `src/test/java/com/mvp/ecommercebackend/config/OpenApiIT.java`
- Test: `src/test/java/com/mvp/ecommercebackend/admin/AdminOrderFulfillmentIT.java`

**Interfaces:**
- Consumes: `OrderStatus.SHIPPED`/`DELIVERED` and `Order.setShippedAt`/`setDeliveredAt`/`setTrackingReference` from Task 1; `AdminEventService.record(...)`; `Clock`.
- Produces:
  - `OrderService.requireStatus(Order, OrderStatus expected, String attemptedAction)` — `public static void`, the spec's sibling of `requirePendingPayment`.
  - `OrderService.cancelAsAdministrator(UUID orderId)` returning `OrderResponse`.
  - `OrderRepository.findWithItemsById(UUID)`, and `OrderRepository extends JpaSpecificationExecutor<Order>`.
  - `AdminOrderResponse`, `AdminOrderSummaryResponse`, `ShipOrderRequest(String trackingReference)`.
  - Endpoints `/api/admin/orders`, `/api/admin/orders/{id}`, `/api/admin/orders/{id}/ship`, `/api/admin/orders/{id}/deliver`, `/api/admin/orders/{id}/cancel`.

**Decisions this task locks in:**

- The state machine is `PENDING_PAYMENT → PAID → SHIPPED → DELIVERED`, with `PENDING_PAYMENT → CANCELLED`. `SHIPPED` and `DELIVERED` are terminal; there is no un-ship, because correcting a mis-shipment is a refund and refunds do not exist yet. `PAID → CANCELLED` stays refused for the same reason.
- Transitions are enforced **in the service**, not only by `ck_orders_status`. The CHECK constraint says which values are legal, not which moves are; shipping an unpaid order writes a value the constraint happily accepts.
- `requirePendingPayment` is **refactored to delegate** to the new `requireStatus` rather than sitting alongside it. Two functions that both answer "is this order in state X" would drift.
- Admin cancel **reuses `OrderService`'s stock-returning path** rather than reimplementing it. `cancel` is refactored to extract the restock, and `cancelAsAdministrator` differs only in dropping the owner scope. Duplicating the lock-and-restock loop in `admin` would mean two places to get the lock ordering right.
- The customer-facing `OrderResponse` is **not extended** with `trackingReference`, `shippedAt`, or `deliveredAt`. The spec's "Changes to existing code" table does not list it, and this slice is admin-only. A customer does still see `status` move to `SHIPPED` and `DELIVERED`, because that field already exists — only the tracking reference is admin-visible for now. Worth a follow-up slice; not worth widening this one.
- `AdminOrderResponse` names the customer (`userId`, `userEmail`). An administrator looking at an order needs to know whose it is, and the customer-facing shape must never carry it.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/mvp/ecommercebackend/admin/AdminOrderFulfillmentIT.java`:

```java
package com.mvp.ecommercebackend.admin;

import com.mvp.ecommercebackend.auth.entity.User;
import com.mvp.ecommercebackend.catalog.entity.CategoryType;
import com.mvp.ecommercebackend.catalog.entity.Product;
import com.mvp.ecommercebackend.catalog.entity.ProductVariant;
import com.mvp.ecommercebackend.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Acceptance criterion 8: the fulfilment lifecycle, and every illegal move refused with 409. */
class AdminOrderFulfillmentIT extends AbstractIntegrationTest {

    private static final String ADDRESS = """
            {"recipientName":"Ada Lovelace","line1":"12 Analytical Way","city":"London",
             "postalCode":"E1 6AN","country":"GB"}
            """;

    private String admin;
    private String customer;
    private User shopper;
    private ProductVariant variant;

    @BeforeEach
    void setUp() {
        admin = bearer(testData.createAdmin("order-admin@example.com", "correct-horse-battery"));
        shopper = testData.createCustomer("shopper@example.com", "correct-horse-battery");
        customer = bearer(shopper);
        CategoryType type = catalogData.createCategoryWithType("Footwear", "Running Shoes");
        Product product = catalogData.createProduct(type, "Trail Runner", "129.99");
        variant = catalogData.createVariant(product, "Black", "42", 10);
    }

    @Test
    void movesAPaidOrderThroughShippingToDelivery() throws Exception {
        UUID orderId = paidOrder();

        mockMvc.perform(post("/api/admin/orders/" + orderId + "/ship")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"trackingReference":"RM123456789GB"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SHIPPED"))
                .andExpect(jsonPath("$.trackingReference").value("RM123456789GB"))
                .andExpect(jsonPath("$.shippedAt").isNotEmpty())
                .andExpect(jsonPath("$.deliveredAt").doesNotExist());

        mockMvc.perform(post("/api/admin/orders/" + orderId + "/deliver")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"))
                .andExpect(jsonPath("$.deliveredAt").isNotEmpty())
                // The tracking reference and shipping timestamp survive delivery.
                .andExpect(jsonPath("$.trackingReference").value("RM123456789GB"))
                .andExpect(jsonPath("$.shippedAt").isNotEmpty());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT tracking_reference FROM orders", String.class)).isEqualTo("RM123456789GB");
        // The customer sees the status move even though tracking stays admin-only for now.
        mockMvc.perform(get("/api/me/orders/" + orderId)
                        .header(HttpHeaders.AUTHORIZATION, customer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"));
    }

    @Test
    void refusesToShipAnUnpaidOrder() throws Exception {
        UUID orderId = unpaidOrder();

        mockMvc.perform(post("/api/admin/orders/" + orderId + "/ship")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"trackingReference":"RM123456789GB"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Conflict"));

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM orders", String.class))
                .isEqualTo("PENDING_PAYMENT");
    }

    @Test
    void refusesToDeliverAnOrderThatWasNeverShipped() throws Exception {
        UUID orderId = paidOrder();

        mockMvc.perform(post("/api/admin/orders/" + orderId + "/deliver")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isConflict());
    }

    @Test
    void refusesToShipTwiceOrToCancelAfterShipping() throws Exception {
        UUID orderId = paidOrder();
        ship(orderId);

        mockMvc.perform(post("/api/admin/orders/" + orderId + "/ship")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"trackingReference":"RM999999999GB"}
                                """))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/admin/orders/" + orderId + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isConflict());
    }

    @Test
    void refusesToCancelAPaidOrderBecauseThatWouldBeARefund() throws Exception {
        UUID orderId = paidOrder();

        mockMvc.perform(post("/api/admin/orders/" + orderId + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isConflict());

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM orders", String.class))
                .isEqualTo("PAID");
    }

    @Test
    void cancelsAnUnpaidOrderAndReturnsItsStock() throws Exception {
        UUID orderId = unpaidOrder();
        assertThat(stock()).isEqualTo(8);

        mockMvc.perform(post("/api/admin/orders/" + orderId + "/cancel")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledAt").isNotEmpty());

        assertThat(stock()).isEqualTo(10);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT action FROM admin_events", String.class)).isEqualTo("ORDER_CANCELLED");
    }

    @Test
    void showsAnyCustomersOrderWithTheirIdentity() throws Exception {
        UUID orderId = unpaidOrder();

        mockMvc.perform(get("/api/admin/orders/" + orderId)
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userEmail").value("shopper@example.com"))
                .andExpect(jsonPath("$.userId").value(shopper.getId().toString()))
                .andExpect(jsonPath("$.items[0].productName").value("Trail Runner"))
                .andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    void filtersTheOrderListByStatusCustomerAndOrderNumber() throws Exception {
        UUID pending = unpaidOrder();
        UUID paid = paidOrder();
        String pendingNumber = orderNumberOf(pending);

        mockMvc.perform(get("/api/admin/orders").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));

        mockMvc.perform(get("/api/admin/orders?status=PAID")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(paid.toString()));

        mockMvc.perform(get("/api/admin/orders?userId=" + shopper.getId())
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));

        mockMvc.perform(get("/api/admin/orders?orderNumber=" + pendingNumber)
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(pending.toString()));
    }

    @Test
    void filtersTheOrderListByPlacementWindow() throws Exception {
        unpaidOrder();

        // from is inclusive and to is exclusive, so a window ending now excludes an order placed now.
        mockMvc.perform(get("/api/admin/orders?from=2000-01-01T00:00:00Z&to=2099-01-01T00:00:00Z")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        mockMvc.perform(get("/api/admin/orders?from=2099-01-01T00:00:00Z")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void rejectsAnUnknownStatusAnUnknownSortAndAnOverlongTrackingReference() throws Exception {
        mockMvc.perform(get("/api/admin/orders?status=SHIPPING")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/admin/orders?sort=user")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isBadRequest());

        UUID orderId = paidOrder();
        mockMvc.perform(post("/api/admin/orders/" + orderId + "/ship")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"trackingReference\":\"" + "R".repeat(101) + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void answers404ForAnUnknownOrder() throws Exception {
        mockMvc.perform(get("/api/admin/orders/" + UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/admin/orders/" + UUID.randomUUID() + "/deliver")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isNotFound());
    }

    private void ship(UUID orderId) throws Exception {
        mockMvc.perform(post("/api/admin/orders/" + orderId + "/ship")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"trackingReference":"RM123456789GB"}
                                """))
                .andExpect(status().isOk());
    }

    /** An order for two units, left in PENDING_PAYMENT. */
    private UUID unpaidOrder() throws Exception {
        UUID addressId = addAddress();
        mockMvc.perform(post("/api/me/cart/items")
                        .header(HttpHeaders.AUTHORIZATION, customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variantId\":\"" + variant.getId() + "\",\"quantity\":2}"))
                .andExpect(status().isOk());
        String body = mockMvc.perform(post("/api/me/orders")
                        .header(HttpHeaders.AUTHORIZATION, customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"shippingAddressId\":\"" + addressId + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("id").asString());
    }

    private UUID paidOrder() throws Exception {
        UUID orderId = unpaidOrder();
        mockMvc.perform(post("/api/me/orders/" + orderId + "/pay")
                        .header(HttpHeaders.AUTHORIZATION, customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"paymentMethodToken":"tok_visa"}
                                """))
                .andExpect(status().isOk());
        return orderId;
    }

    private UUID addAddress() throws Exception {
        String location = mockMvc.perform(post("/api/me/addresses")
                        .header(HttpHeaders.AUTHORIZATION, customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ADDRESS))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader(HttpHeaders.LOCATION);
        return UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
    }

    private String orderNumberOf(UUID orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT order_number FROM orders WHERE id = ?", String.class, orderId);
    }

    private Integer stock() {
        return jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM product_variants WHERE id = ?",
                Integer.class, variant.getId());
    }
}
```

Check `POST /api/me/orders/{id}/pay`'s request field name and the accepted token against `OrderCheckoutIT` — `SimulatedPaymentGateway` approves anything except `tok_declined`, but the field name must match `PayOrderRequest`.

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn verify -Dit.test=AdminOrderFulfillmentIT`
Expected: FAIL — no `/api/admin/orders` handler.

- [ ] **Step 3: Extend the repository**

In `OrderRepository`, add `JpaSpecificationExecutor<Order>` to the `extends` clause (importing it), and add:

```java
    /**
     * One order with its lines, unscoped by owner.
     *
     * <p>The sibling of {@link #findWithItemsByIdAndUserId} for the admin paths, where the caller is
     * not the owner. Kept as a separate method rather than making the owner nullable: an unscoped
     * lookup is exactly the mistake the scoped one exists to prevent, so it should be impossible to
     * reach by accident.
     */
    @EntityGraph(attributePaths = "items")
    Optional<Order> findWithItemsById(UUID id);
```

Do **not** add an entity graph to a paged `Specification` query — the existing docstring on `findByUserId` explains why collection fetching plus `Pageable` paginates in memory. The admin summary rows do not need the lines either.

- [ ] **Step 4: Add the state guard and the admin cancel to `OrderService`**

Replace `requirePendingPayment` with a delegating pair:

```java
    /**
     * Refuses an action unless the order is in {@code expected}.
     *
     * <p>Enforced here and not left to {@code ck_orders_status}: the CHECK constraint says which
     * values are legal, not which moves are, and it would happily accept SHIPPED on an unpaid order.
     *
     * <p>Public and static so {@code AdminOrderService} can share it. Two functions answering "is
     * this order in state X" would drift apart.
     */
    public static void requireStatus(Order order, OrderStatus expected, String attemptedAction) {
        if (order.getStatus() != expected) {
            throw new InvalidOrderStateException("Order " + order.getOrderNumber() + " is "
                    + order.getStatus() + " and cannot be " + attemptedAction);
        }
    }

    private static void requirePendingPayment(Order order, String attemptedAction) {
        requireStatus(order, OrderStatus.PENDING_PAYMENT, attemptedAction);
    }
```

Then split `cancel` so the restock is reusable:

```java
    @Transactional
    public OrderResponse cancel(UUID userId, UUID orderId) {
        return cancelOrder(requireOwnedOrder(userId, orderId));
    }

    /**
     * Cancels any customer's unpaid order.
     *
     * <p>Lives here rather than in {@code AdminOrderService} so the stock-returning loop and its lock
     * ordering exist in exactly one place. The only difference from {@link #cancel} is the absent
     * owner scope; the refusal to cancel a paid order is identical, because a refund needs a record
     * of who authorised it and how much came back.
     */
    @Transactional
    public OrderResponse cancelAsAdministrator(UUID orderId) {
        Order order = orderRepository.findWithItemsById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order Not Found!"));
        return cancelOrder(order);
    }

    private OrderResponse cancelOrder(Order order) {
        requirePendingPayment(order, "cancelled");
        restoreStock(order);
        order.setStatus(OrderStatus.CANCELLED);
        order.setCancelledAt(clock.instant());
        return toResponse(orderRepository.saveAndFlush(order));
    }

    /** Puts an unpaid order's units back on the shelf, under the same lock checkout uses. */
    private void restoreStock(Order order) {
        List<UUID> variantIds = order.getItems().stream()
                .map(OrderItem::getVariantId)
                .filter(Objects::nonNull)
                .toList();
        Map<UUID, ProductVariant> locked = lockVariants(variantIds);
        for (OrderItem item : order.getItems()) {
            ProductVariant variant = locked.get(item.getVariantId());
            // Null when the variant has since been withdrawn: there is nothing left to restock, and
            // the order still records what was sold.
            if (variant != null) {
                variant.setStockQuantity(variant.getStockQuantity() + item.getQuantity());
            }
        }
    }
```

`toResponse` is already `private static`; leave it. `AdminOrderService` builds its own wider shape and does not need it.

- [ ] **Step 5: Write the DTOs**

`admin/dto/ShipOrderRequest.java`:

```java
package com.mvp.ecommercebackend.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param trackingReference the carrier's identifier. Required: an order marked shipped with no way to
 *                          trace the parcel gives a customer service agent nothing to work with.
 *                          Capped at 100 to match {@code orders.tracking_reference varchar(100)}.
 */
public record ShipOrderRequest(@NotBlank @Size(max = 100) String trackingReference) {
}
```

`admin/dto/AdminOrderSummaryResponse.java`:

```java
package com.mvp.ecommercebackend.admin.dto;

import com.mvp.ecommercebackend.commerce.entity.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One row of the administrative order listing. */
public record AdminOrderSummaryResponse(
        UUID id,
        String orderNumber,
        OrderStatus status,
        UUID userId,
        String userEmail,
        String currency,
        BigDecimal totalAmount,
        Instant placedAt,
        Instant paidAt,
        Instant shippedAt,
        Instant deliveredAt,
        Instant cancelledAt,
        String trackingReference) {
}
```

`admin/dto/AdminOrderResponse.java`:

```java
package com.mvp.ecommercebackend.admin.dto;

import com.mvp.ecommercebackend.commerce.dto.OrderItemResponse;
import com.mvp.ecommercebackend.commerce.dto.ShippingAddressResponse;
import com.mvp.ecommercebackend.commerce.entity.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The administrative order detail view.
 *
 * <p>Wider than the customer's {@code OrderResponse}: it names the customer and carries the
 * fulfilment fields. Those two things must not migrate onto the customer shape — {@code userEmail}
 * because it belongs to someone else's session, and the fulfilment fields because deciding what a
 * customer sees of shipping is its own slice.
 *
 * <p>{@code OrderItemResponse} and {@code ShippingAddressResponse} are reused rather than copied: an
 * order line looks the same to everyone, and a second identical record would drift.
 */
public record AdminOrderResponse(
        UUID id,
        String orderNumber,
        OrderStatus status,
        UUID userId,
        String userEmail,
        String currency,
        BigDecimal subtotalAmount,
        BigDecimal totalAmount,
        String paymentReference,
        String trackingReference,
        ShippingAddressResponse shippingAddress,
        List<OrderItemResponse> items,
        Instant placedAt,
        Instant paidAt,
        Instant shippedAt,
        Instant deliveredAt,
        Instant cancelledAt) {
}
```

Confirm `OrderItemResponse` and `ShippingAddressResponse` are `public` and in `commerce.dto` before importing them; they are used by `OrderResponse`, which a controller returns, so they should be.

- [ ] **Step 6: Write the specifications**

`admin/AdminOrderSpecifications.java`:

```java
package com.mvp.ecommercebackend.admin;

import com.mvp.ecommercebackend.commerce.entity.Order;
import com.mvp.ecommercebackend.commerce.entity.OrderStatus;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.UUID;

/** The optional filters behind {@code GET /api/admin/orders}. */
final class AdminOrderSpecifications {

    private AdminOrderSpecifications() {
    }

    static Specification<Order> hasStatus(OrderStatus status) {
        return (root, query, builder) -> builder.equal(root.get("status"), status);
    }

    static Specification<Order> placedBy(UUID userId) {
        return (root, query, builder) -> builder.equal(root.get("user").get("id"), userId);
    }

    /**
     * Exact match, case-insensitive. An order number is quoted by a customer over the phone, so it
     * arrives however they typed it — but it is an identifier, not a search term, so no wildcards.
     */
    static Specification<Order> hasOrderNumber(String orderNumber) {
        return (root, query, builder) ->
                builder.equal(builder.lower(root.get("orderNumber")), orderNumber.toLowerCase());
    }

    /** Inclusive of {@code from}. */
    static Specification<Order> placedAtOrAfter(Instant from) {
        return (root, query, builder) -> builder.greaterThanOrEqualTo(root.get("placedAt"), from);
    }

    /**
     * Exclusive of {@code to}, so a caller can page through consecutive windows without
     * double-counting an order that lands exactly on a boundary.
     */
    static Specification<Order> placedBefore(Instant to) {
        return (root, query, builder) -> builder.lessThan(root.get("placedAt"), to);
    }
}
```

- [ ] **Step 7: Write the service**

`admin/AdminOrderService.java`:

```java
package com.mvp.ecommercebackend.admin;

import com.mvp.ecommercebackend.admin.dto.AdminOrderResponse;
import com.mvp.ecommercebackend.admin.dto.AdminOrderSummaryResponse;
import com.mvp.ecommercebackend.admin.dto.ShipOrderRequest;
import com.mvp.ecommercebackend.admin.entity.AdminEventType;
import com.mvp.ecommercebackend.admin.entity.AdminTargetType;
import com.mvp.ecommercebackend.commerce.OrderService;
import com.mvp.ecommercebackend.commerce.dto.OrderItemResponse;
import com.mvp.ecommercebackend.commerce.dto.ShippingAddressResponse;
import com.mvp.ecommercebackend.commerce.entity.Order;
import com.mvp.ecommercebackend.commerce.entity.OrderItem;
import com.mvp.ecommercebackend.commerce.entity.OrderStatus;
import com.mvp.ecommercebackend.commerce.entity.ShippingAddressSnapshot;
import com.mvp.ecommercebackend.commerce.repository.OrderRepository;
import com.mvp.ecommercebackend.common.PageResponse;
import com.mvp.ecommercebackend.common.ResourceNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Order administration: read any customer's order, and move it through fulfilment.
 *
 * <p>The state machine is {@code PENDING_PAYMENT → PAID → SHIPPED → DELIVERED}, with
 * {@code PENDING_PAYMENT → CANCELLED}. SHIPPED and DELIVERED are terminal: there is no un-ship,
 * because correcting a mis-shipment is a refund and refunds do not exist yet.
 */
@Service
public class AdminOrderService {

    private final OrderRepository orderRepository;
    private final OrderService orderService;
    private final AdminEventService adminEventService;
    private final Clock clock;

    public AdminOrderService(OrderRepository orderRepository,
                             OrderService orderService,
                             AdminEventService adminEventService,
                             Clock clock) {
        this.orderRepository = orderRepository;
        this.orderService = orderService;
        this.adminEventService = adminEventService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminOrderSummaryResponse> list(OrderStatus status, UUID userId,
                                                        String orderNumber, Instant from, Instant to,
                                                        int page, int size, String sortProperty,
                                                        String direction) {
        PageRequest pageRequest = PageRequest.of(page, size,
                Sort.by(Sort.Direction.fromString(direction), sortProperty));
        Page<Order> orders = orderRepository.findAll(
                filters(status, userId, orderNumber, from, to), pageRequest);

        // Inside the transaction, so the lazy user association resolves. One extra query per row, on
        // a page of at most 100 admin rows; a projection would be premature here.
        List<AdminOrderSummaryResponse> rows = orders.getContent().stream()
                .map(AdminOrderService::toSummary)
                .toList();
        return PageResponse.of(orders, rows);
    }

    @Transactional(readOnly = true)
    public AdminOrderResponse getOrder(UUID orderId) {
        return toResponse(requireOrder(orderId));
    }

    /** {@code PAID → SHIPPED}. */
    @Transactional
    public AdminOrderResponse ship(UUID actorUserId, UUID orderId, ShipOrderRequest request) {
        Order order = requireOrder(orderId);
        OrderService.requireStatus(order, OrderStatus.PAID, "shipped");

        order.setStatus(OrderStatus.SHIPPED);
        order.setShippedAt(clock.instant());
        order.setTrackingReference(request.trackingReference().trim());

        adminEventService.record(actorUserId, AdminEventType.ORDER_SHIPPED, AdminTargetType.ORDER,
                orderId, "order=" + order.getOrderNumber()
                        + " tracking=" + order.getTrackingReference());
        return toResponse(orderRepository.saveAndFlush(order));
    }

    /** {@code SHIPPED → DELIVERED}, and the end of the line. */
    @Transactional
    public AdminOrderResponse deliver(UUID actorUserId, UUID orderId) {
        Order order = requireOrder(orderId);
        OrderService.requireStatus(order, OrderStatus.SHIPPED, "delivered");

        order.setStatus(OrderStatus.DELIVERED);
        order.setDeliveredAt(clock.instant());

        adminEventService.record(actorUserId, AdminEventType.ORDER_DELIVERED, AdminTargetType.ORDER,
                orderId, "order=" + order.getOrderNumber());
        return toResponse(orderRepository.saveAndFlush(order));
    }

    /**
     * Cancels an unpaid order and returns its stock.
     *
     * <p>Delegates to {@code OrderService} so the restock and its lock ordering live in one place. A
     * paid order is refused there, not here — the reason is the same for a customer and an
     * administrator: reversing a settled sale is a refund.
     */
    @Transactional
    public AdminOrderResponse cancel(UUID actorUserId, UUID orderId) {
        orderService.cancelAsAdministrator(orderId);

        Order order = requireOrder(orderId);
        adminEventService.record(actorUserId, AdminEventType.ORDER_CANCELLED, AdminTargetType.ORDER,
                orderId, "order=" + order.getOrderNumber());
        return toResponse(order);
    }

    private Order requireOrder(UUID orderId) {
        return orderRepository.findWithItemsById(orderId).orElseThrow(
                () -> new ResourceNotFoundException("Order " + orderId + " was not found"));
    }

    private static Specification<Order> filters(OrderStatus status, UUID userId, String orderNumber,
                                                Instant from, Instant to) {
        List<Specification<Order>> filters = new ArrayList<>();
        if (status != null) {
            filters.add(AdminOrderSpecifications.hasStatus(status));
        }
        if (userId != null) {
            filters.add(AdminOrderSpecifications.placedBy(userId));
        }
        if (orderNumber != null && !orderNumber.isBlank()) {
            filters.add(AdminOrderSpecifications.hasOrderNumber(orderNumber.trim()));
        }
        if (from != null) {
            filters.add(AdminOrderSpecifications.placedAtOrAfter(from));
        }
        if (to != null) {
            filters.add(AdminOrderSpecifications.placedBefore(to));
        }
        return filters.isEmpty() ? Specification.unrestricted() : Specification.allOf(filters);
    }

    private static AdminOrderSummaryResponse toSummary(Order order) {
        return new AdminOrderSummaryResponse(order.getId(), order.getOrderNumber(),
                order.getStatus(), order.getUser().getId(), order.getUser().getEmail(),
                order.getCurrency(), order.getTotalAmount(), order.getPlacedAt(), order.getPaidAt(),
                order.getShippedAt(), order.getDeliveredAt(), order.getCancelledAt(),
                order.getTrackingReference());
    }

    private static AdminOrderResponse toResponse(Order order) {
        List<OrderItem> items = new ArrayList<>(order.getItems());
        // A bag comes back in whatever order the database chose, so the stored position decides.
        items.sort(Comparator.comparingInt(OrderItem::getLineNumber));
        List<OrderItemResponse> lines = items.stream()
                .map(item -> new OrderItemResponse(item.getId(), item.getProductId(),
                        item.getVariantId(), item.getProductName(), item.getVariantColor(),
                        item.getVariantSize(), item.getUnitPrice(), item.getQuantity(),
                        item.getLineTotal()))
                .toList();

        ShippingAddressSnapshot address = order.getShippingAddress();
        return new AdminOrderResponse(order.getId(), order.getOrderNumber(), order.getStatus(),
                order.getUser().getId(), order.getUser().getEmail(), order.getCurrency(),
                order.getSubtotalAmount(), order.getTotalAmount(), order.getPaymentReference(),
                order.getTrackingReference(),
                new ShippingAddressResponse(address.getRecipientName(), address.getPhone(),
                        address.getLine1(), address.getLine2(), address.getCity(),
                        address.getState(), address.getPostalCode(), address.getCountry()),
                lines, order.getPlacedAt(), order.getPaidAt(), order.getShippedAt(),
                order.getDeliveredAt(), order.getCancelledAt());
    }
}
```

`cancel` re-reads the order after delegating because `cancelAsAdministrator` returns the customer-facing `OrderResponse`, which lacks the fulfilment fields and the customer's identity. Both calls run in this method's transaction, so the second read is served from the persistence context, not the database.

- [ ] **Step 8: Write the controller**

`admin/AdminOrderController.java`:

```java
package com.mvp.ecommercebackend.admin;

import com.mvp.ecommercebackend.admin.dto.AdminOrderResponse;
import com.mvp.ecommercebackend.admin.dto.AdminOrderSummaryResponse;
import com.mvp.ecommercebackend.admin.dto.ShipOrderRequest;
import com.mvp.ecommercebackend.auth.AuthenticatedUser;
import com.mvp.ecommercebackend.commerce.entity.OrderStatus;
import com.mvp.ecommercebackend.common.PageResponse;
import com.mvp.ecommercebackend.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/** Order administration and fulfilment. */
@RestController
@RequestMapping("/api/admin/orders")
@Tag(name = "Admin Orders")
@SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME)
public class AdminOrderController {

    private final AdminOrderService adminOrderService;

    public AdminOrderController(AdminOrderService adminOrderService) {
        this.adminOrderService = adminOrderService;
    }

    /**
     * @param status an {@code OrderStatus} name; an unknown value is a 400, because Spring cannot
     *               bind it to the enum
     * @param from   inclusive, ISO-8601 instant, e.g. 2026-08-01T00:00:00Z
     * @param to     exclusive, so consecutive windows do not double-count a boundary order
     */
    @GetMapping
    @Operation(summary = "List orders for administration",
            description = "Filters by status, customer, order number, and placement window. "
                    + "from is inclusive and to is exclusive.")
    public PageResponse<AdminOrderSummaryResponse> listOrders(
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) @Size(max = 20) String orderNumber,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "0")
            @Min(value = 0, message = "must not be negative") int page,
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "must be at least 1")
            @Max(value = 100, message = "must not exceed 100") int size,
            @RequestParam(defaultValue = "placedAt")
            @Pattern(regexp = "placedAt|totalAmount|orderNumber|status",
                    message = "must be one of placedAt, totalAmount, orderNumber, status")
            String sort,
            @RequestParam(defaultValue = "desc")
            @Pattern(regexp = "(?i)asc|desc", message = "must be asc or desc") String direction) {
        return adminOrderService.list(status, userId, orderNumber, from, to, page, size,
                sort, direction);
    }

    @GetMapping("/{id}")
    @Operation(summary = "One order, whoever placed it",
            description = "Includes the customer's identity and the fulfilment fields, neither of "
                    + "which appears on the customer-facing shape.")
    public AdminOrderResponse getOrder(@PathVariable UUID id) {
        return adminOrderService.getOrder(id);
    }

    @PostMapping("/{id}/ship")
    @Operation(summary = "Mark a paid order shipped",
            description = "PAID only; anything else answers 409. trackingReference is required.")
    public AdminOrderResponse shipOrder(@AuthenticationPrincipal AuthenticatedUser principal,
                                        @PathVariable UUID id,
                                        @Valid @RequestBody ShipOrderRequest request) {
        return adminOrderService.ship(principal.id(), id, request);
    }

    @PostMapping("/{id}/deliver")
    @Operation(summary = "Mark a shipped order delivered",
            description = "SHIPPED only; anything else answers 409. DELIVERED is terminal.")
    public AdminOrderResponse deliverOrder(@AuthenticationPrincipal AuthenticatedUser principal,
                                           @PathVariable UUID id) {
        return adminOrderService.deliver(principal.id(), id);
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel an unpaid order and return its stock",
            description = "PENDING_PAYMENT only. Cancelling a paid order would be a refund, which "
                    + "this API does not implement, so it answers 409.")
    public AdminOrderResponse cancelOrder(@AuthenticationPrincipal AuthenticatedUser principal,
                                          @PathVariable UUID id) {
        return adminOrderService.cancel(principal.id(), id);
    }
}
```

If `from`/`to` fail to bind an ISO-8601 string, add `@DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)` to both parameters. Spring's `InstantFormatter` should handle it unaided; confirm from the test run rather than adding the annotation pre-emptively.

- [ ] **Step 9: Register the new paths in `OpenApiIT`**

Add to `EVERY_PATH`:

```java
            "/api/admin/orders",
            "/api/admin/orders/{id}",
            "/api/admin/orders/{id}/ship",
            "/api/admin/orders/{id}/deliver",
            "/api/admin/orders/{id}/cancel",
```

- [ ] **Step 10: Run the tests to verify they pass**

Run: `mvn verify -Dit.test='AdminOrderFulfillmentIT,OrderCheckoutIT,OrderConcurrencyIT,OpenApiIT'`
Expected: PASS. The order tests are included because Step 4 refactored `OrderService.cancel`, which they cover.

- [ ] **Step 11: Commit**

```bash
git add src/main/java/com/mvp/ecommercebackend/admin \
        src/main/java/com/mvp/ecommercebackend/commerce \
        src/test/java/com/mvp/ecommercebackend/admin/AdminOrderFulfillmentIT.java \
        src/test/java/com/mvp/ecommercebackend/config/OpenApiIT.java
git commit -m "feat(admin): order administration and the fulfilment lifecycle"
```

---

### Task 11: Reading the audit trail

**Files:**
- Create: `src/main/java/com/mvp/ecommercebackend/admin/dto/AdminEventResponse.java`
- Create: `src/main/java/com/mvp/ecommercebackend/admin/AdminEventSpecifications.java`
- Create: `src/main/java/com/mvp/ecommercebackend/admin/AdminEventController.java`
- Modify: `src/main/java/com/mvp/ecommercebackend/admin/AdminEventService.java` (add the read method)
- Modify: `src/test/java/com/mvp/ecommercebackend/config/OpenApiIT.java`
- Test: `src/test/java/com/mvp/ecommercebackend/admin/AdminEventIT.java`

**Interfaces:**
- Consumes: `AdminEvent`, `AdminEventType`, `AdminTargetType`, `AdminEventRepository` (already a `JpaSpecificationExecutor<AdminEvent>`) from Task 2; every mutation endpoint from Tasks 3-10.
- Produces:
  - `AdminEventService.list(AdminTargetType targetType, UUID targetId, UUID actorUserId, AdminEventType action, int page, int size)` returning `PageResponse<AdminEventResponse>`.
  - `AdminEventResponse(UUID id, UUID actorUserId, String actorEmail, AdminEventType action, AdminTargetType targetType, UUID targetId, String detail, Instant createdAt)`.
  - Endpoint `/api/admin/events`.

**Decisions this task locks in:**

- Newest first, always. There is no `sort` parameter: an audit trail is read from the most recent action backwards, and letting a caller sort by `detail` or `action` serves no purpose while widening the surface.
- `actorEmail` is resolved and returned alongside `actorUserId`. A UUID alone forces a second lookup for the question the reader always has — who did this. `actorUserId` is `ON DELETE SET NULL`, so both fields can legitimately be null on an old row whose administrator has since been deleted; `actorEmail` is then null too, and the event still stands.
- No `from`/`to` window. `idx_admin_events_actor` covers `(actor_user_id, created_at DESC)`, paging is enough for the volumes here, and an unused filter is a maintenance cost. Add it when someone needs it.
- The controller is **read-only**. There is no path that writes or erases an event: an audit trail an administrator can edit records nothing.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/mvp/ecommercebackend/admin/AdminEventIT.java`:

```java
package com.mvp.ecommercebackend.admin;

import com.mvp.ecommercebackend.auth.entity.User;
import com.mvp.ecommercebackend.catalog.entity.CategoryType;
import com.mvp.ecommercebackend.catalog.entity.Product;
import com.mvp.ecommercebackend.catalog.entity.ProductVariant;
import com.mvp.ecommercebackend.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Acceptance criterion 9: every admin mutation writes exactly one audit row naming the acting
 * administrator, and a mutation that rolls back writes none.
 *
 * <p>The rollback case is the one that matters. {@code AdminEventService.record} deliberately joins
 * the caller's transaction rather than opening its own, so a failed mutation cannot leave behind a
 * row claiming it happened. A {@code REQUIRES_NEW} propagation would break exactly this test.
 */
class AdminEventIT extends AbstractIntegrationTest {

    private User adminUser;
    private String admin;
    private Product product;
    private ProductVariant variant;

    @BeforeEach
    void setUp() {
        adminUser = testData.createAdmin("audit-admin@example.com", "correct-horse-battery");
        admin = bearer(adminUser);
        CategoryType type = catalogData.createCategoryWithType("Footwear", "Running Shoes");
        product = catalogData.createProduct(type, "Trail Runner", "129.99");
        variant = catalogData.createVariant(product, "Black", "42", 10);
    }

    @Test
    void writesExactlyOneRowPerMutationNamingTheActingAdministrator() throws Exception {
        adjustStock(3);

        assertThat(countEvents()).isEqualTo(1);

        mockMvc.perform(get("/api/admin/events").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].action").value("STOCK_ADJUSTED"))
                .andExpect(jsonPath("$.content[0].targetType").value("PRODUCT_VARIANT"))
                .andExpect(jsonPath("$.content[0].targetId").value(variant.getId().toString()))
                .andExpect(jsonPath("$.content[0].actorUserId").value(adminUser.getId().toString()))
                .andExpect(jsonPath("$.content[0].actorEmail").value("audit-admin@example.com"))
                .andExpect(jsonPath("$.content[0].detail").value(
                        org.hamcrest.Matchers.containsString("delta=3")))
                .andExpect(jsonPath("$.content[0].createdAt").isNotEmpty());
    }

    @Test
    void writesNoRowWhenTheMutationIsRejected() throws Exception {
        // Larger than the stock on hand, so the service throws and the transaction rolls back.
        mockMvc.perform(post("/api/admin/variants/" + variant.getId() + "/stock")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"delta":-99,"reason":"Shrinkage"}
                                """))
                .andExpect(status().isConflict());

        assertThat(countEvents()).isZero();
    }

    @Test
    void writesNoRowWhenTheTargetDoesNotExist() throws Exception {
        mockMvc.perform(delete("/api/admin/products/" + UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isNotFound());

        assertThat(countEvents()).isZero();
    }

    @Test
    void returnsTheNewestEventFirst() throws Exception {
        adjustStock(1);
        adjustStock(2);
        adjustStock(3);

        mockMvc.perform(get("/api/admin/events").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[0].detail").value(
                        org.hamcrest.Matchers.containsString("delta=3")))
                .andExpect(jsonPath("$.content[2].detail").value(
                        org.hamcrest.Matchers.containsString("delta=1")));
    }

    @Test
    void filtersByTargetActorAndAction() throws Exception {
        adjustStock(1);
        mockMvc.perform(delete("/api/admin/products/" + product.getId())
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/admin/events?targetType=PRODUCT")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].action").value("PRODUCT_ARCHIVED"));

        mockMvc.perform(get("/api/admin/events?targetId=" + variant.getId())
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].action").value("STOCK_ADJUSTED"));

        mockMvc.perform(get("/api/admin/events?action=STOCK_ADJUSTED")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));

        mockMvc.perform(get("/api/admin/events?actorUserId=" + adminUser.getId())
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));

        mockMvc.perform(get("/api/admin/events?actorUserId=" + UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void attributesTheEventToTheCallerNotToAnyoneNamedInTheBody() throws Exception {
        User other = testData.createAdmin("other-admin@example.com", "correct-horse-battery");

        // actorUserId in the body is ignored: the principal decides. Otherwise one administrator
        // could sign an action with a colleague's name.
        mockMvc.perform(post("/api/admin/variants/" + variant.getId() + "/stock")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"delta\":1,\"reason\":\"Recount\",\"actorUserId\":\""
                                + other.getId() + "\"}"))
                .andExpect(status().isOk());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT actor_user_id FROM admin_events", UUID.class))
                .isEqualTo(adminUser.getId());
    }

    @Test
    void survivesTheDeletionOfTheAdministratorWhoActed() throws Exception {
        adjustStock(1);

        // ON DELETE SET NULL: removing an administrator must not erase the record that they acted.
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", adminUser.getId());

        String reader = bearer(testData.createAdmin("reader@example.com", "correct-horse-battery"));
        mockMvc.perform(get("/api/admin/events").header(HttpHeaders.AUTHORIZATION, reader))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].action").value("STOCK_ADJUSTED"))
                .andExpect(jsonPath("$.content[0].actorUserId").doesNotExist())
                .andExpect(jsonPath("$.content[0].actorEmail").doesNotExist());
    }

    @Test
    void rejectsAnUnknownActionAnUnknownTargetTypeAndAnOutOfRangeSize() throws Exception {
        mockMvc.perform(get("/api/admin/events?action=DEFENESTRATED")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/admin/events?targetType=SPACESHIP")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/admin/events?size=101")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isBadRequest());
    }

    private void adjustStock(int delta) throws Exception {
        mockMvc.perform(post("/api/admin/variants/" + variant.getId() + "/stock")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"delta\":" + delta + ",\"reason\":\"Recount\"}"))
                .andExpect(status().isOk());
    }

    private Integer countEvents() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM admin_events", Integer.class);
    }
}
```

`survivesTheDeletionOfTheAdministratorWhoActed` deletes a user by SQL. If `users` has other children with a restricting foreign key that `refresh_tokens`/`auth_events`/`carts` do not, the delete fails and the test tells you so plainly — widen the delete to clear those children first rather than weakening the assertion.

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn verify -Dit.test=AdminEventIT`
Expected: FAIL — no `/api/admin/events` handler. The two "writes no row" tests may already pass; that is fine, they are guarding Task 2's transaction propagation rather than driving new code.

- [ ] **Step 3: Write the response DTO**

`admin/dto/AdminEventResponse.java`:

```java
package com.mvp.ecommercebackend.admin.dto;

import com.mvp.ecommercebackend.admin.entity.AdminEventType;
import com.mvp.ecommercebackend.admin.entity.AdminTargetType;

import java.time.Instant;
import java.util.UUID;

/**
 * One audit row.
 *
 * @param actorUserId null when the administrator has since been deleted; the column is
 *                    {@code ON DELETE SET NULL} so that removing a person does not erase the record
 *                    that they acted
 * @param actorEmail  resolved alongside the id, because "who did this" is the question every reader
 *                    of an audit trail asks first; null whenever {@code actorUserId} is
 * @param detail      free text written by the acting service, capped at 1000 characters
 */
public record AdminEventResponse(
        UUID id,
        UUID actorUserId,
        String actorEmail,
        AdminEventType action,
        AdminTargetType targetType,
        UUID targetId,
        String detail,
        Instant createdAt) {
}
```

- [ ] **Step 4: Write the specifications**

`admin/AdminEventSpecifications.java`:

```java
package com.mvp.ecommercebackend.admin;

import com.mvp.ecommercebackend.admin.entity.AdminEvent;
import com.mvp.ecommercebackend.admin.entity.AdminEventType;
import com.mvp.ecommercebackend.admin.entity.AdminTargetType;
import org.springframework.data.jpa.domain.Specification;

import java.util.UUID;

/** The optional filters behind {@code GET /api/admin/events}. */
final class AdminEventSpecifications {

    private AdminEventSpecifications() {
    }

    static Specification<AdminEvent> hasTargetType(AdminTargetType targetType) {
        return (root, query, builder) -> builder.equal(root.get("targetType"), targetType);
    }

    static Specification<AdminEvent> hasTargetId(UUID targetId) {
        return (root, query, builder) -> builder.equal(root.get("targetId"), targetId);
    }

    /**
     * Filters on the foreign key rather than joining {@code users}: the actor may since have been
     * deleted, and an inner join would hide exactly the rows an auditor most wants to see.
     */
    static Specification<AdminEvent> hasActor(UUID actorUserId) {
        return (root, query, builder) -> builder.equal(root.get("actor").get("id"), actorUserId);
    }

    static Specification<AdminEvent> hasAction(AdminEventType action) {
        return (root, query, builder) -> builder.equal(root.get("action"), action);
    }
}
```

`root.get("actor").get("id")` on a `@ManyToOne` reads the foreign key column without a join, so a null actor simply fails to match rather than dropping the row from an unfiltered query. Match the field name to whatever Task 2 called the association on `AdminEvent`.

- [ ] **Step 5: Add the read method to `AdminEventService`**

Append to `AdminEventService`, and add the imports it needs (`PageResponse`, `Page`, `PageRequest`, `Sort`, `Specification`, `ArrayList`, `List`, `Transactional`):

```java
    /**
     * The audit trail, newest first.
     *
     * <p>There is deliberately no sort parameter: a trail is read backwards from the most recent
     * action, and sorting by {@code detail} or {@code action} serves nobody while widening the
     * surface. Nor is there a write or delete path — an audit trail an administrator can edit records
     * nothing.
     */
    @Transactional(readOnly = true)
    public PageResponse<AdminEventResponse> list(AdminTargetType targetType, UUID targetId,
                                                 UUID actorUserId, AdminEventType action,
                                                 int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<AdminEvent> events = adminEventRepository.findAll(
                filters(targetType, targetId, actorUserId, action), pageRequest);

        List<AdminEventResponse> rows = events.getContent().stream()
                .map(AdminEventService::toResponse)
                .toList();
        return PageResponse.of(events, rows);
    }

    private static Specification<AdminEvent> filters(AdminTargetType targetType, UUID targetId,
                                                     UUID actorUserId, AdminEventType action) {
        List<Specification<AdminEvent>> filters = new ArrayList<>();
        if (targetType != null) {
            filters.add(AdminEventSpecifications.hasTargetType(targetType));
        }
        if (targetId != null) {
            filters.add(AdminEventSpecifications.hasTargetId(targetId));
        }
        if (actorUserId != null) {
            filters.add(AdminEventSpecifications.hasActor(actorUserId));
        }
        if (action != null) {
            filters.add(AdminEventSpecifications.hasAction(action));
        }
        return filters.isEmpty() ? Specification.unrestricted() : Specification.allOf(filters);
    }

    private static AdminEventResponse toResponse(AdminEvent event) {
        User actor = event.getActor();
        return new AdminEventResponse(event.getId(),
                actor == null ? null : actor.getId(),
                actor == null ? null : actor.getEmail(),
                event.getAction(), event.getTargetType(), event.getTargetId(), event.getDetail(),
                event.getCreatedAt());
    }
```

Two rows deep on `createdAt`: it comes from `BaseEntity`, so it is a real, sortable, non-null column and needs no `placedAt`-style alternative.

- [ ] **Step 6: Write the controller**

`admin/AdminEventController.java`:

```java
package com.mvp.ecommercebackend.admin;

import com.mvp.ecommercebackend.admin.dto.AdminEventResponse;
import com.mvp.ecommercebackend.admin.entity.AdminEventType;
import com.mvp.ecommercebackend.admin.entity.AdminTargetType;
import com.mvp.ecommercebackend.common.PageResponse;
import com.mvp.ecommercebackend.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The audit trail, read-only.
 *
 * <p>There is no POST, PATCH, or DELETE here by design. Rows are written by the services performing
 * the mutations, inside the same transaction, so the trail and the change it describes cannot
 * disagree.
 */
@RestController
@RequestMapping("/api/admin/events")
@Tag(name = "Admin Audit")
@SecurityRequirement(name = OpenApiConfig.BEARER_SCHEME)
public class AdminEventController {

    private final AdminEventService adminEventService;

    public AdminEventController(AdminEventService adminEventService) {
        this.adminEventService = adminEventService;
    }

    @GetMapping
    @Operation(summary = "Read the admin audit trail",
            description = "Newest first. Filters by target, acting administrator, and action. "
                    + "Events are never written or deleted through the API.")
    public PageResponse<AdminEventResponse> listEvents(
            @RequestParam(required = false) AdminTargetType targetType,
            @RequestParam(required = false) UUID targetId,
            @RequestParam(required = false) UUID actorUserId,
            @RequestParam(required = false) AdminEventType action,
            @RequestParam(defaultValue = "0")
            @Min(value = 0, message = "must not be negative") int page,
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "must be at least 1")
            @Max(value = 100, message = "must not exceed 100") int size) {
        return adminEventService.list(targetType, targetId, actorUserId, action, page, size);
    }
}
```

- [ ] **Step 7: Register the path in `OpenApiIT`**

Add to `EVERY_PATH`:

```java
            "/api/admin/events",
```

- [ ] **Step 8: Run the tests to verify they pass**

Run: `mvn verify -Dit.test='AdminEventIT,AdminEventServiceIT,OpenApiIT'`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/mvp/ecommercebackend/admin \
        src/test/java/com/mvp/ecommercebackend/admin/AdminEventIT.java \
        src/test/java/com/mvp/ecommercebackend/config/OpenApiIT.java
git commit -m "feat(admin): read the audit trail"
```

---

### Task 12: Prove the gate holds, and close the slice

**Files:**
- Test: `src/test/java/com/mvp/ecommercebackend/admin/AdminSecurityIT.java`
- Test: `src/test/java/com/mvp/ecommercebackend/admin/AdminCatalogLifecycleIT.java`
- Modify: `docs/` — none; the spec is already committed

**Interfaces:**
- Consumes: every endpoint from Tasks 3-11, and `SecurityConfig`'s existing
  `.requestMatchers("/api/admin/**").hasRole("ADMIN")`.
- Produces: nothing new. This task adds no production code.

**Decisions this task locks in:**

- `AdminSecurityIT` is expected to **pass on first run**, because the path rule already exists. That
  does not make it redundant: it is the regression guard that fails the day someone reorders the
  filter chain or adds a controller under a path the rule does not cover. Step 3 proves it has teeth
  by temporarily removing the rule, exactly as `OrderConcurrencyIT` and Task 7 do with `@Lock`.
- One representative endpoint **per controller**, not per endpoint. The rule is path-prefix based, so
  testing all thirty would assert the same thing thirty times — but a whole controller mounted
  outside `/api/admin` is a real, and previously made, mistake.
- Criterion 1 gets its own end-to-end test rather than being inferred from the per-controller tests.
  Each of those proves one endpoint works; none proves that a product built entirely through the
  admin API is visible to a customer, which is the actual requirement.

- [ ] **Step 1: Write the security test**

Create `src/test/java/com/mvp/ecommercebackend/admin/AdminSecurityIT.java`:

```java
package com.mvp.ecommercebackend.admin;

import com.mvp.ecommercebackend.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Acceptance criterion 2: a customer cannot reach any {@code /api/admin/**} path — 403 with a token,
 * 401 without.
 *
 * <p>This test is expected to pass the moment it is written, because
 * {@code .requestMatchers("/api/admin/**").hasRole("ADMIN")} predates this slice. It is here as a
 * regression guard: it fails the day someone reorders the filter chain, or mounts an admin controller
 * under a path the rule does not cover. Step 3 of this task confirms it can fail.
 *
 * <p>One representative path per controller. The rule is prefix-based, so asserting every endpoint
 * would assert the same thing repeatedly — but a controller mounted outside the prefix is a real
 * mistake, and that is what the per-controller coverage catches.
 */
class AdminSecurityIT extends AbstractIntegrationTest {

    private String customer;
    private String admin;

    @BeforeEach
    void setUp() {
        customer = bearer(testData.createCustomer("shopper@example.com", "correct-horse-battery"));
        admin = bearer(testData.createAdmin("gate-admin@example.com", "correct-horse-battery"));
    }

    /** One GET per admin controller. Every one of these must be unreachable without ROLE_ADMIN. */
    @ParameterizedTest
    @ValueSource(strings = {
            "/api/admin/categories",
            "/api/admin/products",
            "/api/admin/orders",
            "/api/admin/events"})
    void refusesACustomerAndAnAnonymousCaller(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, customer))
                .andExpect(status().isForbidden());

        mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk());
    }

    /**
     * The variant and resource paths are addressed by id and have no list endpoint, so they are
     * checked with an id that does not exist. The point is the status: 403 or 401 means the gate ran,
     * and 404 would mean the request reached the handler.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "/api/admin/variants/00000000-0000-0000-0000-000000000000",
            "/api/admin/resources/00000000-0000-0000-0000-000000000000",
            "/api/admin/category-types/00000000-0000-0000-0000-000000000000"})
    void gatesThePathsAddressedOnlyById(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, customer))
                .andExpect(status().isForbidden());
    }

    /**
     * A mutation, not just a read. A chain that gated reads but let writes through would pass the
     * tests above.
     */
    @Test
    void refusesACustomerTryingToWrite() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/admin/categories")
                        .header(HttpHeaders.AUTHORIZATION, customer)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Footwear","code":"FOOTWEAR"}
                                """))
                .andExpect(status().isForbidden());
    }

    /** Browsing must stay open: the gate is on /api/admin/**, not on the catalogue. */
    @Test
    void leavesThePublicCatalogueOpen() throws Exception {
        mockMvc.perform(get("/api/products")).andExpect(status().isOk());
        mockMvc.perform(get("/api/categories")).andExpect(status().isOk());
    }
}
```

`gatesThePathsAddressedOnlyById` assumes a GET exists on those paths. If none does — the spec lists
only PATCH, DELETE, and POST for variants, resources, and category types — a GET returns 405 for an
admin but must still return 401/403 for a customer, because the security filter runs before dispatch.
If any of those three assertions fails with 405 in the customer case rather than 403, switch that
entry to a `delete(...)` request instead of `get(...)`.

- [ ] **Step 2: Run the security test**

Run: `mvn verify -Dit.test=AdminSecurityIT`
Expected: PASS, first time. Nothing in production code needs to change; that is the point.

- [ ] **Step 3: Confirm the test can fail**

A test that has never failed proves nothing. Temporarily comment out the admin rule in
`SecurityConfig` (around line 66):

```java
//                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
```

Run: `mvn verify -Dit.test=AdminSecurityIT`
Expected: FAIL — the customer now reaches the handlers and gets 200 or 404 instead of 403.

Then restore it:

```bash
git checkout src/main/java/com/mvp/ecommercebackend/config/SecurityConfig.java
```

Run: `mvn verify -Dit.test=AdminSecurityIT`
Expected: PASS. `SecurityConfig` must be byte-identical to `HEAD` before continuing — confirm with
`git status`, which should not list it.

- [ ] **Step 4: Write the end-to-end catalogue test**

Create `src/test/java/com/mvp/ecommercebackend/admin/AdminCatalogLifecycleIT.java`:

```java
package com.mvp.ecommercebackend.admin;

import com.mvp.ecommercebackend.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Acceptance criterion 1: an administrator can build a category, a category type, a product, a
 * variant, and an image through the API alone, and a customer then sees that product.
 *
 * <p>Separate from the per-endpoint tests on purpose. Each of those proves one endpoint works; none
 * of them proves that a product assembled entirely through the admin API is visible to a shopper,
 * which is the requirement. No test data factory is used here — using one would skip the very code
 * path under test.
 */
class AdminCatalogLifecycleIT extends AbstractIntegrationTest {

    private String admin;

    @BeforeEach
    void setUp() {
        admin = bearer(testData.createAdmin("catalog-admin@example.com", "correct-horse-battery"));
    }

    @Test
    void buildsAProductThroughTheApiAndShowsItToACustomer() throws Exception {
        UUID categoryId = idOf(post("/api/admin/categories", """
                {"name":"Footwear","code":"FOOTWEAR","description":"Shoes and boots"}
                """));

        UUID categoryTypeId = idOf(post("/api/admin/categories/" + categoryId + "/types", """
                {"name":"Running Shoes","code":"RUNNING","description":"For the road"}
                """));

        UUID productId = idOf(post("/api/admin/products", """
                {"name":"Trail Runner","description":"Grippy and light","price":129.99,
                 "categoryId":"%s","categoryTypeId":"%s"}
                """.formatted(categoryId, categoryTypeId)));

        idOf(post("/api/admin/products/" + productId + "/variants", """
                {"color":"Black","size":"42","stockQuantity":7}
                """));

        idOf(post("/api/admin/products/" + productId + "/resources", """
                {"name":"Hero shot","url":"https://cdn.example.com/trail-runner.jpg",
                 "type":"IMAGE","isPrimary":true}
                """));

        // The customer's view, with no token at all.
        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(productId.toString()))
                .andExpect(jsonPath("$.content[0].name").value("Trail Runner"))
                .andExpect(jsonPath("$.content[0].thumbnail")
                        .value("https://cdn.example.com/trail-runner.jpg"));

        mockMvc.perform(get("/api/products/" + productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Trail Runner"))
                .andExpect(jsonPath("$.categoryName").value("Footwear"))
                .andExpect(jsonPath("$.variants.length()").value(1))
                .andExpect(jsonPath("$.variants[0].color").value("Black"))
                .andExpect(jsonPath("$.variants[0].stockQuantity").value(7))
                .andExpect(jsonPath("$.resources.length()").value(1));

        mockMvc.perform(get("/api/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("FOOTWEAR"))
                .andExpect(jsonPath("$[0].types[0].code").value("RUNNING"));

        // And the whole build is on the record: category, type, product, variant, resource.
        mockMvc.perform(get("/api/admin/events").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(5));
    }

    private UUID idOf(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b)
            throws Exception {
        String body = mockMvc.perform(b)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("id").asString());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder post(
            String path, String json) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                .header(HttpHeaders.AUTHORIZATION, admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);
    }
}
```

Check the public response field names against `ProductDto` / `ProductSummaryResponse` before running —
`thumbnail`, `categoryName`, `variants`, and `resources` come from the existing mappers and must match
what `ProductMapper` actually emits, not what reads naturally here. The `/api/categories` shape is
whatever `CategoryService.toResponse` produces; adjust the two `$[0]` paths to it. The unused
`ResultActions` import is there for the adjustments this step may need — remove it if you do not use it.

- [ ] **Step 5: Run the end-to-end test**

Run: `mvn verify -Dit.test=AdminCatalogLifecycleIT`
Expected: PASS. If a create endpoint returns 200 rather than 201, fix `idOf`'s expectation to match
the controller rather than changing the controller — the status convention was settled in Tasks 3-8.

- [ ] **Step 6: Run the whole build**

Run: `mvn verify`
Expected: PASS, every test. This is acceptance criterion 10 and the only step that covers it.

Two failures are worth naming in advance, because they are the likely ones:
- `SchemaBaselineIT` — a table or column present in one of `V5__admin.sql` and the entities but not the
  other. `ddl-auto=validate` names the offending column in the failure message.
- `OpenApiIT.documentsEveryEndpoint` — an endpoint added without a matching `EVERY_PATH` entry, or an
  entry whose path template does not match the mapping character for character. AssertJ prints both
  the missing and the unexpected paths.

- [ ] **Step 7: Walk the acceptance criteria**

Confirm each one against a test that actually ran, and record the mapping in the commit message. Not a
paperwork exercise: a criterion with no test behind it is the gap this step exists to find.

| # | Criterion | Test |
|---|---|---|
| 1 | Admin builds a catalogue; customer sees the product | `AdminCatalogLifecycleIT` |
| 2 | Customer gets 403, anonymous gets 401 | `AdminSecurityIT` |
| 3 | Archived product leaves the listing, detail 404s, past orders still render | `AdminArchiveIT` |
| 4 | Archived variant refused at cart-add and at checkout | `AdminArchiveIT` |
| 5 | Restore returns the product without resurrecting archived variants | `AdminArchiveIT` |
| 6 | A delta racing a checkout never oversells | `AdminStockConcurrencyIT` |
| 7 | A delta below zero is refused with 409, changing nothing | `AdminStockIT` |
| 8 | Ship then deliver; every illegal transition 409s | `AdminOrderFulfillmentIT` |
| 9 | One audit row per mutation; none on rollback | `AdminEventIT` |
| 10 | `mvn verify` passes, `SchemaBaselineIT` and `OpenApiIT` included | Step 6 |

- [ ] **Step 8: Commit**

```bash
git add src/test/java/com/mvp/ecommercebackend/admin/AdminSecurityIT.java \
        src/test/java/com/mvp/ecommercebackend/admin/AdminCatalogLifecycleIT.java
git commit -m "test(admin): prove the admin gate holds and the catalogue lifecycle works end to end"
```

---

## Acceptance criteria coverage

Every criterion in the spec maps to a task above.

| # | Criterion | Task |
|---|---|---|
| 1 | Full catalogue creation visible publicly | 3, 4, 5, 8, verified end to end in 12 |
| 2 | `/api/admin/**` refuses customers | 12 |
| 3 | Archived product hidden, orders still render | 9 |
| 4 | Archived variant refused at cart-add and checkout | 9 |
| 5 | Restore does not resurrect archived variants | 4, 5, 9 |
| 6 | Stock delta never oversells under concurrency | 6, 7 |
| 7 | Negative result refused with 409 | 6 |
| 8 | Fulfilment lifecycle and its refusals | 1, 10 |
| 9 | Exactly one audit row per mutation, none on rollback | 2, 11 |
| 10 | `mvn verify` green | 1 (`SchemaBaselineIT`), every task's `OpenApiIT` update, 12 |

## Deliberate departures from the spec

Four additions, each small and each argued at its task:

1. **Two unique constraints and a `DataIntegrityViolationException` mapping** (Tasks 1 and 3). The spec
   left duplicate category-type codes and duplicate variant colour/size to service-level `exists`
   checks, which two concurrent requests can both pass. `V5__admin.sql` therefore adds
   `uq_category_types_category_code` on `(category_id, code)` and the **partial** unique index
   `uq_product_variants_live` on `(product_id, color, size) WHERE archived_at IS NULL` — partial so
   that archiving a variant frees its colour and size for reuse. The services keep their `exists`
   checks as the readable-409 fast path; the database is the guarantee. `GlobalExceptionHandler` gains
   a `DataIntegrityViolationException` → 409 mapping so a lost race reports as a conflict instead of
   falling through to the 500 handler. Also fixes an unrelated gap: the codebase previously had no
   mapping for any constraint violation.
2. **`DuplicateResourceException` on a duplicate live variant** (Task 5). The spec's error table lists
   duplicate `code` on categories and category types but not `(product_id, color, size)` on variants.
   Two identical "Black / 42" rows would show a customer the same option twice and split its stock.
3. **`CartService.updateItem` also rejects archived variants** (Task 9). The spec names only `addItem`.
   Leaving `updateItem` open would let a customer raise the quantity of a variant they could no longer
   add, which is the same hole one call site further along.
4. **`AdminOrderResponse` names the customer** (Task 10). Not in the spec, and deliberately not on the
   customer-facing `OrderResponse`: an administrator looking at an order needs to know whose it is.

Two testing choices also depart from the spec's testing table:

- **`AdminCatalogIT` is split four ways** — `AdminCategoryIT`, `AdminProductIT`, `AdminVariantIT`,
  `AdminResourceIT` (Tasks 3, 4, 5, 8). One class covering all of catalogue CRUD would be several
  hundred lines and would not fail cleanly: a reviewer gating Task 5 would be re-running Task 3's
  assertions. Each task owns the test for its own deliverable.
- **`CatalogTestDataFactory` gains no archive helpers.** The spec anticipated fixture methods for
  archived products and variants; `AdminArchiveIT` instead archives through
  `DELETE /api/admin/products/{id}` and `DELETE /api/admin/variants/{id}`. A fixture that sets
  `archived_at` directly would bypass the endpoint under test and could pass while the endpoint was
  broken. The one helper the spec asked for that is genuinely needed — an authenticated admin caller —
  is `AbstractIntegrationTest.bearer(User)`, added in Task 2, since it applies to every admin test and
  not only catalogue ones.

One spec item is deliberately **not** implemented: the customer-facing `OrderResponse` gains no
`trackingReference`, `shippedAt`, or `deliveredAt`. The spec's "Changes to existing code" table does
not list it, and this is an admin-only slice. A customer does still see `status` reach `SHIPPED` and
`DELIVERED`, because that field already exists — only the tracking reference stays admin-visible.
Worth a follow-up slice.

