-- Orders and their lines.
--
-- Two decisions are baked into this schema:
--
-- 1. The shipping address is a *snapshot*, not a reference to `addresses`. An order records where
--    the goods were sent; editing or deleting the address afterwards must not rewrite that history.
--    The same reasoning applies to the line columns: product name, unit price and variant
--    attributes are copied, so a later price change or a renamed product cannot alter what the
--    customer agreed to pay.
--
-- 2. `order_number` is random, not a sequence. It is the only identifier a customer ever quotes,
--    and a sequence would leak the shop's order volume and let anyone enumerate their neighbours'
--    orders. See OrderNumberGenerator.
--
-- `currency` is varchar(3) rather than char(3): Postgres reports char as bpchar (Types#CHAR) while
-- a Java String maps to Types#VARCHAR, and ddl-auto=validate rejects that mismatch. The length is
-- enforced with a check constraint instead.
CREATE TABLE orders (
    id                  uuid          PRIMARY KEY,
    user_id             uuid          NOT NULL REFERENCES users (id),
    order_number        varchar(20)   NOT NULL,
    status              varchar(20)   NOT NULL,
    currency            varchar(3)    NOT NULL,
    subtotal_amount     numeric(12,2) NOT NULL,
    total_amount        numeric(12,2) NOT NULL,
    payment_reference   varchar(100),
    ship_recipient_name varchar(255)  NOT NULL,
    ship_phone          varchar(30),
    ship_line1          varchar(255)  NOT NULL,
    ship_line2          varchar(255),
    ship_city           varchar(120)  NOT NULL,
    ship_state          varchar(120),
    ship_postal_code    varchar(20)   NOT NULL,
    ship_country        varchar(2)    NOT NULL,
    placed_at           timestamptz   NOT NULL,
    paid_at             timestamptz,
    cancelled_at        timestamptz,
    created_at          timestamptz   NOT NULL,
    updated_at          timestamptz   NOT NULL,
    CONSTRAINT uq_orders_order_number UNIQUE (order_number),
    CONSTRAINT ck_orders_status CHECK (status IN ('PENDING_PAYMENT', 'PAID', 'CANCELLED')),
    CONSTRAINT ck_orders_currency_length CHECK (char_length(currency) = 3),
    CONSTRAINT ck_orders_amounts_not_negative
        CHECK (subtotal_amount >= 0 AND total_amount >= 0)
);

-- The list endpoint pages a single user's orders newest first.
CREATE INDEX idx_orders_user_placed_at ON orders (user_id, placed_at DESC);

-- product_id and product_variant_id are ON DELETE SET NULL, not CASCADE: withdrawing a product
-- must never delete the record that it was once sold. They exist only so a client can offer
-- "buy this again", which is why the display columns beside them are snapshots and not joins.
CREATE TABLE order_items (
    id                 uuid          PRIMARY KEY,
    order_id           uuid          NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    product_id         uuid          REFERENCES products (id) ON DELETE SET NULL,
    product_variant_id uuid          REFERENCES product_variants (id) ON DELETE SET NULL,
    product_name       varchar(255)  NOT NULL,
    variant_color      varchar(60),
    variant_size       varchar(30),
    unit_price         numeric(12,2) NOT NULL,
    quantity           integer       NOT NULL,
    line_total         numeric(12,2) NOT NULL,
    -- The position the line had in the cart. Rows come back from a collection fetch in whatever
    -- order the database likes, and a customer comparing a confirmation against their cart expects
    -- to see the same sequence every time, so the order is stored rather than inferred.
    line_number        integer       NOT NULL,
    created_at         timestamptz   NOT NULL,
    updated_at         timestamptz   NOT NULL,
    CONSTRAINT uq_order_items_order_line UNIQUE (order_id, line_number),
    CONSTRAINT ck_order_items_quantity_positive CHECK (quantity > 0),
    CONSTRAINT ck_order_items_line_number_positive CHECK (line_number > 0),
    CONSTRAINT ck_order_items_amounts_not_negative CHECK (unit_price >= 0 AND line_total >= 0)
);

CREATE INDEX idx_order_items_order_id ON order_items (order_id);
