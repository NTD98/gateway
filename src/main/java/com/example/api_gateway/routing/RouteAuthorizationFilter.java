package com.example.api_gateway.routing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.function.HandlerFilterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import java.util.Arrays;
import java.util.List;

/**
 * Filter that enforces route-specific client authorization.
 * Compares the authenticated client ID (from SecurityContextHolder) against
 * the allowed_clients list configured for the route.
 */
public class RouteAuthorizationFilter {

    private static final Logger log = LoggerFactory.getLogger(RouteAuthorizationFilter.class);

    public static HandlerFilterFunction<ServerResponse, ServerResponse> authorize(String allowedClientsStr) {
        List<String> allowedClients = Arrays.stream(allowedClientsStr.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        return (request, next) -> {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();

            if (auth == null || !auth.isAuthenticated()) {
                log.warn("Unauthorized access attempt to routed path: {}", request.uri().getPath());
                return ServerResponse.status(HttpStatus.UNAUTHORIZED).body("Unauthorized: authentication required.");
            }

            String clientId = auth.getName();

            if (allowedClients.contains("*") || allowedClients.contains(clientId)) {
                log.debug("Access granted to client '{}' for path '{}'", clientId, request.uri().getPath());
                return next.handle(request);
            }

            log.warn("Access denied for client '{}' trying to access path '{}'. Allowed clients: {}",
                    clientId, request.uri().getPath(), allowedClientsStr);
            return ServerResponse.status(HttpStatus.FORBIDDEN).body("Access denied: client not authorized for this route.");
        };
    }
}
