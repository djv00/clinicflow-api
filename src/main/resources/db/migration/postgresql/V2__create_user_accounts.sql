CREATE TABLE user_accounts (
    id UUID PRIMARY KEY,
    username VARCHAR(100) NOT NULL,
    username_key VARCHAR(300) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL,
    enabled BOOLEAN NOT NULL,
    CONSTRAINT uk_user_accounts_username_key UNIQUE (username_key),
    CONSTRAINT ck_user_accounts_role CHECK (role IN ('OPERATOR', 'VIEWER'))
);
