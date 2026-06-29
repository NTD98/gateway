package com.example.api_gateway.ssl;

import com.example.api_gateway.model.GatewayKeyEntity;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Filter that validates incoming request signatures.
 * Expects headers:
 * - X-Client-Id: The client identifier (looks up 'client:<client-id>' in DB).
 * - X-Timestamp: Epoch timestamp in milliseconds.
 * - X-Signature: Base64 RSA SHA-256 signature of the request.
 */
public class RequestSigningValidationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestSigningValidationFilter.class);

    private static final String HEADER_CLIENT_ID = "X-Client-Id";
    private static final String HEADER_TIMESTAMP = "X-Timestamp";
    private static final String HEADER_SIGNATURE = "X-Signature";

    private static final long TIMESTAMP_THRESHOLD_MS = 300000; // 5 minutes

    private final GatewayKeyCache keyCache;

    public RequestSigningValidationFilter(GatewayKeyCache keyCache) {
        this.keyCache = keyCache;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {

        String clientId = request.getHeader(HEADER_CLIENT_ID);
        String timestampStr = request.getHeader(HEADER_TIMESTAMP);
        String signatureStr = request.getHeader(HEADER_SIGNATURE);

        // If any of the signature headers are missing, we pass it down the chain.
        // Spring Security's AuthorizationFilter will block the request if the path is secured.
        if (clientId == null || timestampStr == null || signatureStr == null) {
            filterChain.doFilter(request, response);
            return;
        }

        // Wrap the request to cache the body so it can be read multiple times
        CachedBodyHttpServletRequest cachedRequest = new CachedBodyHttpServletRequest(request);

        try {
            // 1. Validate Timestamp to prevent replay attacks
            long timestamp = Long.parseLong(timestampStr);
            long currentTime = System.currentTimeMillis();
            if (Math.abs(currentTime - timestamp) > TIMESTAMP_THRESHOLD_MS) {
                log.warn("Request signature rejected: timestamp expired. Client: {}, Timestamp: {}, Server: {}",
                        clientId, timestamp, currentTime);
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Request expired.");
                return;
            }

            // 2. Fetch the client's public key from cache/DB
            String keyAlias = "client:" + clientId;
            Optional<GatewayKeyEntity> keyEntityOpt = keyCache.getKey(keyAlias);

            if (keyEntityOpt.isEmpty()) {
                log.warn("Request signature rejected: key alias '{}' not found or disabled.", keyAlias);
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid client credentials.");
                return;
            }

            GatewayKeyEntity keyEntity = keyEntityOpt.get();

            if (!"INBOUND_VERIFY".equals(keyEntity.getPurpose()) || !"PUBLIC_KEY".equals(keyEntity.getKeyType())) {
                log.warn("Request signature rejected: key alias '{}' has invalid purpose or type.", keyAlias);
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid client credentials.");
                return;
            }

            // 3. Reconstruct the Canonical String
            String method = cachedRequest.getMethod();
            String path = cachedRequest.getRequestURI();
            String queryString = cachedRequest.getQueryString();
            String fullPath = (queryString == null) ? path : path + "?" + queryString;

            byte[] bodyBytes = cachedRequest.getCachedBody();
            String bodyHash = sha256Hex(bodyBytes);

            String canonicalString = method + "\n" +
                    fullPath + "\n" +
                    timestampStr + "\n" +
                    bodyHash;

            log.debug("Reconstructed canonical string:\n{}", canonicalString);

            // 4. Verify the Signature
            PublicKey publicKey = parsePublicKey(keyEntity.getKeyValue(), keyEntity.getAlgorithm());
            boolean verified = verifySignature(canonicalString, signatureStr, publicKey);

            if (!verified) {
                log.warn("Request signature verification failed for client: {}", clientId);
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid signature.");
                return;
            }

            // 5. Authentication succeeded — Set SecurityContext
            PreAuthenticatedAuthenticationToken auth = new PreAuthenticatedAuthenticationToken(
                    clientId,
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_CLIENT"))
            );
            auth.setAuthenticated(true);
            SecurityContextHolder.getContext().setAuthentication(auth);

            log.debug("Request signature verified successfully for client: {}", clientId);

        } catch (NumberFormatException e) {
            log.warn("Request signature rejected: invalid timestamp format.");
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid timestamp.");
            return;
        } catch (Exception e) {
            log.error("Error verifying request signature", e);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Signature verification error.");
            return;
        }

        filterChain.doFilter(cachedRequest, response);
    }

    private String sha256Hex(byte[] bytes) throws Exception {
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

    private PublicKey parsePublicKey(String pem, String algorithm) throws Exception {
        String publicPEM = pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
        byte[] encoded = Base64.getDecoder().decode(publicPEM);
        X509EncodedKeySpec keySpec = new X509EncodedKeySpec(encoded);
        KeyFactory kf = KeyFactory.getInstance(algorithm);
        return kf.generatePublic(keySpec);
    }

    private boolean verifySignature(String data, String base64Signature, PublicKey publicKey) throws Exception {
        Signature publicSignature = Signature.getInstance("SHA256withRSA");
        publicSignature.initVerify(publicKey);
        publicSignature.update(data.getBytes(StandardCharsets.UTF_8));
        byte[] signatureBytes = Base64.getDecoder().decode(base64Signature);
        return publicSignature.verify(signatureBytes);
    }
}
