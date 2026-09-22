CREATE TABLE carts (
                        id         BIGSERIAL PRIMARY KEY,
                        user_id    BIGINT      NOT NULL,
                        created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                        updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                        CONSTRAINT uq_carts_user_id UNIQUE (user_id)
);

CREATE TABLE cart_items (
                             id         BIGSERIAL PRIMARY KEY,
                             cart_id    BIGINT      NOT NULL REFERENCES carts(id) ON DELETE CASCADE,
                             product_id BIGINT      NOT NULL REFERENCES products(id) ON DELETE RESTRICT,
                             quantity   INTEGER     NOT NULL DEFAULT 1 CHECK (quantity > 0),
                             created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                             updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                             CONSTRAINT uq_cart_items_cart_product UNIQUE (cart_id, product_id)
);

CREATE INDEX idx_cart_items_cart_id ON cart_items (cart_id);