package com.example.api_gateway.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "gateway_routes")
public class GatewayRouteEntity {

    @Id
    private String routeId;
    private String uri;
    private String predicates;
    private String filters;
    private Integer rateLimitReplenish;
    private Integer rateLimitBurst;
    private String allowedClients;

    // Getters and Setters

    public String getRouteId() {
        return routeId;
    }

    public void setRouteId(String routeId) {
        this.routeId = routeId;
    }

    public String getUri() {
        return uri;
    }

    public void setUri(String uri) {
        this.uri = uri;
    }

    public String getPredicates() {
        return predicates;
    }

    public void setPredicates(String predicates) {
        this.predicates = predicates;
    }

    public String getFilters() {
        return filters;
    }

    public void setFilters(String filters) {
        this.filters = filters;
    }

    public Integer getRateLimitReplenish() {
        return rateLimitReplenish;
    }

    public void setRateLimitReplenish(Integer rateLimitReplenish) {
        this.rateLimitReplenish = rateLimitReplenish;
    }

    public Integer getRateLimitBurst() {
        return rateLimitBurst;
    }

    public void setRateLimitBurst(Integer rateLimitBurst) {
        this.rateLimitBurst = rateLimitBurst;
    }

    public String getAllowedClients() {
        return allowedClients;
    }

    public void setAllowedClients(String allowedClients) {
        this.allowedClients = allowedClients;
    }
}
