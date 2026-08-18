# Admin Portal APIs — Design

Date: 2026-08-14
Status: approved, ready for implementation planning

## Goal

Give administrators an HTTP surface for managing the catalogue and progressing orders through
fulfilment. Today `/api/admin/**` is reserved for `ROLE_ADMIN` in `SecurityConfig` but no controller
serves any path under it, and the catalogue is read-only: `ProductService` and `CategoryService`
expose only `@Transactional(readOnly = true)` methods, and nothing in the `catalog` package calls
`save` or `delete`. There is no way to create a product except by hand-written SQL.

This slice closes that gap. It does not touch payments.

## Out of scope

**Real payment processing.** `PaymentGateway` has a single implementation,
`SimulatedPaymentGateway`, which approves every charge except the literal token `tok_declined`. That
stays exactly as it is. Refunds therefore stay unimplemented, and `PAID → CANCELLED` remains a
refused transition.

Recorded for a later slice, since assessing it was part of this discussion: real payments would need
the `PaymentGateway` seam reshaped from a synchronous `charge(...)` into create-intent plus
asynchronous confirmation, a signature-verified idempotent webhook endpoint, a `payments` table to
hold attempt rows (the single `orders.payment_reference` column cannot represent a retry or a
refund), `FAILED` and `REFUNDED` statuses, server-side amount verification, and a reconciliation job
for orders stranded in `PENDING_PAYMENT`. That is a larger body of work than this slice.

Also out of scope: customer account administration (no user list, role assignment, or suspension),
discounts and promotions, multi-currency, and any admin UI. This is an API-only slice.

## Decisions

| Decision | Choice | Reason |
|---|---|---|
| Code location | New `admin` package | Keeps public catalogue read paths genuinely read-only |
| Delete semantics | Soft delete (`archived_at`) | `cart_items.product_variant_id` is `ON DELETE CASCADE`, so a hard delete silently empties live carts |
| Order actions | Full fulfilment lifecycle | Admins need to progress a paid order to dispatch |
| Stock changes | Signed delta endpoint only | Race-free by construction; an absolute set can clobber a concurrent sale |
| Audit | One `admin_events` table | Mirrors the existing `auth_events` precedent; gives the stock `reason` a home |

Rejected alternatives: admin controllers inside `catalog`/`commerce` (would double `ProductService`
and cost its read-only guarantee; `OrderService` is already ~290 lines); a single `AdminController`
facade (becomes a god class once catalogue CRUD, stock, fulfilment, and audit reads land); absolute
stock set via `PATCH` (a stale read undoes a concurrent sale — admin reads 10, a customer buys one
leaving 9, admin writes 10, and a sold unit reappears).

## Data model

One migration, `V5__admin.sql`.

```sql
-- Archive, not delete
ALTER TABLE products         ADD COLUMN archived_at timestamptz;
ALTER TABLE product_variants ADD COLUMN archived_at timestamptz;

-- Fulfilment
ALTER TABLE orders ADD COLUMN shipped_at         timestamptz,
                   ADD COLUMN delivered_at       timestamptz,
                   ADD COLUMN tracking_reference varchar(100);

-- ck_orders_status is pinned to three literal values and must be replaced, not extended
ALTER TABLE orders DROP CONSTRAINT ck_orders_status;
ALTER TABLE orders ADD  CONSTRAINT ck_orders_status
    CHECK (status IN ('PENDING_PAYMENT','PAID','SHIPPED','DELIVERED','CANCELLED'));

-- Audit trail
CREATE TABLE admin_events (
    id            uuid         PRIMARY KEY,
    actor_user_id uuid         REFERENCES users (id) ON DELETE SET NULL,
    action        varchar(40)  NOT NULL,
    target_type   varchar(30)  NOT NULL,
    target_id     uuid,
    detail        varchar(1000),
    created_at    timestamptz  NOT NULL,
    updated_at    timestamptz  NOT NULL
);

CREATE INDEX idx_admin_events_target ON admin_events (target_type, target_id);
CREATE INDEX idx_admin_events_actor  ON admin_events (actor_user_id, created_at DESC);
CREATE INDEX idx_orders_status       ON orders (status, placed_at DESC);
CREATE INDEX idx_products_active     ON products (id) WHERE archived_at IS NULL;
```

`actor_user_id` is `ON DELETE SET NULL`, matching `auth_events`: deleting an administrator must not
erase the record that an action took place.

Categories and category types deliberately get no `archived_at`. Deleting a category referenced by a
product is blocked by the `products.category_id` foreign key, which is the correct outcome; the
service turns that into a deliberate 409 rather than letting a constraint violation surface as a 500.

### Entity changes

- `Product`, `ProductVariant`: `archivedAt`
- `Order`: `shippedAt`, `deliveredAt`, `trackingReference`
- `OrderStatus`: add `SHIPPED`, `DELIVERED`
- New: `AdminEvent` entity, `AdminEventType` enum, `AdminEventRepository`, `AdminEventService`

