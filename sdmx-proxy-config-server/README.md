# SDMX Proxy Config Server

Spring Boot configuration server for managing SDMX proxy registry configurations. Provides a REST
API for the main `sdmx-proxy` application to fetch registry configuration at runtime, enabling
configuration updates without restarting the proxy.

Supports two storage backends: local filesystem and Dial Storage (S3-like API).

When using the Dial Storage backend, the config server automatically seeds the storage from the
bundled default configuration on first startup if no configuration exists yet.

Runs on port **8060** by default.

## Environment variables

### Core

| Variable                           | Required                                          | Description                                                           | Available Values             | Default                               |
|------------------------------------|:-------------------------------------------------:|-----------------------------------------------------------------------|------------------------------|---------------------------------------|
| `CONFIG_SERVER_API_KEY`            | No                                                | API key for authenticating client requests to the config server       |                              | (empty)                               |
| `CONFIG_SERVER_SOURCE_TYPE`        | No                                                | Backend for storing registry configuration                            | `FILESYSTEM`, `DIAL_STORAGE` | `FILESYSTEM`                          |
| `CONFIG_SERVER_SOURCE_CONFIG_PATH` | No                                                | Path to the config file (filesystem path or Dial Storage object path) |                              | `config/sdmx_registries_config.json`  |
| `DIAL_STORAGE_BASE_URL`           | Yes, if `$CONFIG_SERVER_SOURCE_TYPE=DIAL_STORAGE` | Base URL for the Dial Storage API                                     |                              | `http://localhost:9000`               |
| `DIAL_STORAGE_API_KEY`            | Yes, if `$CONFIG_SERVER_SOURCE_TYPE=DIAL_STORAGE` | API key for Dial Storage authentication                               |                              | (empty)                               |
| `CONFIG_SERVER_FORCE_SEED`        | No                                                | If `true`, overwrite stored configuration from the bundled classpath default on startup (`DIAL_STORAGE` only; hard-fails otherwise). See "Forced reseed" below. | `true`, `false`              | `false`                               |
| `FEIGN_LOG_LEVEL`                 | No                                                | Feign HTTP client log level for the Dial Storage client               | `NONE`, `BASIC`, `HEADERS`, `FULL` |                                 |

### Forced reseed

`CONFIG_SERVER_FORCE_SEED=true` forces the config server to overwrite whatever is in Dial
Storage with the bundled `sdmx_registries_config.json` on every startup while the flag is
set. Use this to push an updated bundled configuration into an existing environment.

**Recommended deploy flow:**

1. Set `CONFIG_SERVER_FORCE_SEED=true` in the deployment manifest for the next rollout.
2. After the deployment is healthy, flip the variable back to `false` (or remove it) and
   redeploy.

**Footguns:**

- **Every restart while the flag is on wipes the stored configuration**, including any
  manual changes made via `POST /config` or directly in Dial Storage. A WARN-level log is
  emitted on every startup while the flag is on to make "we forgot to unset it" visible
  in alerting.
- The flag only applies to `CONFIG_SERVER_SOURCE_TYPE=DIAL_STORAGE`. With any other
  source type the server will fail to start — the filesystem file is the source of truth
  in filesystem mode, so "reseed from classpath" has no meaning there.
- A forced reseed discards any environment-specific overrides that are not part of the
  bundled default.
- If the forced reseed itself fails (bundled resource missing, validation error, Dial
  Storage write error), startup aborts — the pod will crashloop until the flag is
  cleared or the underlying failure is fixed.

### OpenTelemetry

| Variable                       | Required | Description                        | Available Values | Default |
|--------------------------------|:--------:|------------------------------------|------------------|---------|
| `OTEL_SDK_DISABLED`            | No       | Disable the OpenTelemetry SDK      | `true`, `false`  | `true`  |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | Yes, if OTel enabled | OTLP collector endpoint  |                  |         |
| `OTEL_EXPORTER_OTLP_PROTOCOL` | No       | OTLP transport protocol            | `grpc`, `http`   | `grpc`  |
| `OTEL_LOGS_EXPORTER`          | No       | Logs exporter                      | `otlp`, `none`   | `otlp`  |
| `OTEL_TRACES_EXPORTER`        | No       | Traces exporter                    | `otlp`, `none`   | `otlp`  |
| `OTEL_METRICS_EXPORTER`       | No       | Metrics exporter                   | `otlp`, `none`   | `otlp`  |

### Logging

Log4j2 is used with the following log format:

```
<timestamp> <level> trace_id: <trace_id> span_id=<span_id> [<thread>] <class>: <message>
```

`trace_id` and `span_id` are automatically injected by the OpenTelemetry Log4j2 integration when
the OTel SDK is enabled. When disabled, these fields are empty.

| Variable        | Required | Description                            | Available Values | Default |
|-----------------|:--------:|----------------------------------------|------------------|---------|
| `ROOT_LOG_LEVEL`| No       | Root log level                         | `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR` | `INFO`  |
| `APP_LOG_LEVEL` | No       | Log level for `com.epam` packages      | `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR` | `DEBUG` |
