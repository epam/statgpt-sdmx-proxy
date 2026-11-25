# Config Server

## Problem

The SDMX Proxy currently manages its own configuration through four source types (`ENV`, `CLASSPATH_RESOURCE`,
`FILESYSTEM`, `DIAL_STORAGE`) and exposes `GET /api/config` + `POST /api/config` endpoints directly. As the TODO in
`ConfigController.java` (line 14) states:

> This approach will not work in scalable environment. Need to implement something similar to QH config server.

Core problems:

1. **Config management is not the proxy's job.** The proxy should be a lightweight SDMX translation layer. Config
   CRUD and storage integration are separate concerns that bloat the proxy.
2. **`POST /api/config` is publicly exposed.** In production, config mutations should be behind internal auth, not on
   the public-facing proxy.
3. **`POST /api/config` only updates one pod.** In a multi-replica K8s deployment, other pods remain on the old config
   until they independently re-read from DIAL Storage.

## Scope

**In scope:**

- New `sdmx-config-server` microservice (separate deployable, same repo)
- Config server takes over DIAL Storage integration from the proxy
- Proxy simplification: only `CLASSPATH_RESOURCE`, `FILESYSTEM` (local dev) and `CONFIG_SERVER` (production) source
  types remain

**Out of scope:**

- Config versioning, rollback, audit trail (classpath resource is always the baseline fallback; see Future Improvements)
- Multi-tenancy
- Config server HA clustering (addressed in deployment section as a K8s concern)

## What Config Server Actually Is

**Extract `GetConfig` + `PostConfig` from the proxy into a separate internal microservice.** That's the core idea.

The config server:

- Reads config from DIAL Storage (takes over `DialStorageConfigExtractorProxy` + `DialStorageProxyConfigurationWriter`)
- Serves config to proxy instances via an internal API
- Accepts config updates and writes them to DIAL Storage

The proxy becomes a simple polling client that periodically fetches the latest config from the config server.

## Architecture Overview

### Tech stack

Spring Boot 4.0, Java 25, Gradle -- same stack as sdmx-proxy. Reuses `sdmx-proxy-config` module for data classes
(already a standalone `java-library` module with zero Spring dependencies, only Lombok). Shares the proxy's base Docker
image (same Java 25 + Spring Boot 4.0 stack).

### Module structure

New module in the same repository. Adding a new module follows the existing pattern (`sdmx-proxy`, `sdmx-proxy-config`,
`sdmx-proxy-e2e`).

```
sdmx-config-server/
├── src/main/java/com/epam/sdmxproxy/configserver/
│   ├── api/                     # REST API interfaces (OpenAPI-annotated)
│   ├── controller/              # Controllers
│   ├── service/                 # Config read/write via DIAL Storage
│   └── dialstorage/             # DIAL Storage client (moved from proxy)
├── src/main/resources/
│   └── application.yaml
└── build.gradle
```

Note: The package `com.epam.sdmxproxy.configserver` shares the `sdmxproxy` root with the proxy. If both are on the
classpath simultaneously (e.g., in integration tests), use `@SpringBootApplication(scanBasePackages = ...)` to isolate
component scanning.

### Why same repo

| Factor                     | Same repo                 | Separate repo                   |
|----------------------------|---------------------------|---------------------------------|
| Shared `sdmx-proxy-config` | Direct Gradle dependency  | Must publish to artifact repo   |
| Coordinated changes        | Single PR                 | Cross-repo version coordination |
| CI simplicity              | One pipeline              | Two pipelines                   |
| Release independence       | Coupled (but intentional) | Independent                     |

The config server and proxy share the same data model (`ProxyConfiguration`, `AgencyConfiguration`,
`RegistryConfiguration`). Co-locating them avoids version drift.

## Storage

**DIAL Storage only.** The config server takes over the existing DIAL Storage integration from the proxy. No PostgreSQL,
no Flyway, no versioning tables, no rollback.

