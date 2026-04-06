# StatGPT SDMX Proxy

This repository contains the SDMX API Proxy for the StatGPT platform. The proxy acts as a unified
[SDMX 3.0 REST API](https://github.com/sdmx-twg/sdmx-rest/tree/master/doc) facade in front of multiple upstream SDMX
registries (IMF, BIS, and others). Consumers always talk to a single SDMX 3.0 REST API, regardless of whether the
underlying registry speaks SDMX 2.1 or SDMX 3.0.

More information about StatGPT and its architecture can be found in
the [documentation repository](https://github.com/epam/statgpt).

## What it does

1. **Protocol translation** -- accepts SDMX REST 3.0 requests, translates them to SDMX 2.1 or 3.0 depending on the
   upstream registry, and converts the response back to the format requested by the client.

2. **Registry routing** -- determines which upstream registry to call based on the `agencyID` in the request. Supports
   hierarchical agency matching (e.g., `IMF.STA.DS` routes to the IMF registry) and a synthetic AgencyScheme endpoint
   for clients to discover available agencies.

3. **Format conversion** -- converts between SDMX data, structure, and availability formats (JSON-to-JSON, XML-to-JSON,
   JSON-to-XML, etc.) using the sdmx-core and jsdmx libraries.

4. **Response fixing (fixtures)** -- applies configurable patches to fix known defects in upstream registry
   responses before parsing and conversion.

5. **Caching** -- two-layer caching (raw parsed structures and ready-to-serve structure responses) backed by Caffeine (local) or
   Redis (distributed).

6. **Resilience** -- Feign HTTP clients wrapped with Resilience4j circuit breaker, retry with exponential backoff, and
   optional per-registry rate limiting.

7. **Observability** -- OpenTelemetry integration (traces, metrics, logs) with OTLP export and correlation ID
   propagation.

## Supported registries

The proxy ships with built-in configurations for the following SDMX registries:

| Registry | Organization                        | SDMX version | Structure formats        | Data formats                  |
|----------|-------------------------------------|--------------|--------------------------|-------------------------------|
| BIS      | Bank for International Settlements  | 3.0          | SDMX-JSON 2.0.0         | SDMX-JSON 1.0.0, CSV 2.0.0   |
| IMF      | International Monetary Fund         | 3.0          | SDMX-JSON 2.0.0         | SDMX-JSON 2.0.0, CSV 2.0.0   |

Additional registries can be added through the JSON configuration file without code changes.

### Client output formats

Regardless of what format the upstream registry returns, the proxy can convert and serve responses in the following
formats per endpoint:

| Format              | Structure | Data | Availability |
|---------------------|-----------|------|--------------|
| SDMX-JSON 2.0.0    | Yes       | Yes  | Yes          |
| SDMX-JSON 1.0.0    | --        | Yes  | --           |
| SDMX-ML 2.1 (XML)  | Yes       | --   | --           |
| SDMX-ML 3.0 (XML)  | --        | Yes  | --           |
| CSV 2.0.0           | --        | Yes  | --           |

## Technological stack

The application is written in Java 25 and uses the following main technologies:

| Technology                                                          | Purpose                                        |
|---------------------------------------------------------------------|-------------------------------------------------|
| [Spring Boot 4.0](https://spring.io/projects/spring-boot)          | Application framework                           |
| [OpenFeign](https://github.com/OpenFeign/feign)                    | Declarative HTTP clients for upstream registries |
| [Resilience4j](https://resilience4j.readme.io/)                    | Circuit breaker, retry, rate limiting            |
| [Caffeine](https://github.com/ben-manes/caffeine)                  | High-performance local caching                   |
| [Spring Data Redis](https://spring.io/projects/spring-data-redis)  | Distributed caching                              |
| [sdmx-core (fusion v2.3.9)](lib-repo/sdmxsource)                   | SDMX parsing and format conversion               |
| [OpenTelemetry](https://opentelemetry.io/)                          | Observability (traces, metrics, logs)            |
| [SpringDoc OpenAPI](https://springdoc.org/)                         | API documentation (Swagger UI)                   |
| [OkHttp](https://square.github.io/okhttp/)                         | HTTP transport for Feign clients                 |
| [Lombok](https://projectlombok.org/)                                | Boilerplate reduction                            |
| [Log4j2](https://logging.apache.org/log4j/2.x/)                    | Logging framework                                |

## Project structure

```
statgpt-sdmx-proxy/
├── sdmx-proxy/                 # Main Spring Boot application module
├── sdmx-proxy-config/          # Pure data classes (no Spring dependency), shared with config server and E2E tests
├── sdmx-proxy-config-server/   # Configuration server module
├── sdmx-proxy-e2e/             # End-to-end tests (Testcontainers + RestAssured)
├── lib-repo/sdmxsource/        # Local JARs of sdmx-core (fusion v2.3.9)
├── compose/                    # Docker Compose configurations (Redis, OpenTelemetry)
├── config/                     # Checkstyle configuration
├── docs/                       # Design documents and technical documentation
└── scripts/                    # Build and utility scripts
```

* `sdmx-proxy/` -- main application that exposes the SDMX 3.0 REST API, handles routing, conversion, caching, and
  resilience.
* `sdmx-proxy-config/` -- pure Lombok data classes representing the registry configuration model. Shared between the
  main app, config server and the E2E test module.
* `sdmx-proxy-config-server/` -- configuration server for managing registry configurations.
* `sdmx-proxy-e2e/` -- end-to-end tests that spin up the proxy in a Docker container via Testcontainers and test against
  real upstream registries.
* `lib-repo/sdmxsource/` -- local JAR files for the sdmx-core library (fusion v2.3.9)
* `compose/` -- Docker Compose files for local development (Redis, OpenTelemetry Collector).
* `docs/` -- design documents, resilience documentation, and technical references.

## Environment variables

Each module documents its own environment variables in its README:

- [sdmx-proxy/README.md](sdmx-proxy/README.md) -- main application (cache, Redis, OpenTelemetry, config source, Feign)
- [sdmx-proxy-config-server/README.md](sdmx-proxy-config-server/README.md) -- config server (authentication, source type, Dial Storage)
- [sdmx-proxy-e2e/README.md](sdmx-proxy-e2e/README.md) -- E2E tests (Docker image configuration)

The `sdmx-proxy-config` module is a pure data library with no environment variables
([sdmx-proxy-config/README.md](sdmx-proxy-config/README.md)).

### Build and CI

The following environment variables are used by the Gradle build system and are not needed at runtime:

| Variable                 | Required | Description                        | Default values                            |
|--------------------------|:--------:|------------------------------------|-------------------------------------------|
| `ARTIFACTORY_USER`       | No       | Artifactory username for publishing |                                          |
| `ARTIFACTORY_PASS`       | No       | Artifactory password for publishing |                                          |
| `NEXUS_USER`             | No       | Nexus repository username           |                                          |
| `NEXUS_PASS`             | No       | Nexus repository password           |                                          |
| `MAVEN_PROXY_REPOSITORY` | No       | Maven proxy repository URL          | `https://repo.maven.apache.org/maven2/` |

## Local setup

### Prerequisites

#### 1. Install Java 25

* MacOS (using Homebrew): `brew install openjdk@25`
* Windows (using [SDKMAN](https://sdkman.io/)): `sdk install java 25-open`
* Windows or MacOS: download from [Oracle](https://www.oracle.com/java/technologies/downloads/) or
  [Adoptium](https://adoptium.net/)
* Make sure that `java` is in the PATH (run `java --version`).

#### 2. Install Docker Engine and Docker Compose

Required for E2E tests (Testcontainers) and for running Redis locally.

* [Docker Engine and Docker Compose on Linux](https://docs.docker.com/engine/install/)
* [Rancher Desktop](https://rancherdesktop.io/) on Windows or MacOS
* [Docker Desktop](https://www.docker.com/products/docker-desktop/)

**Note:** Gradle Wrapper (`gradlew`) is included in the repository, so you do not need to install Gradle separately.

---

### Setup

#### 1. Clone the repository

```bash
git clone https://github.com/epam/statgpt-sdmx-proxy.git
cd statgpt-sdmx-proxy
```

#### 2. Build the project

```bash
./gradlew clean build
```

This compiles the code, runs Checkstyle, and executes unit tests.

To build without running tests:

```bash
./gradlew clean build -x test
```

#### 3. (Optional) Start infrastructure services

If you need Redis or the OpenTelemetry Collector for local development:

```bash
docker compose -f compose/docker-compose.yml up -d
```

This starts:
* **Redis** on port 6379 (password: `local_password`)
* **OpenTelemetry Collector** on ports 4317 (gRPC) and 4318 (HTTP)

## Run the proxy locally

```bash
./gradlew :sdmx-proxy:bootRun
```

The application starts on port **8050** by default. Once running:

* **API:** `http://localhost:8050/structure/dataflow/*/latest`
* **Health check:** `http://localhost:8050/health`
* **Swagger UI:** `http://localhost:8050/swagger-ui.html`
* **API docs:** `http://localhost:8050/api-docs`

## Utils for development

### 1. Run Checkstyle

Checkstyle is configured with a Google Java Style-based ruleset and runs automatically during `./gradlew build`.
To run it manually:

```bash
./gradlew checkstyleMain checkstyleTest
```

### 2. Pre-commit hooks

The project uses [pre-commit](https://pre-commit.com/) hooks to enforce basic file hygiene (trailing whitespace,
end-of-file newlines, YAML validation, consistent line endings).

To install:

```bash
pip install pre-commit
pre-commit install
```

### 3. Build Docker-ready artifacts

```bash
./gradlew :sdmx-proxy:prepareFilesForDocker
```

## Run tests

### Unit tests

```bash
./gradlew :sdmx-proxy:test
```

To run a specific test class:

```bash
./gradlew :sdmx-proxy:test --tests "com.epam.sdmxproxy.services.translator.QueryTranslatorImplTest"
```

### End-to-end tests

E2E tests use [Testcontainers](https://testcontainers.com/) to spin up the proxy in a Docker container and test against
real upstream registries. They require **Docker** and **internet access**.

Before running E2E tests, build the Docker image:

```powershell
./scripts/build-and-docker.ps1
```

This builds the project, prepares Docker artifacts, and tags the image as `statgpt/statgpt-sdmx-proxy:local`.

Then run the tests:

```bash
# Run all E2E tests
./gradlew :sdmx-proxy-e2e:e2eTest

# Run tests for a specific registry
./gradlew :sdmx-proxy-e2e:e2eTest --tests "*.BIS_3_0_RegistryTestSuit"
./gradlew :sdmx-proxy-e2e:e2eTest --tests "*.IMF_3_0_RegistryTestSuit"
```

E2E tests use **pairwise combination** (via [allpairs4j](https://github.com/pavelicii/allpairs4j)) to generate 100-200+
test cases per registry from: artefact URNs, media types, return formats, reference details, and query details.

## Current limitations

- **No fan-out queries** -- wildcard agency (`agencyID=*`) and comma-separated agencies (`agencyID=BIS,IMF`) are not
  supported and return 501. Clients should use the synthetic AgencyScheme endpoint (`GET /structure/agencyscheme`) to
  discover available agencies and query them individually.
- **Partial XML support** -- see the [client output formats](#client-output-formats) table for details. Structure
  responses support SDMX-ML 2.1 but not 3.0. Data responses support SDMX-ML 3.0 but not 2.1. Availability responses
  are JSON-only.
- **SDMX 2.1 upstreams are untested** -- the proxy is designed to connect to SDMX 2.1 registries (code paths exist for
  protocol translation), but no 2.1 upstream is currently configured. Connecting to a 2.1 registry may require
  additional testing and tweaking.
- **Client-facing API is SDMX 3.0 only** -- the proxy does not expose an SDMX 2.1 REST API to consumers.

## License

This project is licensed under the MIT License -- see the [LICENSE](LICENSE) file for details.

Copyright (c) 2026 EPAM Systems
