package com.example.api_gateway.ssl;

import com.example.api_gateway.model.GatewayCertificateEntity;
import com.example.api_gateway.model.GatewayKeyEntity;
import com.example.api_gateway.repository.CertificateRepository;
import com.example.api_gateway.repository.GatewayKeyRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundleKey;
import org.springframework.boot.ssl.SslBundleRegistry;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.ssl.SslManagerBundle;
import org.springframework.boot.ssl.SslOptions;
import org.springframework.boot.ssl.SslStoreBundle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class CertificateSyncService {

    private static final Logger log = LoggerFactory.getLogger(CertificateSyncService.class);

    private final CertificateRepository certRepository;
    private final GatewayKeyRepository gatewayKeyRepository;
    private final SslBundles sslBundles;
    private final GatewayKeyCache gatewayKeyCache;

    private int lastServerCertHash = 0;

    public CertificateSyncService(CertificateRepository certRepository,
                                  GatewayKeyRepository gatewayKeyRepository,
                                  SslBundles sslBundles,
                                  GatewayKeyCache gatewayKeyCache) {
        this.certRepository = certRepository;
        this.gatewayKeyRepository = gatewayKeyRepository;
        this.sslBundles = sslBundles;
        this.gatewayKeyCache = gatewayKeyCache;
    }

    @PostConstruct
    public void init() {
        log.info("Initializing server certificates and gateway keys on startup...");
        syncServerCertificates();
        syncGatewayKeys();
    }

    @Scheduled(fixedDelay = 60000)
    public void syncServerCertificates() {
        try {
            List<GatewayCertificateEntity> certs = certRepository.findAll();

            int currentHash = 0;
            for (GatewayCertificateEntity cert : certs) {
                currentHash += cert.getDomain().hashCode() + cert.getPrivateKeyPem().hashCode() + cert.getCertificatePem().hashCode();
            }

            if (currentHash != lastServerCertHash && !certs.isEmpty()) {
                log.info("Detected server SSL certificate changes in database. Rebuilding SSL bundle...");

                KeyStore keyStore = KeyStore.getInstance("PKCS12");
                keyStore.load(null, null);
                String dummyPassword = UUID.randomUUID().toString();

                for (GatewayCertificateEntity cert : certs) {
                    try {
                        PrivateKey privateKey = parsePrivateKey(cert.getPrivateKeyPem());
                        X509Certificate[] certificateChain = parseCertificateChain(cert.getCertificatePem());
                        keyStore.setKeyEntry(cert.getDomain(), privateKey, dummyPassword.toCharArray(), certificateChain);
                    } catch (Exception e) {
                        log.error("Failed to load server certificate for domain: " + cert.getDomain(), e);
                    }
                }

                KeyStore trustStore = KeyStore.getInstance("PKCS12");
                trustStore.load(null, null);

                SslStoreBundle storeBundle = SslStoreBundle.of(keyStore, dummyPassword, trustStore);
                SslBundle newBundle = SslBundle.of(
                    storeBundle,
                    SslBundleKey.of(dummyPassword),
                    SslOptions.NONE,
                    "TLS",
                    SslManagerBundle.from(storeBundle, SslBundleKey.of(dummyPassword))
                );

                if (sslBundles instanceof SslBundleRegistry registry) {
                    registry.updateBundle("gateway-bundle", newBundle);
                    lastServerCertHash = currentHash;
                    log.info("Server SSL certs reloaded in Tomcat from database! Loaded {} cert(s).", certs.size());
                } else {
                    log.error("sslBundles is not an instance of SslBundleRegistry!");
                }
            } else if (certs.isEmpty() && lastServerCertHash != 0) {
                log.warn("No server certificates found in database! Keeping previous cache.");
            }
        } catch (Exception e) {
            log.error("Failed to sync server certificates from database", e);
        }
    }

    @Scheduled(fixedDelay = 60000)
    public void syncGatewayKeys() {
        try {
            List<GatewayKeyEntity> keys = gatewayKeyRepository.findAll();
            Map<String, GatewayKeyEntity> newCache = new HashMap<>();
            for (GatewayKeyEntity key : keys) {
                newCache.put(key.getKeyAlias(), key);
            }
            gatewayKeyCache.refresh(newCache);
            long enabledCount = keys.stream().filter(GatewayKeyEntity::isEnabled).count();
            log.info("Gateway key cache refreshed: {} total keys, {} enabled.", keys.size(), enabledCount);
        } catch (Exception e) {
            log.error("Failed to sync gateway keys from database", e);
        }
    }

    private PrivateKey parsePrivateKey(String pem) throws Exception {
        String privateKeyPEM = pem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replaceAll("\\s", "");
        byte[] encoded = Base64.getDecoder().decode(privateKeyPEM);
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(encoded);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return kf.generatePrivate(keySpec);
    }

    private X509Certificate[] parseCertificateChain(String pem) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        ByteArrayInputStream bis = new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8));
        Collection<? extends Certificate> certs = cf.generateCertificates(bis);
        return certs.toArray(new X509Certificate[0]);
    }
}
