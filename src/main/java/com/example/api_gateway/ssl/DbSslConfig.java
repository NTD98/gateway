package com.example.api_gateway.ssl;

import org.springframework.boot.ssl.DefaultSslBundleRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DbSslConfig {

    /**
     * Registers a dummy SslBundle on startup so Tomcat can bind to 8443 immediately.
     * CertificateSyncService replaces this with the real DB-backed bundle before
     * any traffic arrives (via @PostConstruct → syncCertificates).
     */
    @Bean
    public DefaultSslBundleRegistry sslBundles() {
        DefaultSslBundleRegistry registry = new DefaultSslBundleRegistry();

        try {
            java.security.KeyStore dummyStore = java.security.KeyStore.getInstance("PKCS12");
            dummyStore.load(null, null);
            org.springframework.boot.ssl.SslStoreBundle storeBundle = org.springframework.boot.ssl.SslStoreBundle.of(dummyStore, "", dummyStore);
            org.springframework.boot.ssl.SslBundle dummyBundle = org.springframework.boot.ssl.SslBundle.of(
                storeBundle,
                org.springframework.boot.ssl.SslBundleKey.NONE,
                org.springframework.boot.ssl.SslOptions.NONE,
                "TLS",
                org.springframework.boot.ssl.SslManagerBundle.from(storeBundle, org.springframework.boot.ssl.SslBundleKey.NONE)
            );
            registry.registerBundle("gateway-bundle", dummyBundle);
        } catch (Exception e) {
            // Ignore — will be overwritten immediately by CertificateSyncService
        }

        return registry;
    }

}

