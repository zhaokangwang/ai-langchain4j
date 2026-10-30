CREATE TABLE user_accounts (
    id VARCHAR(64) PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    role VARCHAR(16) NOT NULL CHECK (role IN ('buyer', 'service', 'warehouse')),
    administrator BOOLEAN NOT NULL DEFAULT FALSE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    password_change_required BOOLEAN NOT NULL DEFAULT FALSE,
    session_version BIGINT NOT NULL DEFAULT 0 CHECK (session_version >= 0),
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    CHECK (username = LOWER(TRIM(username)) AND CHAR_LENGTH(username) BETWEEN 3 AND 64)
);
