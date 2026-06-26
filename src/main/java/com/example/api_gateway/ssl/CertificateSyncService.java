package com.example.api_gateway.ssl;

import com.example.api_gateway.model.GatewayCertificateEntity;
import com.example.api_gateway.repository.CertificateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Optional;

@Service
public class CertificateSyncService {

    private static final Logger log = LoggerFactory.getLogger(CertificateSyncService.class);
    
    private final CertificateRepository certRepository;
    private final Path certDir = Paths.get("/etc/gateway/certs");

    public CertificateSyncService(CertificateRepository certRepository) {
        this.certRepository = certRepository;
    }

    @Scheduled(fixedDelay = 60000)
    public void syncCertificates() {
        try {
            Optional<GatewayCertificateEntity> certOpt = certRepository.findById("gateway.example.com");
            
            if (certOpt.isPresent()) {
                GatewayCertificateEntity cert = certOpt.get();
                
                if (!Files.exists(certDir)) {
                    Files.createDirectories(certDir);
                }

                Path keyFile = certDir.resolve("privkey.pem");
                Path certFile = certDir.resolve("fullchain.pem");

                if (hasFileChanged(keyFile, cert.getPrivateKeyPem()) || 
                    hasFileChanged(certFile, cert.getCertificatePem())) {
                    
                    Files.writeString(keyFile, cert.getPrivateKeyPem());
                    Files.writeString(certFile, cert.getCertificatePem());
                    
                    log.info("Certificates updated from database. Spring Boot will trigger SSL hot-reload if reload-on-update is true.");
                }
            }
        } catch (Exception e) {
            log.error("Failed to sync certificates", e);
        }
    }

    private boolean hasFileChanged(Path file, String newContent) throws Exception {
        if (!Files.exists(file)) return true;
        byte[] currentBytes = Files.readAllBytes(file);
        byte[] newBytes = newContent.getBytes();
        return !Arrays.equals(currentBytes, newBytes);
    }
}
