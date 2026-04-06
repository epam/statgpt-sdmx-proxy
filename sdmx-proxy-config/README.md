# SDMX Proxy Config

Pure Lombok data classes representing the registry configuration model. This module has no Spring
dependency and no runtime behavior. It is shared as a compile dependency by:

- `sdmx-proxy` (main application)
- `sdmx-proxy-config-server` (configuration server)
- `sdmx-proxy-e2e` (end-to-end tests)

## Environment variables

This module does not use any environment variables. It contains only data classes (POJOs).
