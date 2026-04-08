# SDMX Proxy E2E Tests

End-to-end tests that spin up the SDMX proxy in a Docker container via
[Testcontainers](https://testcontainers.com/) and test against real upstream SDMX registries.
Tests use [allpairs4j](https://github.com/pavelicii/allpairs4j) for pairwise test case generation
and [RestAssured](https://rest-assured.io/) for HTTP assertions.

## Prerequisites

- Docker Engine (for Testcontainers)
- Internet access (tests call real upstream registries)
- A pre-built Docker image of sdmx-proxy (see [root README](../README.md) for build instructions)

## Environment variables

| Variable               | Required | Description                                                                                                            | Available Values | Default values |
|------------------------|:--------:|------------------------------------------------------------------------------------------------------------------------|------------------|----------------|
| `DOCKER_IMAGE_TAG`     | No       | Tag of the sdmx-proxy Docker image to test                                                                             |                  | `latest`       |
| `DOCKER_IMAGE_REGISTRY` | No      | Docker registry URL (e.g., `myregistry.azurecr.io`). If empty, Docker Hub is assumed.                                  |                  | (empty)        |
| `E2E_DOCKER_USER`      | No       | Docker registry username for pulling images from private registries. Required together with `E2E_DOCKER_PASS` and `DOCKER_IMAGE_REGISTRY`. |                  |                |
| `E2E_DOCKER_PASS`      | No       | Docker registry password. Required together with `E2E_DOCKER_USER` and `DOCKER_IMAGE_REGISTRY`.                        |                  |                |

## Running tests

Before running E2E tests, build the Docker image:

```powershell
./scripts/build-and-docker.ps1
```

Then run the tests:

```bash
# Run all E2E tests
./gradlew :sdmx-proxy-e2e:e2eTest

# Run tests for a specific registry
./gradlew :sdmx-proxy-e2e:e2eTest --tests "*.BIS_3_0_RegistryTestSuit"
./gradlew :sdmx-proxy-e2e:e2eTest --tests "*.IMF_3_0_RegistryTestSuit"
```
