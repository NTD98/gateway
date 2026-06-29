package com.example.api_gateway.repository;

import com.example.api_gateway.model.GatewayKeyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface GatewayKeyRepository extends JpaRepository<GatewayKeyEntity, String> {
    Optional<GatewayKeyEntity> findByKeyAliasAndEnabled(String keyAlias, boolean enabled);
    List<GatewayKeyEntity> findAllByEnabled(boolean enabled);
}
