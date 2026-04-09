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
| `FEIGN_LOG_LEVEL`                 | No                                                | Feign HTTP client log level for the Dial Storage client               | `NONE`, `BASIC`, `HEADERS`, `FULL` |                                 |

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