The following classes move from the proxy to the config server (with package adjustments):

| Current location (sdmx-proxy)                                            | New location (sdmx-config-server)              |
|--------------------------------------------------------------------------|------------------------------------------------|
| `registry/configuration/dialstorage/DialStorageClient.java`              | `dialstorage/DialStorageClient.java`           |
| `registry/configuration/dialstorage/FeignDialStorageClient.java`         | `dialstorage/FeignDialStorageClient.java`      |
| `registry/configuration/dialstorage/DialStorageFeignApi.java`            | `dialstorage/DialStorageFeignApi.java`         |
| `registry/configuration/dialstorage/DialStorageSourceProperties.java`    | `dialstorage/DialStorageSourceProperties.java` |
| `registry/configuration/dialstorage/DialStorageUploadRequest.java`       | `dialstorage/DialStorageUploadRequest.java`    |
| `registry/configuration/extractor/DialStorageConfigExtractorProxy.java`  | `service/ConfigService.java` (read logic)      |
| `registry/configuration/writer/DialStorageProxyConfigurationWriter.java` | `service/ConfigService.java` (write logic)     |

The DIAL Storage caching layer (`DialStorageConfigCache`, `RedisDialStorageConfigCache`,
`CaffeineDialStorageConfigCache`) is no longer needed. The config server serves config from memory (loaded from DIAL
Storage on startup and refreshed on writes). No caching layer between config server and DIAL Storage.

**Config server startup sequence:** On startup, the config server reads the current config from DIAL Storage into
memory. If DIAL Storage is unavailable at startup, the config server starts but reports unhealthy
via `/actuator/health` and returns 503 on `GET /api/config` until DIAL Storage becomes available and the first
successful read completes.

**Cold-start (no config in DIAL Storage):** If DIAL Storage has no config (fresh deployment), `GET /api/config` returns

404. The initial config must be pushed via `POST /api/config`. The proxy continues using its classpath config until the
     config server has data.

**DIAL Storage authentication:** The config server uses the same auth pattern as the existing proxy integration -- API
key passed in a request header to DIAL Storage (see `DialStorageFeignApi`).

## Config Server API

```
GET    /api/config                      -- Serve current config (from DIAL Storage)
POST   /api/config                      -- Update config (write to DIAL Storage)
GET    /actuator/health                 -- Health endpoint (Spring Boot Actuator)
```

The health endpoint is the standard Spring Boot Actuator endpoint (`/actuator/health` by default, can be remapped via
`management.endpoints.web.base-path`). Health indicators include DIAL Storage connectivity.

OpenTelemetry must be connected (DevOps requirement). Standard Spring Boot + OpenTelemetry auto-instrumentation covers
basic metrics (HTTP request latency, error rates). Custom metrics (e.g., DIAL Storage read/write latency, config update
counter) deferred to implementation.

### GET /api/config

Returns the current config from DIAL Storage.

**Response:**

```json
{
  "configs": [
    {
      "name": "BIS",
      "description": "Bank for International Settlements",
      "versions": {
        "..."
      }
    }
  ],
  "agencies": [
    {
      "name": "BIS",
      "primaryRegistry": "BIS"
    },
    {
      "name": "IMF",
      "primaryRegistry": "IMF",
      "allowSubAgencies": true
    }
  ]
}
```

The response body is a `ProxyConfiguration` -- same shape as today (minus removed fields). No wrapper, no version
metadata.

### POST /api/config

Accepts a `ProxyConfiguration` body. Validates it, writes to DIAL Storage, and updates the in-memory config.

**Request body:** `ProxyConfiguration` (same format as the current `POST /api/config` on the proxy).

Request body size is limited to 1MB (configurable via `spring.servlet.multipart.max-request-size`).

**Validation (strict):**

- JSON must deserialize into a valid `ProxyConfiguration`
- `primaryRegistry` is mandatory on every agency entry -- reject if missing (see `AGENCY_ROUTING.md` for the
  `AgencyConfiguration` model definition)
