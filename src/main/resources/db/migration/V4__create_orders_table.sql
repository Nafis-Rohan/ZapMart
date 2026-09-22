CREATE TABLE orders (
                         id          BIGSERIAL PRIMARY KEY,
                         user_id     BIGINT        NOT NULL,
                         status      VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
                         total_price NUMERIC(10,2) NOT NULL,
                         created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
                         updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_orders_user_id ON orders (user_id);

CREATE TABLE order_items (
                              id           BIGSERIAL PRIMARY KEY,
                              order_id     BIGINT        NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
                              product_id   BIGINT        REFERENCES products(id) ON DELETE SET NULL,
                              product_name VARCHAR(255)  NOT NULL,
                              unit_price   NUMERIC(10,2) NOT NULL,
                              quantity     INTEGER       NOT NULL CHECK (quantity > 0),
                              created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
                              updated_at   TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_order_items_order_id ON order_items (order_id);