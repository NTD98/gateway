CREATE TABLE IF NOT EXISTS gateway_routes (
    route_id VARCHAR(255) PRIMARY KEY,
    uri VARCHAR(255) NOT NULL,
    predicates JSONB NOT NULL,
    filters JSONB NOT NULL,
    rate_limit_replenish INT DEFAULT 10,
    rate_limit_burst INT DEFAULT 20
);

CREATE TABLE IF NOT EXISTS gateway_certificates (
    domain VARCHAR(255) PRIMARY KEY,
    private_key_pem TEXT NOT NULL,
    certificate_pem TEXT NOT NULL,
    last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