`AdminEventService.record(...)` follows `AuthEventService` and runs inside the caller's transaction,
so a mutation and its audit row commit together or roll back together.

## State machine

```
PENDING_PAYMENT --pay--> PAID --ship--> SHIPPED --deliver--> DELIVERED
       |
       +--cancel--> CANCELLED
```

- `PAID → CANCELLED` stays refused. Reversing a settled sale is a refund, which needs a record of
  who authorised it and how much came back. Deferred with payments.
- `SHIPPED` and `DELIVERED` are terminal. There is no un-ship; a mis-shipment is corrected by a
  refund, which does not exist yet.
- Transitions are enforced in the service, not only by the CHECK constraint. The existing
  `requirePendingPayment` gains a sibling taking an expected status and throwing
  `InvalidOrderStateException`.

## API surface

All paths sit under `/api/admin/**`, already gated by `hasRole("ADMIN")` at `SecurityConfig:66`.
No security configuration change is required.

Pagination and sorting follow the established convention: `PageResponse<T>`, `page`/`size` bounded
by `@Min`/`@Max`, and `sort` restricted by an `@Pattern` whitelist so no caller can sort by an
arbitrary entity path.

### AdminCategoryController

```
POST   /api/admin/categories                {name, code, description}
PATCH  /api/admin/categories/{id}           partial
DELETE /api/admin/categories/{id}           409 when products reference it
POST   /api/admin/categories/{id}/types     {name, code, description}
PATCH  /api/admin/category-types/{id}
DELETE /api/admin/category-types/{id}
```

### AdminProductController

```
GET    /api/admin/products?archived=&categoryId=&q=&page=&size=&sort=&direction=
GET    /api/admin/products/{id}
POST   /api/admin/products                  {name, description, price, categoryId, categoryTypeId}
PATCH  /api/admin/products/{id}             partial
DELETE /api/admin/products/{id}             sets archived_at
POST   /api/admin/products/{id}/restore
```

The admin projection differs from the public one: it exposes `archivedAt` and per-variant stock.
`archived=` defaults to excluding archived rows and can select only-archived or all.

Unlike the public detail endpoint, `GET /api/admin/products/{id}` returns an archived product
normally rather than 404 — an administrator must be able to inspect what they retired in order to
restore it. Archived variants are likewise included in the admin response, each carrying its own
`archivedAt`.

### AdminVariantController

```
POST   /api/admin/products/{id}/variants    {color, size, stockQuantity}
PATCH  /api/admin/variants/{id}             {color, size}
DELETE /api/admin/variants/{id}             sets archived_at
POST   /api/admin/variants/{id}/restore
POST   /api/admin/variants/{id}/stock       {delta, reason}

POST   /api/admin/products/{id}/resources   {name, url, type, isPrimary}
PATCH  /api/admin/resources/{id}
DELETE /api/admin/resources/{id}            hard delete
```

`stockQuantity` is accepted when creating a variant, as an opening balance, and is **not** patchable
afterwards. Every later change goes through the delta endpoint, which reads the row under the same
`FOR UPDATE` lock `OrderService.placeOrder` uses and rejects a result below zero. No code path can
overwrite stock from a stale read.

Product resources are hard deleted rather than archived: a URL carries no history worth preserving,
unlike a product a customer once bought. Setting `isPrimary: true` clears the flag on the product's
other resources within the same transaction.

### AdminOrderController

```
GET    /api/admin/orders?status=&userId=&orderNumber=&from=&to=&page=&size=&sort=&direction=
GET    /api/admin/orders/{id}
POST   /api/admin/orders/{id}/ship          {trackingReference}
POST   /api/admin/orders/{id}/deliver
POST   /api/admin/orders/{id}/cancel        unpaid orders only; returns stock
```

Admin cancel reuses the existing stock-returning logic and is restricted to `PENDING_PAYMENT`.

`from` and `to` filter on `placed_at` and are ISO-8601 instants (for example
`2026-08-01T00:00:00Z`), inclusive of `from` and exclusive of `to`.

### AdminEventController

```
GET    /api/admin/events?targetType=&targetId=&actorUserId=&action=&page=&size=
```

Read-only. There is no API path that writes or erases an audit event directly.

## Changes to existing code

Archiving only means something if it hides things, so five paths must respect `archived_at`:

| Path | Change |
|---|---|
| `ProductSpecifications` | add `archived_at IS NULL` to the public listing query |
| public `GET /api/products/{id}` | 404 when the product is archived |
| public product detail | omit archived variants from the response |
| `CartService.addItem` | reject an archived variant with 409 |
| `OrderService.placeOrder` | reject archived variants at checkout with 409 |

The checkout check is the one most easily missed: a variant can be archived *after* it is already in
a customer's cart, so placement must re-check rather than trust the cart's contents.

