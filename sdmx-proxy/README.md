# SDMX Proxy

Main Spring Boot application module that exposes the unified SDMX 3.0 REST API. Handles registry
routing, protocol translation (SDMX 2.1 / 3.0), format conversion (JSON, XML, CSV), caching,
resilience, and observability.

Runs on port **8050** by default.

## Environment variables

All variables are optional unless noted otherwise. Spring Boot relaxed binding applies -- for
example, `sdmxproxy.registry.config.source.type` can be set via `SDMXPROXY_REGISTRY_CONFIG_SOURCE_TYPE`.

### Config source

| Variable                              | Required                          | Description                                                            | Available Values                            | Default values       |
|---------------------------------------|:---------------------------------:|------------------------------------------------------------------------|---------------------------------------------|----------------------|
| `SDMXPROXY_REGISTRY_CONFIG_SOURCE_TYPE` | No                              | How registry configuration is loaded                                   | `CLASSPATH_RESOURCE`, `FILESYSTEM`, `CONFIG_SERVER` | `CLASSPATH_RESOURCE` |
| `SDMXPROXY_REGISTRY_CONFIG_SOURCE_FILENAME` | Yes, if type = `FILESYSTEM` | Filesystem path to the registry config JSON file                       |                                             |                      |

#### Config Server source (when type = `CONFIG_SERVER`)

When config source type is set to `CONFIG_SERVER`, the proxy fetches configuration from a running
`sdmx-proxy-config-server` instance.

| Variable                                                      | Required                           | Description                                                | Available Values | Default values |
|---------------------------------------------------------------|:----------------------------------:|------------------------------------------------------------|------------------|----------------|
| `SDMXPROXY_REGISTRY_CONFIG_SOURCE_CONFIG_SERVER_URL`          | Yes, if type = `CONFIG_SERVER`     | Config Server URL (e.g., `http://sdmx-config-server:8060`) |                  |                |
| `SDMXPROXY_REGISTRY_CONFIG_SOURCE_CONFIG_SERVER_API_KEY`      | Yes, if type = `CONFIG_SERVER`     | API key for Config Server authentication                   |                  |                |
| `SDMXPROXY_REGISTRY_CONFIG_SOURCE_CONFIG_SERVER_POLL_INTERVAL` | No                                | Polling interval for configuration updates (ISO 8601 duration) |              | `PT30S`        |

### Cache

| Variable     | Required | Description                                   | Available Values  | Default values |
|--------------|:--------:|-----------------------------------------------|-------------------|----------------|
| `CACHE_MODE` | No       | Cache backend: in-memory Caffeine or Redis     | `LOCAL`, `REDIS`  | `LOCAL`        |

When `CACHE_MODE=LOCAL`, an in-memory Caffeine cache is used. No further configuration is needed.

When `CACHE_MODE=REDIS`, the proxy connects to a Redis instance. Authentication depends on the
cloud provider (see below).

### Redis connection (when `CACHE_MODE=REDIS`)

| Variable              | Required                       | Description                                            | Available Values               | Default values |
|-----------------------|:------------------------------:|--------------------------------------------------------|--------------------------------|----------------|
| `REDIS_HOST`          | No                             | Redis hostname                                         |                                | `localhost`    |
| `REDIS_PORT`          | No                             | Redis port                                             |                                | `6379`         |
| `REDIS_PASSWORD`      | No                             | Static password (used when `REDIS_AUTH_PROVIDER=NONE`) |                                | (empty)        |
| `REDIS_SSL`           | No                             | Enable TLS for Redis connection                        | `true`, `false`                | `false`        |
| `REDIS_AUTH_PROVIDER` | No                             | Cloud authentication provider                          | `NONE`, `AZURE`, `AWS`, `GCP` | `NONE`         |

#### Azure Cache for Redis (when `REDIS_AUTH_PROVIDER=AZURE`)

Uses `DefaultAzureCredential` to authenticate via Azure Managed Identity. No extra environment
variables are needed -- identity is resolved automatically from the Azure environment (MSI,
Workload Identity, etc.). SSL is force-enabled when a cloud provider is configured.

#### AWS ElastiCache (when `REDIS_AUTH_PROVIDER=AWS`)

Uses `DefaultAWSCredentialsProviderChain` to obtain AWS credentials, then generates a
SigV4-presigned token for ElastiCache IAM auth.

| Variable                 | Required                      | Description                                | Available Values  | Default values |
|--------------------------|:-----------------------------:|--------------------------------------------|-------------------|----------------|
| `REDIS_AWS_USER_ID`      | Yes, if `$REDIS_AUTH_PROVIDER=AWS` | ElastiCache IAM user ID               |                   |                |
| `REDIS_AWS_REGION`       | Yes, if `$REDIS_AUTH_PROVIDER=AWS` | AWS region (e.g., `us-east-1`)        |                   |                |
| `REDIS_AWS_CLUSTER_NAME` | Yes, if `$REDIS_AUTH_PROVIDER=AWS` | ElastiCache cluster/replication group name |              |                |
| `REDIS_AWS_SERVERLESS`   | No                            | Set to `true` for ElastiCache Serverless   | `true`, `false`   | `false`        |

#### GCP Memorystore (when `REDIS_AUTH_PROVIDER=GCP`)

Uses Application Default Credentials to generate a short-lived access token for the configured
service account.

| Variable                    | Required                      | Description                                                            | Available Values | Default values |
|-----------------------------|:-----------------------------:|------------------------------------------------------------------------|------------------|----------------|
| `REDIS_GCP_SERVICE_ACCOUNT` | Yes, if `$REDIS_AUTH_PROVIDER=GCP` | Full service account email (e.g., `sa@project.iam.gserviceaccount.com`) |                 |                |

### OpenTelemetry

| Variable                       | Required | Description                            | Available Values | Default values |
|--------------------------------|:--------:|----------------------------------------|------------------|----------------|
| `OTEL_SDK_DISABLED`            | No       | Set to `false` to enable OpenTelemetry | `true`, `false`  | `true`         |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | No       | OTLP exporter endpoint                 |                  |                |
| `OTEL_EXPORTER_OTLP_PROTOCOL` | No       | OTLP protocol                          | `grpc`, `http/protobuf` | `grpc`  |
| `OTEL_LOGS_EXPORTER`          | No       | Logs exporter type                     |                  | `otlp`         |
| `OTEL_TRACES_EXPORTER`        | No       | Traces exporter type                   |                  | `otlp`         |
| `OTEL_METRICS_EXPORTER`       | No       | Metrics exporter type                  |                  | `otlp`         |

### Feign logging

| Variable         | Required | Description                                         | Available Values                   | Default values |
|------------------|:--------:|-----------------------------------------------------|------------------------------------|----------------|
| `FEIGN_LOG_LEVEL` | No      | Feign HTTP client log level for upstream registries | `NONE`, `BASIC`, `HEADERS`, `FULL` |                |
