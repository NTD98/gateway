package com.example.api_gateway.repository;

import com.example.api_gateway.model.GatewayRouteEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RouteRepository extends JpaRepository<GatewayRouteEntity, String> {
}
