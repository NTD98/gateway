CREATE TABLE IF NOT EXISTS gateway_routes (
    route_id VARCHAR(255) PRIMARY KEY,
    uri VARCHAR(255) NOT NULL,
    predicates JSONB NOT NULL,
    filters JSONB NOT NULL,
    rate_limit_replenish INT DEFAULT 10,
    rate_limit_burst INT DEFAULT 20,
    allowed_clients TEXT
);

CREATE TABLE IF NOT EXISTS gateway_certificates (
    domain VARCHAR(255) PRIMARY KEY,
    private_key_pem TEXT NOT NULL,
    certificate_pem TEXT NOT NULL,
    last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS gateway_keys (
    key_alias VARCHAR(255) PRIMARY KEY,
    key_type VARCHAR(50) NOT NULL,
    algorithm VARCHAR(50) NOT NULL,
    key_value TEXT NOT NULL,
    purpose VARCHAR(50) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
