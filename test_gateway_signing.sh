#!/bin/bash

# Exit on error
set -e

echo "=================================================="
echo "API Gateway Request Signing Test Suite"
echo "=================================================="

# 1. Generate test RSA keypair if not present
if [ ! -f /tmp/test_private.pem ]; then
  echo "Generating temporary RSA 2048-bit keypair in /tmp..."
  openssl genpkey -algorithm RSA -out /tmp/test_private.pem -pkeyopt rsa_keygen_bits:2048 2>/dev/null
  openssl rsa -pubout -in /tmp/test_private.pem -out /tmp/test_public.pem 2>/dev/null
fi

PRIVATE_KEY=$(cat /tmp/test_private.pem)
PUBLIC_KEY=$(cat /tmp/test_public.pem)

# 2. Configure PostgreSQL Database
echo "Configuring test keys and routes in the database..."
PGPASSWORD=password psql -U user -h localhost -d gateway_db -c "
-- Insert Inbound Client Public Key
DELETE FROM gateway_keys WHERE key_alias = 'client:test-client';
INSERT INTO gateway_keys (key_alias, key_type, algorithm, key_value, purpose, enabled)
VALUES ('client:test-client', 'PUBLIC_KEY', 'RSA', '$PUBLIC_KEY', 'INBOUND_VERIFY', true);

-- Insert Outbound Gateway Private Key
DELETE FROM gateway_keys WHERE key_alias = 'outbound:partner-api';
INSERT INTO gateway_keys (key_alias, key_type, algorithm, key_value, purpose, enabled)
VALUES ('outbound:partner-api', 'PRIVATE_KEY', 'RSA', '$PRIVATE_KEY', 'OUTBOUND_SIGN', true);

-- Configure Outbound Route with Client Authorization
DELETE FROM gateway_routes WHERE route_id = 'outbound-test-route';
INSERT INTO gateway_routes (route_id, uri, predicates, filters, allowed_clients)
VALUES (
  'outbound-test-route',
  'https://postman-echo.com',
  '[{\"name\": \"Path\", \"args\": {\"pattern\": \"/get\"}}]',
  '[{\"name\": \"OutboundSign\", \"args\": {\"keyAlias\": \"outbound:partner-api\", \"clientId\": \"gateway-client\"}}]',
  'test-client'
);
"

echo "Database successfully configured."
echo "NOTE: If you just started the gateway, please wait up to 60 seconds for the cache to sync."
echo "=================================================="

CLIENT_ID="test-client"
TIMESTAMP=$(date +%s)000
BODY_HASH=$(echo -n "" | shasum -a 256 | awk '{print $1}')

# --------------------------------------------------
# TEST 1: Inbound Signature Verification
# --------------------------------------------------
echo -e "\n--- Running TEST 1: Inbound Signature Verification ---"
CANONICAL_HEALTH="GET
/actuator/health
${TIMESTAMP}
${BODY_HASH}"

echo -n "$CANONICAL_HEALTH" > /tmp/canonical_health.txt
openssl dgst -sha256 -sign /tmp/test_private.pem -out /tmp/sig_health.bin /tmp/canonical_health.txt
SIG_HEALTH=$(openssl base64 -in /tmp/sig_health.bin | tr -d '\n')

echo "Calling https://gateway.example.com:8443/actuator/health..."
curl -i -k \
  -H "X-Client-Id: $CLIENT_ID" \
  -H "X-Timestamp: $TIMESTAMP" \
  -H "X-Signature: $SIG_HEALTH" \
  --resolve gateway.example.com:8443:127.0.0.1 \
  https://gateway.example.com:8443/actuator/health

# --------------------------------------------------
# TEST 2: Outbound Request Signing & Authorization
# --------------------------------------------------
echo -e "\n--- Running TEST 2: Outbound Request Signing & Authorization ---"
CANONICAL_GET="GET
/get
${TIMESTAMP}
${BODY_HASH}"

echo -n "$CANONICAL_GET" > /tmp/canonical_get.txt
openssl dgst -sha256 -sign /tmp/test_private.pem -out /tmp/sig_get.bin /tmp/canonical_get.txt
SIG_GET=$(openssl base64 -in /tmp/sig_get.bin | tr -d '\n')

echo "Calling https://gateway.example.com:8443/get..."
curl -s -k \
  -H "X-Client-Id: $CLIENT_ID" \
  -H "X-Timestamp: $TIMESTAMP" \
  -H "X-Signature: $SIG_GET" \
  --resolve gateway.example.com:8443:127.0.0.1 \
  https://gateway.example.com:8443/get
echo -e "\n"
