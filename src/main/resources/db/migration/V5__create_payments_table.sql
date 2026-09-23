CREATE TABLE payments (
                          id                        BIGSERIAL PRIMARY KEY,
                          order_id                  BIGINT        NOT NULL UNIQUE REFERENCES orders(id) ON DELETE RESTRICT,
                          user_id                   BIGINT        NOT NULL,
                          amount                    NUMERIC(10,2) NOT NULL,
                          currency                  VARCHAR(3)    NOT NULL DEFAULT 'usd',
                          status                    VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
                          stripe_payment_intent_id  VARCHAR(255),
                          created_at                TIMESTAMPTZ   NOT NULL DEFAULT now(),
                          updated_at                TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_payments_order_id ON payments (order_id);
CREATE INDEX idx_payments_user_id ON payments (user_id);