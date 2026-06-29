package com.example.api_gateway.ssl;

import com.example.api_gateway.model.GatewayKeyEntity;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class GatewayKeyCache {

    private volatile Map<String, GatewayKeyEntity> cache = new ConcurrentHashMap<>();

    public void refresh(Map<String, GatewayKeyEntity> newCache) {
        this.cache = new ConcurrentHashMap<>(newCache);
    }

    public Optional<GatewayKeyEntity> getKey(String keyAlias) {
        GatewayKeyEntity entity = cache.get(keyAlias);
        if (entity == null || !entity.isEnabled()) {
            return Optional.empty();
        }
        return Optional.of(entity);
    }

    public int size() {
        return cache.size();
    }
}