- Referenced registry names in `agencies[].primaryRegistry` must exist in `configs[]`
- At least one registry must be defined
- Non-empty `agencies[]`
- `versions` present on each registry
- No duplicate registry names

Returns `400 Bad Request` with details on validation failure.

### GET /actuator/health

Spring Boot Actuator health endpoint. Checks DIAL Storage connectivity.

## Proxy Simplification

### Only 3 source types remain

After the config server is introduced, the proxy keeps only:

- **`CLASSPATH_RESOURCE`** -- for local development and E2E tests (loads from `sdmx_registries_config.json` on
  classpath)
- **`FILESYSTEM`** -- for local development with custom config files outside the classpath
- **`CONFIG_SERVER`** -- for production (polls config server)

Remove from the proxy:

- `ENV` source type and extractor
- `DIAL_STORAGE` source type and all related classes (`DialStorageConfigExtractorProxy`,
  `DialStorageProxyConfigurationWriter`, `DialStorageClient`, `FeignDialStorageClient`,
  `DialStorageConfigCache`, `RedisDialStorageConfigCache`, `CaffeineDialStorageConfigCache`,
  `DialStorageSourceProperties`, `DialStorageFeignApi`, `DialStorageUploadRequest`)

### Proxy as config client

New `ConfigServerConfigExtractor` implementation:

```java
@Component
@ConditionalOnProperty(name = "sdmxproxy.registry.config.source.type", havingValue = "CONFIG_SERVER")
public class ConfigServerConfigExtractor implements ProxyConfigurationExtractor {

    private final ConfigServerClient configServerClient;  // Feign client
    private final AtomicReference<ProxyConfiguration> cachedConfig = new AtomicReference<>();

    @PostConstruct
    void init() {
        // Pre-populate with classpath config as baseline -- ensures getConfiguration() never returns null
        cachedConfig.set(loadClasspathConfig());
    }

    @Override
    public ProxyConfigurationSourceType supports() {
        return ProxyConfigurationSourceType.CONFIG_SERVER;
    }

    @Override
    public ProxyConfiguration getConfiguration() {
        return cachedConfig.get();  // Never null -- initialized from classpath, updated by polling
    }

    // Called by ConfigServerPoller on schedule
    void pollForUpdate() {
        ProxyConfiguration config = configServerClient.fetchConfig();
        if (config != null) {
            cachedConfig.set(config);
        }
    }

    private ProxyConfiguration loadClasspathConfig() {
        // Load from sdmx_registries_config.json on classpath (same logic as ClasspathResourceConfigExtractor)
        // This is always available -- it's bundled in the JAR
    }
}
```

The `ConfigServerClient` follows the existing Feign client pattern: a `ConfigServerFeignApi` interface with
`@RequestLine` annotations, built via `Feign.builder()` with the OkHttp client, same as other Feign clients in
the proxy.

### ConfigServerPoller

```java
@Component
@ConditionalOnProperty(name = "sdmxproxy.registry.config.source.type", havingValue = "CONFIG_SERVER")
public class ConfigServerPoller {

    private final ConfigServerConfigExtractor extractor;

    @Scheduled(fixedDelayString = "${sdmxproxy.registry.config.source.config-server.poll-interval:PT30S}")
    public void poll() {
        extractor.pollForUpdate();
    }
}
```

### What happens to ConfigController on the proxy

- `GET /api/config` remains for debugging (returns the locally cached config)
- `POST /api/config` is removed entirely from the proxy

### Startup behavior

The proxy does NOT fetch config from the config server on startup. There is no fail-fast, no retry/backoff, and no
startup dependency on the config server.

- The proxy always starts with its classpath config (loaded in `@PostConstruct` as the initial `cachedConfig` value)
- `ConfigServerPoller` runs on its regular schedule and picks up config server updates when they come
- Until the first successful poll, the proxy serves using the classpath config
- No startup ordering requirement between the proxy and config server

