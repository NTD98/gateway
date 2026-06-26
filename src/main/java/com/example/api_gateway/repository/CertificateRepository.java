package com.example.api_gateway.repository;

import com.example.api_gateway.model.GatewayCertificateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CertificateRepository extends JpaRepository<GatewayCertificateEntity, String> {
}
