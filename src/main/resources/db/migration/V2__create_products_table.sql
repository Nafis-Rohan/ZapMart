CREATE TABLE products (
                          id             BIGSERIAL PRIMARY KEY,
                          name           VARCHAR(255)   NOT NULL,
                          description    TEXT,
                          price          NUMERIC(10,2)  NOT NULL,
                          stock_quantity INTEGER        NOT NULL DEFAULT 0,
                          category       VARCHAR(100)   NOT NULL,
                          created_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
                          updated_at     TIMESTAMPTZ    NOT NULL DEFAULT now()
);

CREATE INDEX idx_products_category ON products (category);