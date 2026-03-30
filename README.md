# SDMX API Proxy

## Prerequisites

Before running the application locally, ensure you have the following installed:

- **Java**: Java 25 (as specified in `build.gradle`) or compatible version
- **Gradle**: The project includes Gradle Wrapper (`gradlew`), so you don't need to install Gradle separately

## Environment variables

The following environment variables can be used for setup and configuration. All are optional unless noted.

### Config source type (config mode)

Proxy registry configuration can be loaded from different sources. Set the mode via configuration property (e.g. in
`application.yaml` or with an env variable):

- **Property:** `sdmxproxy.registry.config.source.type`
- **Possible values:**

| Value                | Description                                                                                                |
|----------------------|------------------------------------------------------------------------------------------------------------|
| `ENV`                | Configuration from environment variables                                                                   |
| `CLASSPATH_RESOURCE` | Configuration from a file on the classpath (default)                                                       |
| `FILESYSTEM`         | Configuration from a file path; set `sdmxproxy.registry.config.source.filename` to the path                |
| `DIAL_STORAGE`       | Configuration from Dial Storage (S3-like API); requires `dial-storage` and `filename` settings (see below) |

Example with env: `SDMXPROXY_REGISTRY_CONFIG_SOURCE_TYPE=DIAL_STORAGE` (Spring Boot maps
`sdmxproxy.registry.config.source.type` from `SDMXPROXY_REGISTRY_CONFIG_SOURCE_TYPE`).

### Cache

| Variable     | Description                                   | Default |
|--------------|-----------------------------------------------|---------|
| `CACHE_MODE` | Cache backend: `LOCAL` (in-memory) or `REDIS` | `LOCAL` |

When `CACHE_MODE=LOCAL`, an in-memory Caffeine cache is used. No further configuration is needed.

When `CACHE_MODE=REDIS`, the proxy connects to a Redis instance. Authentication depends on the environment.

#### Redis connection

| Variable              | Description                                            | Default     |
|-----------------------|--------------------------------------------------------|-------------|
| `REDIS_HOST`          | Redis hostname                                         | `localhost` |
| `REDIS_PORT`          | Redis port                                             | `6379`      |
| `REDIS_PASSWORD`      | Static password (used when `REDIS_AUTH_PROVIDER=NONE`) | (empty)     |
| `REDIS_SSL`           | Enable TLS (`true`/`false`)                            | `false`     |
| `REDIS_AUTH_PROVIDER` | Cloud auth provider: `NONE`, `AZURE`, `AWS`, or `GCP`  | `NONE`      |

#### Local Redis (no cloud auth)

For local development or self-hosted Redis with a static password:

```bash
CACHE_MODE=REDIS
REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_PASSWORD=your_password
# REDIS_SSL=true  # if your Redis requires TLS
```

No `REDIS_AUTH_PROVIDER` is needed (defaults to `NONE`).

#### Azure Cache for Redis (Managed Identity)

Uses `DefaultAzureCredential` to authenticate via Azure Managed Identity. No extra env vars beyond the
connection are needed -- identity is resolved automatically from the Azure environment (MSI, Workload Identity, etc.).

```bash
CACHE_MODE=REDIS
REDIS_HOST=your-redis.redis.cache.windows.net
REDIS_PORT=6380
REDIS_AUTH_PROVIDER=AZURE
```

SSL is force-enabled when a cloud provider is configured. The proxy extracts the `oid` claim from the Azure AD
token and uses it as the Redis username.

#### AWS ElastiCache (IAM authentication)

Uses `DefaultAWSCredentialsProviderChain` to obtain AWS credentials, then generates a SigV4-presigned token
for ElastiCache IAM auth.

| Variable                 | Description                                | Required     |
|--------------------------|--------------------------------------------|--------------|
| `REDIS_AWS_USER_ID`      | ElastiCache IAM user ID                    | Yes          |
| `REDIS_AWS_REGION`       | AWS region (e.g. `us-east-1`)              | Yes          |
| `REDIS_AWS_CLUSTER_NAME` | ElastiCache cluster/replication group name | Yes          |
| `REDIS_AWS_SERVERLESS`   | Set to `true` for ElastiCache Serverless   | No (`false`) |

```bash
CACHE_MODE=REDIS
REDIS_HOST=your-cluster.abcdef.use1.cache.amazonaws.com
REDIS_PORT=6379
REDIS_AUTH_PROVIDER=AWS
REDIS_AWS_USER_ID=my-iam-user
REDIS_AWS_REGION=us-east-1
REDIS_AWS_CLUSTER_NAME=my-cluster
# REDIS_AWS_SERVERLESS=true  # for serverless ElastiCache
```

#### GCP Memorystore (IAM authentication)

Uses Application Default Credentials to generate a short-lived access token for the configured service account.

| Variable                    | Description                                                            | Required |
|-----------------------------|------------------------------------------------------------------------|----------|
| `REDIS_GCP_SERVICE_ACCOUNT` | Full service account email (e.g. `sa@project.iam.gserviceaccount.com`) | Yes      |

```bash
CACHE_MODE=REDIS
REDIS_HOST=10.0.0.3
REDIS_PORT=6379
REDIS_AUTH_PROVIDER=GCP
REDIS_GCP_SERVICE_ACCOUNT=my-sa@my-project.iam.gserviceaccount.com
```

### Config source (Dial Storage)

When `sdmxproxy.registry.config.source.type` is set to `DIAL_STORAGE`, proxy configuration is read from and written to
Dial Storage (S3-like API). You can pass the API key via an environment variable and reference it in
`application.yaml` (e.g. `api-key: ${DIAL_STORAGE_API_KEY}`).

| Variable               | Description                                        |
|------------------------|----------------------------------------------------|
| `DIAL_STORAGE_API_KEY` | API key for Dial Storage (recommended for secrets) |

Other Dial Storage settings (base URL, path/filename) are configured in `application.yaml` under
`sdmxproxy.registry.config.source.dial-storage` and `sdmxproxy.registry.config.source.filename`.

### OpenTelemetry

| Variable                      | Description                            | Default |
|-------------------------------|----------------------------------------|---------|
| `OTEL_SDK_DISABLED`           | Set to `true` to disable OpenTelemetry | `true`  |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | OTLP exporter endpoint                 | —       |
| `OTEL_EXPORTER_OTLP_PROTOCOL` | OTLP protocol (e.g. `grpc`)            | `grpc`  |
| `OTEL_LOGS_EXPORTER`          | Logs exporter type                     | `otlp`  |
| `OTEL_TRACES_EXPORTER`        | Traces exporter type                   | `otlp`  |
| `OTEL_METRICS_EXPORTER`       | Metrics exporter type                  | `otlp`  |

## Local Setup

1. Copy epam jsdmx into src folder. -> will be obsolette after it will be opensource
2. Put new (10.*.*) jsdmx libs into lib-repo/sdmxsource
