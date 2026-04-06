# SDMX Proxy Config Server

Spring Boot configuration server for managing SDMX proxy registry configurations. Provides a REST
API for the main `sdmx-proxy` application to fetch registry configuration at runtime, enabling
configuration updates without restarting the proxy.

Supports two storage backends: local filesystem and Dial Storage (S3-like API).

Runs on port **8060** by default.

## Environment variables

| Variable                        | Required                              | Description                                                          | Available Values            | Default values                        |
|---------------------------------|:-------------------------------------:|----------------------------------------------------------------------|-----------------------------|---------------------------------------|
| `CONFIG_SERVER_API_KEY`         | No                                    | API key for authenticating client requests to the config server      |                             | (empty)                               |
| `CONFIG_SERVER_SOURCE_TYPE`     | No                                    | Backend for storing registry configuration                           | `FILESYSTEM`, `DIAL_STORAGE` | `FILESYSTEM`                          |
| `CONFIG_SERVER_SOURCE_CONFIG_PATH` | No                                 | Path to the config file (filesystem path or Dial Storage object path) |                             | `config/sdmx_registries_config.json`  |
| `DIAL_STORAGE_BASE_URL`        | Yes, if `$CONFIG_SERVER_SOURCE_TYPE=DIAL_STORAGE` | Base URL for the Dial Storage API                         |                             | `http://localhost:9000`               |
| `DIAL_STORAGE_API_KEY`         | Yes, if `$CONFIG_SERVER_SOURCE_TYPE=DIAL_STORAGE` | API key for Dial Storage authentication                   |                             | (empty)                               |
| `FEIGN_LOG_LEVEL`              | No                                    | Feign HTTP client log level for the Dial Storage client              | `NONE`, `BASIC`, `HEADERS`, `FULL` |                                |