### Runtime unavailability

If the config server becomes unreachable during polling:

- Proxy continues serving with last known good config (in-memory `AtomicReference`, or classpath config if no
  successful poll has occurred yet)
- Log warnings on each failed poll
- Resume normal operation when server recovers

During config updates, replicas converge within one poll interval (default 30s). For destructive config changes
(removing an agency), ensure all replicas have updated before relying on the new config. In practice, wait at least
one full poll interval after pushing a config update before assuming all replicas are consistent.

## Security

### Internal service

The config server is an internal microservice, not exposed publicly. It sits behind the K8s cluster boundary.

### API key auth

Simple API key in the `X-API-Key` HTTP header. Sufficient for internal services.

```yaml
# Config server application.yaml
sdmxproxy.configserver.auth:
  api-key: ${CONFIG_SERVER_API_KEY}

# Proxy application.yaml
sdmxproxy.registry.config.source.config-server:
  url: http://sdmx-config-server:8060
  api-key: ${CONFIG_SERVER_API_KEY}
  poll-interval: PT30S
```

The API key is stored as a K8s Secret and injected via environment variable.

## Configuration

Key `application.yaml` properties for the config server:

```yaml
sdmxproxy:
  configserver:
    auth:
      api-key: ${CONFIG_SERVER_API_KEY}        # API key for authenticating incoming requests
    dial-storage:
      base-url: ${DIAL_STORAGE_BASE_URL}       # DIAL Storage base URL
      api-key: ${DIAL_STORAGE_API_KEY}         # DIAL Storage API key
```

## Migration Path

### Phase 1: Config server as additional source type (no breaking changes)

1. Build `sdmx-config-server` module with DIAL Storage backend and REST API
2. Add `CONFIG_SERVER` to `ProxyConfigurationSourceType` enum
3. Implement `ConfigServerConfigExtractor` + `ConfigServerPoller` in sdmx-proxy
4. Deploy config server alongside existing setup
5. Switch proxy to `CONFIG_SERVER` source type

All existing source types continue to work during migration.

**Model compatibility:** The config server is model-agnostic -- it stores and serves `ProxyConfiguration` JSON without
field-level validation beyond structural integrity (valid JSON, deserializable into `ProxyConfiguration`, referential
checks). Model changes from `AGENCY_ROUTING.md` (removing `secondaryRegistries`, adding `allowSubAgencies`) can ship
independently. The config server works with both the old and new model shapes.

### Phase 2: Proxy simplification

1. Remove `POST /api/config` from the proxy (`GET /api/config` remains for debugging)
2. Remove `ENV`, `DIAL_STORAGE` source types and all related classes from the proxy

E2E tests continue using `CLASSPATH_RESOURCE` -- no config server needed for E2E. Config server gets its own
integration tests.

**E2E test migration:** E2E tests currently push runtime config via `POST /api/config` on the proxy
(see `BaseRegistryTestSuite`). Before removing this endpoint, E2E tests must be refactored to use
`CLASSPATH_RESOURCE` with test-specific config files, or a test-only config endpoint must be retained.

## Deployment

### Config server

- **Replicas:** 1 is sufficient (config reads are infrequent, not latency-critical). For HA: 2 replicas behind a K8s
  Service.
- **Port:** 8060 (proxy is 8050).
- **Health check:** `GET /actuator/health` (checks DIAL Storage connectivity)
- **Resources:** minimal (256-512MB heap, low CPU)
- **OpenTelemetry:** must be connected (same setup as the proxy)

### What happens if config server is down

| Scenario                   | Behavior                                                                                           |
|----------------------------|----------------------------------------------------------------------------------------------------|
| Proxy startup, server down | Proxy starts normally with classpath config. Picks up config server config on next successful poll |
| Proxy runtime, server down | Continue with last known good config. Log warnings. Resume on recovery                             |
| Config server restart      | Proxy detects change on next poll cycle (30s default)                                              |
| DIAL Storage down          | Config server returns 503. Proxy keeps last known good config                                      |

