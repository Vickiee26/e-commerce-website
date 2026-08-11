-- One open cart per customer.
--
-- The unique constraint on user_id is the whole concurrency story for carts: two requests racing
-- to create the caller's first cart cannot both win.
CREATE TABLE carts (
    id         uuid        PRIMARY KEY,
    user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT uq_carts_user UNIQUE (user_id)
);

-- Lines reference a variant, not a product: stock lives on the variant, so a product with no
-- variants has nothing purchasable to point at.
--
-- No price column. A cart line is a pointer, and prices are re-read from the catalogue when the
-- order is placed, so a stale cart can never lock in a stale price.
CREATE TABLE cart_items (
    id                 uuid        PRIMARY KEY,
    cart_id            uuid        NOT NULL REFERENCES carts (id) ON DELETE CASCADE,
    product_variant_id uuid        NOT NULL REFERENCES product_variants (id) ON DELETE CASCADE,
    quantity           integer     NOT NULL,
    created_at         timestamptz NOT NULL,
    updated_at         timestamptz NOT NULL,
    CONSTRAINT uq_cart_items_cart_variant UNIQUE (cart_id, product_variant_id),
    CONSTRAINT ck_cart_items_quantity_positive CHECK (quantity > 0)
);

CREATE INDEX idx_cart_items_cart_id ON cart_items (cart_id);
