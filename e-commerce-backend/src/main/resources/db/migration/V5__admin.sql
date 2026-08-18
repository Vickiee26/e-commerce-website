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