The config server is NOT in the hot path of SDMX requests. Brief downtime (minutes) is acceptable.

### Proxy configuration

```yaml
# application.yaml additions for CONFIG_SERVER mode
sdmxproxy:
  registry:
    config:
      source:
        type: CONFIG_SERVER
        config-server:
          url: http://sdmx-config-server:8060
          api-key: ${CONFIG_SERVER_API_KEY}
          poll-interval: PT30S
```

## Files Affected

### New files (sdmx-config-server module)

| File                                                                | Description                                      |
|---------------------------------------------------------------------|--------------------------------------------------|
| `sdmx-config-server/build.gradle`                                   | Gradle build with Spring Boot, DIAL Storage deps |
| `sdmx-config-server/src/.../api/ConfigServerApi.java`               | OpenAPI interface                                |
| `sdmx-config-server/src/.../controller/ConfigServerController.java` | REST controller                                  |
| `sdmx-config-server/src/.../service/ConfigService.java`             | Config read/write via DIAL Storage               |
| `sdmx-config-server/src/.../dialstorage/*`                          | DIAL Storage client classes (moved from proxy)   |
| `sdmx-config-server/src/main/resources/application.yaml`            | Config server config                             |

### New files (sdmx-proxy)

| File                               | Description                                                               |
|------------------------------------|---------------------------------------------------------------------------|
| `ConfigServerConfigExtractor.java` | Config server client extractor                                            |
| `ConfigServerPoller.java`          | Scheduled polling component                                               |
| `ConfigServerClient.java`          | Feign client interface for config server (follows existing Feign pattern) |

### Modified files (sdmx-proxy)

| File                                | Change                                                      |
|-------------------------------------|-------------------------------------------------------------|
| `ProxyConfigurationSourceType.java` | Add `CONFIG_SERVER`; remove `ENV`, `DIAL_STORAGE` (phase 2) |
| `settings.gradle`                   | Include `sdmx-config-server` module                         |
| `application.yaml`                  | Add config-server properties                                |
| `ConfigController.java`             | Phase 2: remove `POST /api/config` endpoint                 |

### Removed files (sdmx-proxy, phase 2)

| File                                       | Reason                 |
|--------------------------------------------|------------------------|
| `DialStorageConfigExtractorProxy.java`     | Moved to config server |
| `DialStorageProxyConfigurationWriter.java` | Moved to config server |
| `DialStorageClient.java`                   | Moved to config server |
| `FeignDialStorageClient.java`              | Moved to config server |
| `DialStorageFeignApi.java`                 | Moved to config server |
| `DialStorageSourceProperties.java`         | Moved to config server |
| `DialStorageUploadRequest.java`            | Moved to config server |
| `DialStorageConfigCache.java`              | No longer needed       |
| `RedisDialStorageConfigCache.java`         | No longer needed       |
| `CaffeineDialStorageConfigCache.java`      | No longer needed       |
| `EnvProxyConfigExtractor.java`             | Source type removed    |

## Future Improvements

- **Config versioning and rollback.** The ability to version configs and roll back to a previous version is acknowledged
  as valuable but is not in the current scope. The classpath resource always serves as the baseline fallback. If a bad
  config is pushed via POST, the proxy continues to function (it falls back to classpath config if the config server
  becomes unavailable). A dedicated versioning/rollback mechanism can be added later if operational needs justify it.
- **ETag-based conditional polling.** Currently the proxy fetches the full config on every poll cycle. For the current
  scale this is fine (config JSON is small). If the number of registries grows or the poll interval decreases, ETag
  support (`If-None-Match` / `304 Not Modified`) can be added as an optimization.

## Open Questions

1. **Should the config server expose a management UI?** Or is API-only sufficient (admin uses curl/Postman/Swagger UI)?
