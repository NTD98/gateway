package com.example.api_gateway.config;

import com.example.api_gateway.ssl.GatewayKeyCache;
import com.example.api_gateway.ssl.RequestSigningValidationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final GatewayKeyCache gatewayKeyCache;

    public SecurityConfig(GatewayKeyCache gatewayKeyCache) {
        this.gatewayKeyCache = gatewayKeyCache;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        // Request signing filter runs before JWT filter so both auth mechanisms can coexist.
        // If a request has valid signing headers -> authenticated via ROLE_CLIENT.
        // If no signing headers but a valid JWT is present -> authenticated via Keycloak roles.
        RequestSigningValidationFilter signingFilter = new RequestSigningValidationFilter(gatewayKeyCache);

        http
            .csrf(csrf -> csrf.disable())
            .addFilterBefore(signingFilter, BearerTokenAuthenticationFilter.class)
            .authorizeHttpRequests(authz -> authz
                .requestMatchers("/actuator/**", "/login/**", "/oauth2/**").permitAll()
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
            );

        return http.build();
    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Map<String, Object> realmAccess = jwt.getClaim("realm_access");
            if (realmAccess == null || !realmAccess.containsKey("roles")) {
                return Collections.emptyList();
            }
            @SuppressWarnings("unchecked")
            List<String> roles = (List<String>) realmAccess.get("roles");
            return roles.stream()
                    .map(role -> new SimpleGrantedAuthority("ROLE_" + role.toUpperCase()))
                    .collect(Collectors.toList());
        });
        return converter;
    }
}
