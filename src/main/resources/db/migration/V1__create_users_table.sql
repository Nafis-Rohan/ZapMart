CREATE TABLE users (
                       id         BIGSERIAL PRIMARY KEY,
                       email      VARCHAR(255) NOT NULL UNIQUE,
                       name       VARCHAR(255) NOT NULL,
                       role       VARCHAR(20)  NOT NULL,
                       created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
                       updated_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

INSERT INTO users (email, name, role) VALUES
                                          ('admin@zapmart.test', 'Admin User', 'ADMIN'),
                                          ('customer@zapmart.test', 'Test Customer', 'CUSTOMER');