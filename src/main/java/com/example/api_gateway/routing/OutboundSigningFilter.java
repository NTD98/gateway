package com.example.api_gateway.routing;

import com.example.api_gateway.model.GatewayKeyEntity;
import com.example.api_gateway.ssl.GatewayKeyCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.function.HandlerFilterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Optional;

/**
 * Spring Cloud Gateway MVC filter that signs outgoing requests.
 * Retrieves the private key from GatewayKeyCache, constructs the canonical request,
 * signs it, and adds X-Client-Id, X-Timestamp, and X-Signature headers.
 */
public class OutboundSigningFilter {

    private static final Logger log = LoggerFactory.getLogger(OutboundSigningFilter.class);

    private static final String HEADER_CLIENT_ID = "X-Client-Id";
    private static final String HEADER_TIMESTAMP = "X-Timestamp";
    private static final String HEADER_SIGNATURE = "X-Signature";

    public static HandlerFilterFunction<ServerResponse, ServerResponse> sign(GatewayKeyCache keyCache, String keyAlias, String clientId) {
        return (request, next) -> {
            Optional<GatewayKeyEntity> keyEntityOpt = keyCache.getKey(keyAlias);

            if (keyEntityOpt.isEmpty()) {
                log.error("Outbound signing failed: private key alias '{}' not found or disabled.", keyAlias);
                return ServerResponse.status(500).body("Signing key not configured.");
            }

            GatewayKeyEntity keyEntity = keyEntityOpt.get();

            if (!"OUTBOUND_SIGN".equals(keyEntity.getPurpose()) || !"PRIVATE_KEY".equals(keyEntity.getKeyType())) {
                log.error("Outbound signing failed: key alias '{}' has invalid purpose or type.", keyAlias);
                return ServerResponse.status(500).body("Invalid signing key configuration.");
            }

            try {
                // 1. Read request body and cache it
                byte[] bodyBytes = request.body(byte[].class);
                String bodyHash = sha256Hex(bodyBytes);

                // 2. Prepare canonical details
                String method = request.method().name();
                String path = request.uri().getRawPath();
                String queryString = request.uri().getRawQuery();
                String fullPath = (queryString == null) ? path : path + "?" + queryString;
                String timestampStr = String.valueOf(System.currentTimeMillis());

                String canonicalString = method + "\n" +
                        fullPath + "\n" +
                        timestampStr + "\n" +
                        bodyHash;

                // 3. Sign the canonical string
                PrivateKey privateKey = parsePrivateKey(keyEntity.getKeyValue(), keyEntity.getAlgorithm());
                String base64Signature = signData(canonicalString, privateKey);

                // 4. Rebuild the request with signature headers and the cached body
                ServerRequest signedRequest = ServerRequest.from(request)
                        .header(HEADER_CLIENT_ID, clientId)
                        .header(HEADER_TIMESTAMP, timestampStr)
                        .header(HEADER_SIGNATURE, base64Signature)
                        .body(bodyBytes) // Re-set the body bytes so downstream can read it
                        .build();

                log.debug("Outbound request signed successfully using key alias: {}", keyAlias);
                return next.handle(signedRequest);

            } catch (Exception e) {
                log.error("Error signing outbound request", e);
                return ServerResponse.status(500).body("Request signing error.");
            }
        };
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(bytes);
        StringBuilder hexString = new StringBuilder();
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }

    private static PrivateKey parsePrivateKey(String pem, String algorithm) throws Exception {
        String privatePEM = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] encoded = Base64.getDecoder().decode(privatePEM);
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(encoded);
        KeyFactory kf = KeyFactory.getInstance(algorithm);
        return kf.generatePrivate(keySpec);
    }

    private static String signData(String data, PrivateKey privateKey) throws Exception {
        Signature privateSignature = Signature.getInstance("SHA256withRSA");
        privateSignature.initSign(privateKey);
        privateSignature.update(data.getBytes(StandardCharsets.UTF_8));
        byte[] signatureBytes = privateSignature.sign();
        return Base64.getEncoder().encodeToString(signatureBytes);
    }
}
