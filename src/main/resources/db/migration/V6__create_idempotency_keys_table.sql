CREATE TABLE idempotency_keys (
                                  id               BIGSERIAL PRIMARY KEY,
                                  user_id          BIGINT       NOT NULL,
                                  idempotency_key  VARCHAR(255) NOT NULL,
                                  request_hash     VARCHAR(64)  NOT NULL,
                                  status           VARCHAR(20)  NOT NULL DEFAULT 'IN_PROGRESS',
                                  locked_until     TIMESTAMPTZ,
                                  response_status  INTEGER,
                                  response_body    TEXT,
                                  expires_at       TIMESTAMPTZ  NOT NULL,
                                  created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
                                  updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
                                  CONSTRAINT uq_idempotency_user_key UNIQUE (user_id, idempotency_key)
);

CREATE INDEX idx_idempotency_expires_at ON idempotency_keys (expires_at);
