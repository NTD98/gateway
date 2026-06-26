# Dynamic Spring Cloud MVC Gateway

A production-ready, highly concurrent API Gateway built with **Spring Cloud Gateway MVC** and **Java 21 Virtual Threads**. This gateway is designed to route requests to backend microservices dynamically by storing all configurations (Routes, Predicates, and SSL Certificates) in a PostgreSQL database and hot-reloading them without requiring application restarts.

## Features

- 🚀 **Java 21 Virtual Threads**: Uses Spring WebMVC under the hood instead of WebFlux, allowing for imperative programming while maintaining massive scalability and non-blocking I/O via Project Loom's Virtual Threads.
- 🔀 **Dynamic Hot-Reloading Routing**: Implements an **Atomic Delegate Pattern** to fetch route definitions (including JSON predicates) from the database every 30 seconds and swap the routing tree atomically. 
- 🔒 **Keycloak OAuth2 Security**: Stateless JWT validation using the `SecurityFilterChain`. Invalid requests are rejected (HTTP 401/403) before they even reach the routing layer.
- 🌍 **Service Discovery (Eureka)**: Integrates `spring-cloud-starter-netflix-eureka-client` and `spring-cloud-starter-loadbalancer`. Routes with URIs like `lb://customer-service` are natively resolved against the Eureka cluster.
- 🔐 **Dynamic SSL Certificate Sync**: Fetches PEM certificates from PostgreSQL and writes them to disk dynamically. Spring Boot SSL bundles (`reload-on-update: true`) automatically hot-reload the Tomcat HTTPS listener.
- 🐳 **Dockerized Infrastructure**: Comes with a ready-to-run `docker-compose.yml` including PostgreSQL, Redis, and Keycloak.

## Technology Stack

- **Framework**: Spring Boot 3.4.x, Spring Cloud MVC
- **Language**: Java 21
- **Database**: PostgreSQL (Spring Data JPA)
- **Identity Provider**: Keycloak
- **Build Tool**: Maven

## Architecture Overview

1. **Request Lifecycle**: 
   - `Outside -> Spring Security (JWT Validation) -> DynamicRouterFunction -> Eureka Resolution -> Backend Microservice`
2. **Database Schema (`schema.sql`)**: 
   - `gateway_routes`: Stores routing rules (e.g. `route_id`, `uri` = `lb://service-name`, `predicates` = `[{"name": "Path", "args": {"pattern": "/api/**"}}]`)
   - `gateway_certificates`: Stores raw PEM keys for domains.

## Getting Started

### 1. Launch Infrastructure
Spin up the backing databases and Keycloak identity provider:
```bash
docker compose up -d postgres redis keycloak
```

### 2. Run the Gateway
Build and start the API Gateway container using Maven:
```bash
docker compose up --build -d api-gateway
```

### 3. Verification
The gateway will start on ports `8080` (HTTP) and `8443` (HTTPS). You can verify its health via:
```bash
curl http://localhost:8443/actuator/health
```

To test the security interceptor (you will receive a `401 Unauthorized` without a valid Keycloak JWT):
```bash
curl -v http://localhost:8443/fallback
```

## Adding Dynamic Routes
Insert a row into the PostgreSQL `gateway_routes` table:
```sql
INSERT INTO gateway_routes (route_id, uri, predicates) 
VALUES ('customer_api', 'lb://customer-service', '[{"name": "Path", "args": {"pattern": "/customer/**"}}]');
```
Wait 30 seconds, and the background `RouteSyncService` will automatically parse the predicates and inject the route into the active WebMVC router tree!