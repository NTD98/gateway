package com.example.api_gateway.routing;

import com.example.api_gateway.model.GatewayRouteEntity;
import com.example.api_gateway.repository.RouteRepository;
import com.example.api_gateway.ssl.GatewayKeyCache;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.server.mvc.filter.LoadBalancerFilterFunctions;
import org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.function.RequestPredicate;
import org.springframework.web.servlet.function.RequestPredicates;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import java.util.List;
import java.util.Map;
import jakarta.annotation.PostConstruct;

import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;

@Service
public class RouteSyncService {

    private static final Logger log = LoggerFactory.getLogger(RouteSyncService.class);

    private final RouteRepository routeRepository;
    private final ObjectMapper objectMapper;
    private final DynamicRouterFunction dynamicRouterFunction;
    private final GatewayKeyCache gatewayKeyCache;

    public RouteSyncService(RouteRepository routeRepository, 
                            ObjectMapper objectMapper, 
                            DynamicRouterFunction dynamicRouterFunction,
                            GatewayKeyCache gatewayKeyCache) {
        this.routeRepository = routeRepository;
        this.objectMapper = objectMapper;
        this.dynamicRouterFunction = dynamicRouterFunction;
        this.gatewayKeyCache = gatewayKeyCache;
    }

    @PostConstruct
    public void init() {
        syncRoutes();
    }

    @Scheduled(fixedDelay = 30000) // Sync every 30 seconds
    public void syncRoutes() {
        try {
            List<GatewayRouteEntity> routeEntities = routeRepository.findAll();
            RouterFunction<ServerResponse> routes = null;

            for (GatewayRouteEntity entity : routeEntities) {
                try {
                    RequestPredicate combinedPredicate = null;
                    List<Map<String, Object>> predicates = objectMapper.readValue(
                            entity.getPredicates(), new TypeReference<>() {});

                    for (Map<String, Object> pred : predicates) {
                        String name = (String) pred.get("name");
                        @SuppressWarnings("unchecked")
                        Map<String, String> args = (Map<String, String>) pred.get("args");

                        RequestPredicate rp = null;
                        if ("Path".equalsIgnoreCase(name) && args != null && args.containsKey("pattern")) {
                            rp = RequestPredicates.path(args.get("pattern"));
                        } else if ("Method".equalsIgnoreCase(name) && args != null && args.containsKey("method")) {
                            rp = RequestPredicates.method(org.springframework.http.HttpMethod.valueOf(args.get("method").toUpperCase()));
                        }

                        if (rp != null) {
                            combinedPredicate = (combinedPredicate == null) ? rp : combinedPredicate.and(rp);
                        }
                    }

                    if (combinedPredicate == null) {
                        log.warn("Route {} has no valid predicates, skipping.", entity.getRouteId());
                        continue;
                    }

                    var routeBuilder = route(entity.getRouteId())
                            .route(combinedPredicate, HandlerFunctions.http(entity.getUri()));

                    // Apply Load Balancer filter if URI starts with lb://
                    if (entity.getUri() != null && entity.getUri().startsWith("lb://")) {
                        String serviceId = entity.getUri().replace("lb://", "");
                        routeBuilder.filter(LoadBalancerFilterFunctions.lb(serviceId));
                    }

                    // Parse and apply route filters (e.g., OutboundSign)
                    if (entity.getFilters() != null && !entity.getFilters().isBlank()) {
                        List<Map<String, Object>> filters = objectMapper.readValue(
                                entity.getFilters(), new TypeReference<>() {});
                        for (Map<String, Object> filter : filters) {
                            String name = (String) filter.get("name");
                            @SuppressWarnings("unchecked")
                            Map<String, String> args = (Map<String, String>) filter.get("args");

                            if ("OutboundSign".equalsIgnoreCase(name) && args != null && args.containsKey("keyAlias")) {
                                String keyAlias = args.get("keyAlias");
                                String clientId = args.getOrDefault("clientId", "gateway");
                                routeBuilder.filter(OutboundSigningFilter.sign(gatewayKeyCache, keyAlias, clientId));
                                log.info("Applied OutboundSign filter to route '{}' using key alias '{}'", entity.getRouteId(), keyAlias);
                            }
                        }
                    }

                    RouterFunction<ServerResponse> currentRoute = routeBuilder.build();

                    if (routes == null) {
                        routes = currentRoute;
                    } else {
                        routes = routes.and(currentRoute);
                    }
                    
                } catch (Exception e) {
                    log.error("Failed to parse route config for " + entity.getRouteId(), e);
                }
            }

            if (routes == null) {
                routes = route("default_fallback")
                        .GET("/fallback", HandlerFunctions.http("http://localhost:8080"))
                        .build();
            }

            // Atomically update the routing tree
            dynamicRouterFunction.updateRoutes(routes);
            log.info("Gateway routing table updated successfully from database.");

        } catch (Exception e) {
            log.error("Failed to sync routing table from database.", e);
        }
    }
}
