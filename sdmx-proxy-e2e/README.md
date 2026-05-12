# SDMX Proxy E2E Tests

End-to-end tests that exercise a running sdmx-proxy over HTTP. Tests use
[allpairs4j](https://github.com/pavelicii/allpairs4j) for pairwise test case
generation and [RestAssured](https://rest-assured.io/) for HTTP assertions.

The tests do **not** start the proxy. They assume the proxy is already running
at a base URL supplied externally — either a local `bootRun` or a deployed
review environment (see [design 019](../docs/designs/019-e2e-against-review-env/DESIGN.md)).

## Prerequisites

- A running sdmx-proxy reachable at some URL. Options:
    - `./gradlew :sdmx-proxy:bootRun` in another shell (listens on
      `http://localhost:8050` by default).
    - A deployed review environment whose URL is passed via `E2E_HOST`.
- Internet access (the tests call real upstream registries through the proxy).

The proxy must be started with `SDMXPROXY_TEST_CONFIG_ENDPOINT_ENABLED=true`
so each suite can POST its `ProxyConfiguration` to `/config` in `@BeforeAll`.

## Configuration

| Variable                  | Required | Description                                                                                              | Default                  |
|---------------------------|:--------:|----------------------------------------------------------------------------------------------------------|--------------------------|
| `E2E_HOST`                | No       | Base URL of the proxy under test (scheme + host + optional port). Trailing slashes are stripped.         | `http://localhost:8050`  |
| `sdmxproxy.e2e.host` (`-D`) | No     | Same as `E2E_HOST` but as a JVM system property. Takes precedence over the env var when both are set.    | (unset)                  |

## Running tests

```bash
# Against a local bootRun on http://localhost:8050:
./gradlew :sdmx-proxy-e2e:e2eTest

# Against a deployed review env:
./gradlew :sdmx-proxy-e2e:e2eTest -Dsdmxproxy.e2e.host=https://chat-sdmx-proxy-pr-NNN.<base-domain>

# Run tests for a specific registry:
./gradlew :sdmx-proxy-e2e:e2eTest --tests "*.BIS_3_0_RegistryTestSuit"
./gradlew :sdmx-proxy-e2e:e2eTest --tests "*.IMF_3_0_RegistryTestSuit"
```

Wait until the proxy logs `Started SdmxProxyApplication` before launching the
test task — there is no health-wait inside the test module, so launching too
early results in a connection refused on the first request.

## See also

`scripts/build-and-docker.ps1` builds a local Docker image of the proxy. It is
no longer an E2E prerequisite, but remains useful for running the proxy via
`docker compose` or for manual smoke testing of the built image.
