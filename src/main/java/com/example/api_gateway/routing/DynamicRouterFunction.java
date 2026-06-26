package com.example.api_gateway.routing;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.function.HandlerFunction;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class DynamicRouterFunction implements RouterFunction<ServerResponse> {

    private final AtomicReference<RouterFunction<ServerResponse>> currentRoutes = new AtomicReference<>();

    public void updateRoutes(RouterFunction<ServerResponse> newRoutes) {
        currentRoutes.set(newRoutes);
    }

    @Override
    public Optional<HandlerFunction<ServerResponse>> route(ServerRequest request) {
        RouterFunction<ServerResponse> routes = currentRoutes.get();
        if (routes != null) {
            return routes.route(request);
        }
        return Optional.empty();
    }
}