An archived product is hidden from the public catalogue regardless of its variants' own flags.
Variants keep an independent `archived_at`, so restoring a product does not resurrect a variant that
was retired separately.

## Error handling

The existing RFC 7807 `ProblemDetail` mappings cover every case but one, so this slice adds a single
exception type and its handler.

| Situation | Exception | Status |
|---|---|---|
| Unknown product, variant, category, or order id | `ResourceNotFoundException` | 404 |
| Duplicate category or category-type `code` | `DuplicateResourceException` | 409 |
| Ship a non-`PAID` order, deliver a non-`SHIPPED` order, cancel a paid order | `InvalidOrderStateException` | 409 |
| Stock delta would leave a negative quantity | `InsufficientStockException` | 409 |
| Add or check out an archived variant | `InvalidOrderStateException` | 409 |
| Delete a category still referenced by products | `ResourceInUseException` (new) | 409 |
| Malformed body, out-of-range `page`/`size` | bean validation | 400 |
| Customer token against `/api/admin/**` | `AccessDeniedException` | 403 |
| No token | — | 401 |

One new exception, `ResourceInUseException`, is added with a handler returning 409. Reusing
`DuplicateResourceException` for "still in use" would be misleading at the call site.

Validation matches the column types: `price` carries `@DecimalMin("0.00")` and
`@Digits(integer = 10, fraction = 2)` for `numeric(12,2)`; `code` carries a `@Pattern` and a length
cap matching `varchar(100)`; `trackingReference` is capped at 100 characters.

## Security

- `/api/admin/**` is matched before `anyRequest().authenticated()`, so chain ordering is already
  correct.
- Admin controllers carry no `@PreAuthorize`. The path rule is sufficient, and a second overlapping
  check invites the two drifting apart. `CartController` and `OrderController` need
  `isAuthenticated()` only because their paths are not otherwise gated.
- The audit actor is read from the authenticated principal, never from the request body, so an
  administrator cannot attribute an action to someone else.
- The rate limiter remains scoped to auth endpoints, so bulk catalogue work is not throttled.

## Testing

Integration tests extend `AbstractIntegrationTest`: one Testcontainers Postgres shared by the suite,
tables truncated between tests.

| Test | Covers |
|---|---|
| `AdminCatalogIT` | CRUD happy paths for products, categories, category types, variants, resources; validation failures; `isPrimary` reassignment |
| `AdminArchiveIT` | archived product absent from public list and 404 on detail; archived variant rejected at cart-add and at checkout; restore reverses it |
| `AdminStockIT` | positive and negative deltas; rejection below zero; `reason` persisted |
| `AdminStockConcurrencyIT` | a delta adjustment racing a checkout on one variant, modelled on `OrderConcurrencyIT` |
| `AdminOrderFulfillmentIT` | `PAID → SHIPPED → DELIVERED`; tracking reference persisted; every illegal transition returns 409 |
| `AdminSecurityIT` | 401 unauthenticated, 403 as `CUSTOMER`, 200 as `ADMIN`, one representative endpoint per controller |
| `AdminEventIT` | each mutation writes exactly one audit row with the correct actor; a rolled-back mutation writes none |

`AdminStockConcurrencyIT` is the test this design most depends on. The delta endpoint's entire
justification is that it is race-free, and that should be proven rather than asserted.

Two existing tests need updating:

- `SchemaBaselineIT` asserts an explicit table list and must gain `admin_events`. Its
  `ddl-auto=validate` guard catches any entity/migration mismatch in V5 automatically.
- `OpenApiIT` validates the generated document, so new endpoints need `@Operation` annotations
  matching the existing style.

`CatalogTestDataFactory` gains helpers for archived products and variants, and for an authenticated
admin caller.

## Acceptance criteria

1. An administrator can create a category, a category type, a product, a variant, and an image, then
   see that product in the public `GET /api/products` listing.
2. A customer cannot reach any `/api/admin/**` path: 403 with a token, 401 without.
3. Archiving a product removes it from the public listing and makes its detail endpoint return 404,
   while existing orders referencing it still render their line items from the snapshot columns.
4. An archived variant cannot be added to a cart, and cannot be checked out if it was archived after
   being added.
5. Restoring an archived product returns it to the public listing without resurrecting separately
   archived variants.
6. A stock delta applied concurrently with a checkout on the same variant leaves a correct quantity
   and never oversells.
7. A stock delta that would leave a negative quantity is rejected with 409 and changes nothing.
8. A paid order can be shipped with a tracking reference and then delivered; shipping an unpaid
   order, delivering an unshipped order, and cancelling a paid order each return 409.
9. Every admin mutation writes exactly one `admin_events` row naming the acting administrator, and a
   mutation that rolls back writes none.
10. `mvn verify` passes, including `SchemaBaselineIT` and `OpenApiIT`.
