# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run Commands

```bash
# Run in dev mode (hot reload)
./mvnw quarkus:dev

# Build (skip tests)
./mvnw package -DskipTests

# Build native image
./mvnw package -Pnative

# Run tests
./mvnw test

# Run a single test class
./mvnw test -Dtest=MyTestClass

# Run integration tests
./mvnw verify -DskipITs=false
```

The app runs on port **8488** by default.

## Architecture Overview

This is a **Quarkus 3.4.1** (Java 21) application that acts as an automated connection rebalancer and autoscaler for WebSocket (WS) and Server-Sent Events (SSE) session servers.

### Core Loop

A scheduled task ([RebalancerTask](src/main/java/infrastructure/scheduler/RebalancerTask.java)) drives two periodic operations:
- **Every 10s** — `analyzeSessionServerUtilization()`: scale out/in servers based on overall utilization
- **Every 60s** — `analyzeSessionServerBalance()`: rebalance connections across servers by dropping sessions on overutilized hosts via Redis Pub/Sub

### Session Types

Two parallel session hierarchies exist (WS and SSE), each following the same layered pattern:

| Layer | WS | SSE |
|---|---|---|
| API (orchestration) | [WsSessionApi](src/main/java/api/WsSessionApi.java) | [SseSessionApi](src/main/java/api/SseSessionApi.java) |
| Domain service | [WsSessionService](src/main/java/domain/WsSessionService.java) | [SseSessionService](src/main/java/domain/SseSessionService.java) |
| Repository interface | [WsSessionRepository](src/main/java/domain/WsSessionRepository.java) | [SseSessionRepository](src/main/java/domain/SseSessionRepository.java) |
| Redis impl | [RedisWsSessionRepository](src/main/java/infrastructure/redis/RedisWsSessionRepository.java) | [RedisSseSessionRepository](src/main/java/infrastructure/redis/RedisSseSessionRepository.java) |

Sessions are stored in Redis with keys `WsSession#*` / `SseSession#*`. The `dropWsSessions`/`dropSseSessions` methods publish to the Redis channel `drop-persistent-sessions`, which the actual session servers subscribe to in order to elect and close connections.

### Environment Modes

Controlled by `app.connection-rebalancer.environment.type` (env var `ENVIRONMENT_TYPE`):

- **`container_runtime`** (default): Uses Docker Socket Proxy ([AutoScaler](src/main/java/domain/utils/AutoScaler.java)) and Consul ([RestConsulClient](src/main/java/infrastructure/resources/rest/RestConsulClient.java)) for service discovery. Active/inactive services are determined by Consul health checks — inactive = has a `_service_maintenance` check. Scale-in puts the service into Consul maintenance mode; scale-out reactivates it or spawns new containers via Docker API.

- **`k8s`**: Uses fabric8 Kubernetes client ([K8AutoScaler](src/main/java/domain/utils/K8AutoScaler.java)). Active/inactive pods are distinguished by the label `traffic=active` / `traffic=inactive`. Scale-in patches the pod label to `inactive`; scale-out reactivates inactive pods first, then patches deployment replicas. Pods ready for deletion get the annotation `controller.kubernetes.io/pod-deletion-cost=-100` before replica count is reduced.

### Key Configuration Properties

Defined in [application.properties](src/main/resources/application.properties) and read via `@ConfigProperty`:

| Property | Purpose |
|---|---|
| `connection.limit.per.host` | Max sessions allowed per server |
| `overutilized.tolerance.percent` | % above average to classify as overutilized |
| `underutilized.tolerance.percent` | % below average to classify as underutilized |
| `max.utilization.percent` | Overall threshold to trigger scale-out (e.g. 70) |
| `min.utilization.percent` | Overall threshold to trigger scale-in (e.g. 40) |
| `socket-proxy.uri` | Docker socket proxy endpoint (container_runtime mode) |
| `container-runtime.network` | Docker network name for new containers |
| `app.connection-rebalancer.kubernetes.app-label` | K8s deployment name to patch replicas |

### External Dependencies

- **Redis** — session storage (keys) + signaling (Pub/Sub for session drops)
- **Consul** — service discovery and active/inactive state management (container_runtime mode only)
- **Docker Socket Proxy** — container lifecycle management (container_runtime mode only)
- **Kubernetes API** — pod/deployment management (k8s mode only)
