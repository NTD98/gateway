# Dynamic Spring Cloud MVC Gateway with Request Signing

A production-ready, highly concurrent API Gateway built with **Spring Cloud Gateway MVC** and **Java 21 Virtual Threads**. 

This gateway is designed to route requests dynamically using configurations (Routes, Predicates, and SSL Certificates) stored in a PostgreSQL database and hot-reloaded without requiring application restarts. It also features a **Unified Key Management System** for **Inbound Signature Verification** and **Outbound Request Signing**.

---

## Architecture Overview

```mermaid
graph TD
    classDef process fill:#1a73e8,stroke:#1a73e8,color:#fff;
    classDef decision fill:#f9ab00,stroke:#f9ab00,color:#000;
    classDef success fill:#1e8e3e,stroke:#1e8e3e,color:#fff;
    classDef failure fill:#d93025,stroke:#d93025,color:#fff;

    %% Inbound Flow
    Start([Client Request]) --> InboundFilter[1. RequestSigningValidationFilter]:::process
    InboundFilter --> InboundHeaders{Has X-Client-Id, <br> X-Timestamp, X-Signature?}:::decision
    
    InboundHeaders -- No --> PassInbound[Pass to Next Filter <br> e.g. JWT / Public Path]:::process
    InboundHeaders -- Yes --> VerifyTimestamp{Is Timestamp <br> under 5 min old?}:::decision
    
    VerifyTimestamp -- No --> RejectReplay[Reject HTTP 401]:::failure
    VerifyTimestamp -- Yes --> FetchPublicKey[Fetch Client Public Key <br> from gateway_keys]:::process
    
    FetchPublicKey --> VerifyInbound{Signature Valid?}:::decision
    VerifyInbound -- No --> RejectSignature[Reject HTTP 401]:::failure
    VerifyInbound -- Yes --> Authenticate[Authenticate client <br> Set SecurityContext]:::success
    
    %% Routing
    Authenticate --> RouteTree[2. DynamicRouterFunction <br> Match Route]:::process
    PassInbound --> RouteTree
    
    %% Outbound Flow
    RouteTree --> OutboundCheck{3. Route has <br> OutboundSign Filter?}:::decision
    
    OutboundCheck -- No --> ForwardDirect[Forward to Downstream]:::process
    OutboundCheck -- Yes --> OutboundSign[4. OutboundSigningFilter]:::process
    
    OutboundSign --> FetchPrivateKey[Fetch Gateway Private Key <br> from gateway_keys]:::process
    FetchPrivateKey --> GenerateSig[Generate Outbound Signature <br> over Method, Path, Time, Body]:::process
    GenerateSig --> AddHeaders[Append Outbound Headers <br> X-Signature, X-Timestamp]:::process
    AddHeaders --> ForwardSigned[5. Forward Signed Request <br> to Third-Party API]:::success
```

---

## Detailed Request Signing Flows

### 1. Inbound Signature Verification
Verifies that incoming requests from clients are authentic and have not been tampered with or replayed.

1.  **Intercept Request**: The `RequestSigningValidationFilter` intercepts the request and wraps it in a `CachedBodyHttpServletRequest` to allow the request body to be read multiple times.
2.  **Validate Timestamp**: The filter checks the `X-Timestamp` header. If the difference between the server time and the request timestamp is greater than 5 minutes, the request is rejected (`401 Unauthorized`) to prevent replay attacks.
3.  **Fetch Public Key**: The filter retrieves the client's public key from the database/cache (`gateway_keys`) using the alias `client:<X-Client-Id>`.
4.  **Reconstruct Canonical String**: The filter constructs a canonical representation of the request:
    ```
    HTTP_METHOD + "\n" +
    PATH_AND_QUERY_STRING + "\n" +
    X-Timestamp + "\n" +
    SHA256_HEX(RAW_BODY)
    ```
5.  **Verify Signature**: The filter decodes the `X-Signature` header (Base64) and verifies it against the canonical string using the client's RSA Public Key (`SHA256withRSA`).
6.  **Authorize Request**: If valid, the request is authenticated with `ROLE_CLIENT` and continues down the filter chain.

---

### 2. Outbound Request Signing
Signs outgoing requests routed through the gateway before forwarding them to third-party external APIs.

1.  **Match Route**: The request matches a route configured in `gateway_routes` that contains the `OutboundSign` filter.
2.  **Fetch Private Key**: The `OutboundSigningFilter` retrieves the gateway's private key from the database/cache (`gateway_keys`) using the configured `keyAlias`.
3.  **Reconstruct Canonical String**: The filter constructs the canonical representation of the outgoing request using its method, path, query string, timestamp, and the SHA-256 hash of the request body.
4.  **Generate Signature**: The filter signs the canonical string using the gateway's RSA Private Key (`SHA256withRSA`) and encodes it in Base64.
5.  **Append Headers**: The filter appends the signature headers (`X-Client-Id`, `X-Timestamp`, and `X-Signature`) to the request and forwards it to the external API.

---

## How to Test

### 1. Database Configuration
Insert the public key for inbound verification and the private key for outbound signing:
```sql
-- Inbound Client Public Key
INSERT INTO gateway_keys (key_alias, key_type, algorithm, key_value, purpose, enabled)
VALUES ('client:test-client', 'PUBLIC_KEY', 'RSA', '-----BEGIN PUBLIC KEY-----\n...', 'INBOUND_VERIFY', true);

-- Outbound Gateway Private Key
INSERT INTO gateway_keys (key_alias, key_type, algorithm, key_value, purpose, enabled)
VALUES ('outbound:partner-api', 'PRIVATE_KEY', 'RSA', '-----BEGIN PRIVATE KEY-----\n...', 'OUTBOUND_SIGN', true);
```

Configure a route that forwards to an external echo service and signs the outgoing request:
```sql
INSERT INTO gateway_routes (route_id, uri, predicates, filters)
VALUES (
  'outbound-test-route',
  'https://postman-echo.com',
  '[{"name": "Path", "args": {"pattern": "/get"}}]',
  '[{"name": "OutboundSign", "args": {"keyAlias": "outbound:partner-api", "clientId": "gateway-client"}}]'
);
```

---

### 2. Curl Command Example
To call the gateway's `/get` endpoint, the client must sign the request canonical string and pass the signature.

```bash
# 1. Construct the canonical string:
# GET\n/get\n1782722203000\ne3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855

# 2. Sign the string using the client's private key:
# openssl dgst -sha256 -sign client_private.pem -out sig.bin canonical.txt
# SIGNATURE=$(openssl base64 -in sig.bin | tr -d '\n')

# 3. Send the request:
curl -i -k \
  -H "X-Client-Id: test-client" \
  -H "X-Timestamp: 1782722203000" \
  -H "X-Signature: cVM4fJYY/S1q3RO7..." \
  --resolve gateway.example.com:8443:127.0.0.1 \
  https://gateway.example.com:8443/get
```

---

### 3. Testing in Postman
To import and test this in Postman:
1.  **Import the Request**: Click **Import** in Postman and paste the URL `https://gateway.example.com:8443/get`.
2.  **Add Headers**: Under the **Headers** tab, add:
    *   `X-Client-Id`: `test-client`
    *   `X-Timestamp`: The current epoch timestamp in milliseconds (e.g. `1782722203000`).
    *   `X-Signature`: The signature generated using the client's private key.
3.  **Disable SSL Verification**: If using a self-signed server certificate, go to **Settings > General** and turn **SSL certificate verification** to **OFF**.
4.  **Send**: Click **Send**. You will see the echoed headers showing both the inbound client ID and the outbound gateway signature.