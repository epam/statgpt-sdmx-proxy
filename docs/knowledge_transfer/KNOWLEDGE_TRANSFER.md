# SDMX Proxy — Knowledge Transfer Document

## 1. Project Overview

**sdmx-proxy** is a Spring Boot application that acts as a **unified SDMX 3.0 REST API facade** in front of multiple
upstream SDMX registries (IMF, BIS, and potentially others). Its consumers always talk to a single SDMX 3.0 REST API,
regardless of whether the underlying registry speaks SDMX 2.1 or SDMX 3.0.

### Main Responsibilities

1. **Protocol translation** — accepts SDMX REST 3.0 requests, translates them to SDMX 2.1 or 3.0 depending on what the
   upstream registry supports, and converts the response back to the format requested by the client (SDMX-JSON 2.0.0,
   SDMX-JSON 1.0.0, SDMX-ML 2.1, CSV, etc.).

2. **Registry routing** — determines which upstream registry to call based on the `agencyID` in the request. Each agency
   maps to exactly one registry. Supports fan-out when agencyID is `*` or comma-separated agencies span multiple
   registries.

3. **Format conversion** — converts between SDMX data/structure/availability formats using the sdmx-core library (fusion
   JARs v2.3.9). Supports JSON-to-JSON, XML-to-JSON, JSON-to-XML, etc.

4. **Response fixing (fixtures)** — applies configurable JSON patches to fix known defects in upstream registry
   responses before parsing/conversion. Fixtures are configured per-registry per-endpoint in the JSON config.

5. **Caching** — two-layer caching: raw parsed structures (SdmxBeans serialized to bytes) and ready-to-serve responses.
   Backends: Caffeine (local/single-instance) or Redis (distributed).

6. **Resilience** — Feign HTTP clients wrapped with Resilience4j: circuit breaker, retry with exponential backoff, and
   optional rate limiting per registry.

7. **Observability** — OpenTelemetry integration (traces, metrics, logs) with OTLP export. Correlation IDs propagated
   via traceParent.

This proxy is part of the **StatGPT** project — an AI-powered statistical data assistant that uses this proxy to access
SDMX data from international organizations.

---

## 2. Architecture

### 2.1 Module Layout

```
sdmx-proxy/                        # Git root / Gradle root
├── sdmx-proxy/                    # Main Spring Boot application module
│   ├── src/main/java/com/epam/sdmxproxy/
│   │   ├── api/                   # OpenAPI interface definitions (Spring @RequestMapping)
│   │   ├── controller/            # REST controllers implementing the API interfaces
│   │   ├── common/                # Shared DTOs, media type utils, mappers, filters
│   │   ├── configuration/         # Telemetry config (correlation IDs, trace context)
│   │   ├── exception/             # Custom exception classes
│   │   ├── registry/              # Feign clients, resilience, configuration loading
│   │   │   ├── api/               # Feign client interfaces & provider
│   │   │   └── configuration/     # Config extractors, writers, Dial Storage integration
│   │   ├── services/              # Core business logic
│   │   │   ├── adapter/           # AdapterRouter + GenericRegistryAdapter + conversion services
│   │   │   ├── cache/             # CacheService (Caffeine / Redis)
│   │   │   ├── filter/            # Filter validation & translation (3.0→2.1)
│   │   │   ├── fixture/           # Response patching (structure & availability fixtures)
│   │   │   ├── misc/              # DimensionService
│   │   │   ├── sdmxsource/        # Custom overrides of sdmx-core library classes
│   │   │   └── translator/        # QueryTranslator — the routing & translation brain
│   │   └── web/                   # WebMVC config, OpenAPI config, global exception handler
│   └── src/main/resources/
│       ├── application.yaml
│       └── sdmx_registries_config.json   # Default registry configurations
│
├── sdmx-proxy-config/             # Pure data classes module (no Spring dependency)
│   └── src/main/java/com/epam/sdmxproxy/configuration/data/
│       ├── ProxyConfiguration.java
│       ├── RegistryConfiguration.java
│       ├── VersionSpecificRegistryConfiguration.java
│       ├── EndpointConfiguration.java (+ Structure/Data/Availability subtypes)
│       ├── ReturnFormat.java
│       ├── SdmxVersion.java
│       └── fixture/               # FixtureType enums and FixtureConfiguration
│
├── sdmx-proxy-e2e/                # E2E tests (Testcontainers + RestAssured)
│   └── src/test/
│       ├── java/.../e2e/
│       │   ├── tests/framework/   # BaseRegistryTestSuite (pairwise parameterized)
│       │   ├── tests/registry/    # IMF_2_1, IMF_3_0, BIS_3_0 test suites
│       │   ├── tests/smoke/       # Smoke tests for structure/data/availability
│       │   └── support/           # Container management, REST client, validators
│       └── resources/.../registry/  # Per-registry JSON configs + test case configs
│
└── lib-repo/sdmxsource/           # Local JARs of sdmx-core (fusion) v2.3.9
```

### 2.2 Request Flow

```
Client (SDMX 3.0 REST)
  │
  ▼
Controller (e.g. DataQuery30Controller)
  │  - Extracts path/query params
  │  - Handles Accept header fallback (*/* → default SDMX media type)
  │  - For data/availability: fetches SdmxBeans first (structure query)
  │
  ▼
QueryTranslator (QueryTranslatorImpl)
  │  - Parses Accept header → MediaType + SdmxVersion
  │  - Selects registry + version based on agencyID
  │  - Determines registryReturnFormat
  │  - Validates and translates filters (3.0 c[] params → 2.1 key)
  │  - Produces TranslatedXxxQuery DTO
  │
  ▼
AdapterRouter (AdapterRouterImpl)
  │  - Checks cache (ready response or raw structures)
  │  - Decides: bypass (passthrough) vs conversion
  │  - For structures: applies StructureFixtureService
  │  - For availability: applies AvailabilityFixtureService
  │  - Returns StreamingResponseBody
  │
  ▼
GenericRegistryAdapter (GenericRegistryAdapterImpl)
  │  - Dispatches to Sdmx21*Client or Sdmx30*Client based on version
  │  - Handles version-specific URL/param differences
  │
  ▼
SdmxApiClientProvider (SdmxApiClientProviderImpl)
  │  - Lazily builds Feign clients per (class, baseUrl) pair
  │  - Wraps with Resilience4j decorators (circuit breaker, retry, rate limiter)
  │  - Caches built clients in ConcurrentHashMap
  │
  ▼
Upstream SDMX Registry (IMF, BIS, ...)
```

### 2.3 Key Design Decisions

| Decision                                 | Rationale                                                                                                                                                                                                     |
|------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Expose only SDMX 3.0 REST API**        | Consumers (AI agents) should not care which version the registry speaks                                                                                                                                       |
| **sdmx-proxy-config as separate module** | Pure Lombok data classes, no Spring dependency — shared between main app and E2E tests                                                                                                                        |
| **Local JAR files for sdmx-core**        | The sdmx-core (fusion) library is a proprietary EPAM fork, not on Maven Central. JARs live in `lib-repo/sdmxsource/`                                                                                          |
| **Fixture system**                       | Upstream registries have known bugs (invalid JSON structures). Fixtures patch the raw JSON before sdmx-core tries to parse it. Configured per-registry in the JSON config, applied as chain of responsibility |
| **Bypass vs Conversion**                 | If the registry natively returns the format the client wants AND bypass is enabled in config, skip conversion entirely. Otherwise, fetch in `defaultFormat` and convert                                       |
| **Fan-out for wildcard agency**          | When agencyID is `*`, the proxy queries ALL configured registries in parallel and merges the SdmxBeans                                                                                                        |
| **Streaming responses**                  | Controllers return `StreamingResponseBody` to avoid buffering entire responses in memory                                                                                                                      |
| **Custom sdmx-core overrides**           | Many classes in `services/sdmxsource/` are custom rewrites of sdmx-core internals to fix bugs or add features the library doesn't support                                                                     |

---

## 3. Key Files & Entry Points

### Application Entry

- `SdmxApiProxyApplication.java` — Spring Boot main class

### Controllers (REST Endpoints)

- `controller/SdmxStructure30Controller.java` — `GET /structure/{structureType}/{agencyId}/{resourceId}/{version}`
- `controller/DataQuery30Controller.java` — `GET /data/{context}/{agencyID}/{resourceID}/{version}/{key}`
- `controller/AvailabilityQuery30Controller.java` —
  `GET /availability/{context}/{agencyID}/{resourceID}/{version}/{key}/{componentId}` + POST variant
- `controller/ConfigController.java` — `GET/POST /config` — runtime configuration management
- `controller/HealthCheckController.java` — `GET /` and `GET /health`

### API Interfaces (OpenAPI-annotated)

- `api/SdmxStructure30Api.java`
- `api/DataQuery30Api.java`
- `api/AvailabilityQuery30Api.java`
- `api/ConfigApi.java`

### Core Business Logic

- **`services/translator/QueryTranslatorImpl.java`** (~900 lines) — THE central brain. Registry selection, version
  fallback, filter validation, filter-to-key merging for 2.1, format determination, fan-out logic. **Read this first.**
- **`services/adapter/AdapterRouterImpl.java`** (~360 lines) — orchestrates cache lookup, bypass vs conversion, fixture
  application, fan-out execution
- **`services/adapter/GenericRegistryAdapterImpl.java`** (~240 lines) — dispatches to correct Feign client based on SDMX
  version, handles 2.1 vs 3.0 URL/param differences

### Format Conversion

- `services/adapter/conversion/StreamingStructureConversionService.java` — structures: reads SdmxBeans, writes to JSON
  or XML
- `services/adapter/conversion/StreamingDataConversionService.java` — data: reads data stream, writes to SDMX-JSON 2.0.0
  or other formats
- `services/adapter/conversion/StreamingAvailabilityConversionService.java` — availability: similar pattern

### Fixtures (Response Patching)

- `services/fixture/structure/StructureFixtureService.java` — chain-of-responsibility orchestrator for structure
  fixtures
- `services/fixture/structure/DsdAttributeAttachmentLevelJsonFixture.java` — fixes
  `"attributeRelationship": {"none": {}}` → `{"observation": {}}`
- `services/fixture/structure/VersionWildcardJsonFixture.java` — fixes non-compliant version wildcards in URNs like
  `(1.3+.1)` → `(1.3+.0)`
- `services/fixture/structure/MetadataAttributeUsageToAttributeJsonFixture.java` — converts `metadataAttributeUsages` to
  regular `attributes` using MSD data
- `services/fixture/availability/AvailabilityFixtureService.java` — chain-of-responsibility for availability fixtures
- `services/fixture/availability/MoveCubeRegionComponentsToKeyValuesJsonFixture.java` — moves dimension entries from
  `components` to `keyValues` in availability responses (SDMX-JSON 2.0.0 compliance)

### sdmx-core Custom Overrides

- **`services/sdmxsource/CustomSdmxJsonDataReaderEngineV2.java`** (~936 lines) — complete rewrite of JSON 2.0.0 data
  reader to fix observation indexing and other issues
- `services/sdmxsource/CustomSdmxJsonDataStructureReaderEngineV2.java` — custom DSD reader from JSON
- `services/sdmxsource/CustomSdmxStructureIterator.java` (~394 lines) — fixes structure iteration for JSON 2.0
- `services/sdmxsource/CustomStaxAbstractStructureWriterEngineV21.java` — fixes XML 2.1 structure writing
- `services/sdmxsource/CustomStaxDsdWriterEngineV21.java` — fixes DSD XML 2.1 writing
- `services/sdmxsource/TimeDimensionTextTypePatcher.java` — patches TimeDimension TextType from `String` to
  `ObservationalTimePeriod` in XML structures

### Registry API Clients (Feign)

- `registry/api/client/Sdmx21StructureClient.java`, `Sdmx21DataClient.java`, `Sdmx21AvailabilityClient.java`
- `registry/api/client/Sdmx30StructureClient.java`, `Sdmx30DataClient.java`, `Sdmx30AvailabilityClient.java`
- `registry/api/SdmxApiClientProviderImpl.java` — builds and caches Feign clients with resilience decorators

### Configuration Loading

- `registry/configuration/ProxyConfigurationProviderImpl.java` — provides `ProxyConfiguration`
- `registry/configuration/extractor/` — config extractors for different sources: `ENV`, `CLASSPATH_RESOURCE`,
  `FILESYSTEM`, `DIAL_STORAGE`
- `registry/configuration/writer/` — config writers for `FILESYSTEM` and `DIAL_STORAGE` (runtime config updates)
- `registry/configuration/dialstorage/` — Feign client for AI DIAL Storage API (config persistence)

### Caching

- `services/cache/CacheService.java` — interface with two domains: rawStructures and readyResponses
- `services/cache/InMemoryCacheService.java` — Caffeine-based (default, `CACHE_MODE=LOCAL`)
- `services/cache/RedisCacheService.java` — Redis-based (`CACHE_MODE=REDIS`)
- `services/cache/CacheKeyGenerator.java` — deterministic key generation with MD5 fingerprinting

### Media Type Handling

- `common/data/SdmxMediaType.java` — maps Accept headers to MediaType objects, extracts SDMX version from media type
- `common/utils/FormatSupportChecker.java` — determines if bypass is possible for a given format/config combination

### Exception Handling

- `web/exception/GlobalExceptionHandler.java` — maps exceptions to HTTP status codes with traceparent in error responses

### Configuration Data Classes (sdmx-proxy-config module)

- `ProxyConfiguration.java` → `List<RegistryConfiguration>`
- `RegistryConfiguration.java` → name, description, supportedAgencies,
  `Map<SdmxVersion, VersionSpecificRegistryConfiguration>`
- `VersionSpecificRegistryConfiguration.java` → sdmxVersion, structure/data/availability endpoint configs, resilience
  config
- `EndpointConfiguration.java` → url, supportedFormats, defaultFormat, bypassEnabled
- `StructureEndpointConfiguration.java` → extends EndpointConfiguration + supportedStructures, fixtures
- `AvailabilityEndpointConfiguration.java` → extends EndpointConfiguration + availabilityEnabled, unwrapStarComponentId,
  fixtures
- `ReturnFormat.java` — enum: `JSON_1_0_0`, `JSON_2_1_DRAFT`, `JSON_DATA_2_0_0`, `JSON_STRUCTURE_2_0_0`, `XML_2_1`,
  `XML_GENERICDATA_2_1`, `XML_STRUCTURE_SPECIFIC_2_1`, `CSV`
- `SdmxVersion.java` — enum: `SDMX_2_1`, `SDMX_3_0`

---

## 4. Build & Test

### Prerequisites

- **Java 25** (toolchain configured in Gradle)
- **Gradle** (wrapper included)
- **Docker** (for E2E tests via Testcontainers, and for Redis)

### Build Commands

```bash
# Full build (clean + compile + test + bootJar + Docker prep)
./gradlew clean build

# Build without tests
./gradlew clean build -x test

# Just compile
./gradlew compileJava

# Run the application
./gradlew :sdmx-proxy:bootRun

# Build Docker-ready artifacts
./gradlew :sdmx-proxy:prepareFilesForDocker
```

### Run Locally

```bash
# Start Redis (needed for CACHE_MODE=REDIS) and OpenTelemetry collector
docker-compose -f compose/docker-compose.yml up -d

# Run the app (default port 8050)
./gradlew :sdmx-proxy:bootRun

# Or run the built JAR directly
java -jar sdmx-proxy/build/libs/sdmx-proxy-0.0.1-SNAPSHOT.jar
```

### Unit Tests

```bash
# Run unit tests only
./gradlew :sdmx-proxy:test

# Run a specific test class
./gradlew :sdmx-proxy:test --tests "com.epam.sdmxproxy.services.translator.QueryTranslatorImplTest"
```

### E2E Tests

E2E tests use **Testcontainers** to spin up the proxy in a Docker container and test against real upstream registries (
IMF, BIS). They require Docker and internet access.

```bash
# Run all E2E tests
./gradlew :sdmx-proxy-e2e:test

# Run a specific registry test suite
./gradlew :sdmx-proxy-e2e:test --tests "*.IMF_3_0_RegistryTestSuit"
./gradlew :sdmx-proxy-e2e:test --tests "*.BIS_3_0_RegistryTestSuit"
./gradlew :sdmx-proxy-e2e:test --tests "*.IMF_2_1_RegistryTestSuit"
```

E2E tests use **pairwise combination** (via allpairs4j) to generate test cases from: artefact URNs × media types ×
registry return formats × reference details × query details.

### Key Environment Variables

| Variable                      | Default          | Description                   |
|-------------------------------|------------------|-------------------------------|
| `CACHE_MODE`                  | `LOCAL`          | `LOCAL` (Caffeine) or `REDIS` |
| `REDIS_HOST`                  | `localhost`      | Redis hostname                |
| `REDIS_PORT`                  | `6379`           | Redis port                    |
| `REDIS_PASSWORD`              | `local_password` | Redis password                |
| `OTEL_SDK_DISABLED`           | `true`           | Disable OpenTelemetry         |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | —                | OTLP exporter endpoint        |
| `FEIGN_LOG_LEVEL`             | `BASIC`          | Feign client log level        |

---

## 5. sdmx-core Quirks & Registry Workarounds

### 5.1 sdmx-core Library Issues (fusion v2.3.9)

The sdmx-core library (EPAM's proprietary fork, JARs in `lib-repo/sdmxsource/`) has several known issues that required
custom class overrides:

| Issue                                      | File(s)                                                                                                          | Description                                                                                                                                                         |
|--------------------------------------------|------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Observation indexing broken**            | `CustomSdmxJsonDataReaderEngineV2.java`                                                                          | The stock data reader engine produces all-zero observation indices, making data unreadable. Complete rewrite of the V2 JSON data reader (~936 lines)                |
| **Structure iteration fails for JSON 2.0** | `CustomSdmxStructureIterator.java`                                                                               | Stock iterator can't handle the JSON 2.0.0 structure format correctly. Custom iterator (~394 lines)                                                                 |
| **DSD reader misses fields**               | `CustomSdmxJsonDataStructureReaderEngineV2.java`                                                                 | Custom DSD reader for JSON 2.0.0 format                                                                                                                             |
| **XML 2.1 structure writer bugs**          | `CustomStaxAbstractStructureWriterEngineV21.java`, `CustomStaxDsdWriterEngineV21.java`                           | Fixes for XML structure serialization                                                                                                                               |
| **TimeDimension TextType**                 | `TimeDimensionTextTypePatcher.java`                                                                              | sdmx-core expects `ObservationalTimePeriod` for TimeDimension TextType, but some registries return `String`. This patcher traverses XML with XmlCursor and fixes it |
| **Structure reader manager**               | `CustomSdmxJsonStructureReaderManagerV2.java`                                                                    | Replaces the stock `SdmxJsonDataStructureReaderEngineV2` with our custom version in the reader registry                                                             |
| **Metadata iterator**                      | `CustomSdmxJsonMetadataIteratorV2.java`                                                                          | Custom metadata section iterator for JSON 2.0                                                                                                                       |
| **Writer factories**                       | `CustomSdmxMLStructureWriterFactory.java`, `CustomSdmxJsonDataWriterFactory.java`                                | Replace stock factories to inject our custom writer engines                                                                                                         |
| **Reader factories**                       | `CustomSdmxJsonDataReaderFactory.java`, `JsonV1StructureReaderFactory.java`, `JsonV2StructureReaderFactory.java` | Custom factory for data reading with our custom engine                                                                                                              |

### 5.2 IMF Registry Issues

| Issue                                          | Fixture                                          | Description                                                                                                                                                                           |
|------------------------------------------------|--------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Invalid attributeRelationship `"none"`**     | `DsdAttributeAttachmentLevelJsonFixture`         | IMF returns `"attributeRelationship": {"none": {}}` which is not valid SDMX. Fixture replaces `"none"` with `"observation"` (configurable via `sourceValue`/`fallbackValue`)          |
| **Non-compliant version wildcards**            | `VersionWildcardJsonFixture`                     | IMF returns URNs like `(1.3+.1)` where spec requires trailing parts after wildcard to be 0. Fixture rewrites to `(1.3+.0)`                                                            |
| **metadataAttributeUsages in DSD**             | `MetadataAttributeUsageToAttributeJsonFixture`   | IMF includes `metadataAttributeUsages` in DSD responses, which sdmx-core cannot parse. Fixture converts them to regular `attributes` by resolving from the MetadataStructure          |
| **Dimensions in `components` not `keyValues`** | `MoveCubeRegionComponentsToKeyValuesJsonFixture` | IMF availability responses put dimension entries in `components` instead of `keyValues`. Fixture moves dimension entries (identified via DSD) to `keyValues` per SDMX-JSON 2.0.0 spec |
| **IMF 2.1 data format**                        | Configuration                                    | IMF 2.1 returns `XML_STRUCTURE_SPECIFIC_2_1` for data (not JSON)                                                                                                                      |
| **IMF time period filters**                    | `QueryTranslatorImpl`                            | IMF 2.1 doesn't support c[] filters — time period filters are translated to `startPeriod`/`endPeriod` query params                                                                    |

### 5.3 BIS Registry Issues

| Issue                                   | Workaround                                  | Description                                                                                                                               |
|-----------------------------------------|---------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------|
| **BIS 3.0 returns JSON 1.0.0 for data** | Configuration (`defaultFormat: JSON_1_0_0`) | Despite being a 3.0 registry, BIS returns SDMX-JSON 1.0.0 for data endpoints                                                              |
| **BIS 3.0 doesn't return cube regions** | Known issue (commit `edb74e0`)              | BIS availability responses may lack cube regions                                                                                          |
| **`*` componentId not supported**       | `unwrapStarComponentId` config flag         | BIS doesn't understand `componentId=*`. When enabled, the proxy expands `*` into a comma-separated list of all dimension IDs from the DSD |

### 5.4 SDMX 2.1 vs 3.0 Translation Quirks

| Area                 | 3.0                                                                  | 2.1 Translation                                                                    |
|----------------------|----------------------------------------------------------------------|------------------------------------------------------------------------------------|
| **Wildcard**         | `*`                                                                  | `all`                                                                              |
| **Filters**          | `c[DIM]=value` query params                                          | Merged into key parameter: `val1.val2.val3` (position-based)                       |
| **Time filters**     | `c[TIME_PERIOD]=ge:2020+lt:2025`                                     | `startPeriod=2020&endPeriod=2025`                                                  |
| **Data URL**         | `/data/dataflow/{agency}/{id}/{version}/{key}`                       | `/data/{agency},{id},{version}/{key}/all`                                          |
| **Structure URL**    | `/structure/{type}/{agency}/{id}/{version}`                          | `/{type}/{agency}/{id}/{version}`                                                  |
| **Availability URL** | `/availability/dataflow/{agency}/{id}/{version}/{key}/{componentId}` | `/availableconstraint/{agency},{id},{version}/{key}/{providerRef}?componentId=...` |

---

## 6. Conventions & Patterns

### 6.1 Code Style

- **Java 25** with preview features disabled; uses modern Java features (records, switch expressions, `var`,
  `List.of()`, `Stream`)
- **Lombok** everywhere: `@Data`, `@Builder`, `@RequiredArgsConstructor`, `@Slf4j`, `@SneakyThrows`
- **Spring Boot 4.0** with WebMVC (not WebFlux)
- **No field injection** — always constructor injection via `@RequiredArgsConstructor`
- **Log4j2** (not Logback) — Logback is explicitly excluded
- Comments are used sparingly; Javadoc on fixture classes and config classes explaining SDMX context

### 6.2 Package Naming

- `controller/` — REST controllers, thin, delegate to services
- `api/` — OpenAPI-annotated interfaces that controllers implement
- `services/` — business logic
- `registry/api/` — Feign client interfaces and their provider
- `registry/configuration/` — configuration loading (extractors, writers)
- `common/` — shared utilities, DTOs, mappers
- `web/` — Spring WebMVC configuration, exception handling
- `configuration/` — cross-cutting concerns (telemetry)

### 6.3 Patterns

- **API interface + Controller implementation** — every controller implements a dedicated `@RequestMapping`-annotated
  interface in `api/`
- **TranslatedQuery DTOs** — `TranslatedStructureQuery`, `TranslatedDataQuery`, `TranslatedAvailabilityQuery` —
  immutable (built with `@Builder`) query objects produced by `QueryTranslator` and consumed by `AdapterRouter`
- **Chain of Responsibility for fixtures** — `StructureFixtureService` / `AvailabilityFixtureService` iterate over
  configured fixtures and apply matching ones in order
- **Fixture interface + enum type** — each fixture implements `StructureFixture` or `AvailabilityFixture`, declares its
  `getType()` and `supportedFormats()`, is discovered via Spring's `List<T>` injection
- **Bypass vs Conversion** — `FormatSupportChecker` decides if the registry's native format matches what the client
  wants. If yes and `bypassEnabled=true`, raw bytes pass through untouched
- **`ReturnFormat` enum** — represents what the proxy requests FROM the registry (not what the client gets). The
  conversion layer transforms from `ReturnFormat` → client's requested `MediaType`
- **Registry selection** — `QueryTranslatorImpl.selectRegistryAndVersion()` finds registry by agencyID, then picks
  version: exact match from Accept header first, fallback to 3.0, then 2.1
- **Feign client caching** — `SdmxApiClientProviderImpl` uses `ConcurrentHashMap` with double-checked locking to build
  Feign clients lazily, keyed by `(clientClass, baseUrl)`
- **StreamingResponseBody** — all endpoint responses stream to avoid buffering large SDMX payloads

### 6.4 Configuration Format

Registry configurations are in JSON (`sdmx_registries_config.json`). Each registry has:

- `name`, `description`, `supportedAgencies`
- `versions` map: `SDMX_2_1` and/or `SDMX_3_0`, each with:
    - `structureEndpointConfig`, `dataEndpointConfig`, `availabilityEndpointConfig`
    - `resilienceConfig` (optional per-registry overrides)
    - Endpoint configs have: `url`, `supportedFormats`, `defaultFormat`, `bypassEnabled`, and optionally `fixtures` (
      list of `{type, config}`)

### 6.5 Commit Message Convention

Commits follow pattern: `[issue-number] - description` (e.g.,
`[104] - Normalise availability response so dimensions are in keyValues`). `000` is used for non-issue-related changes.

---

## 7. Known Issues & TODOs

### Documented TODOs in Code

1. **`ConfigController.java`** — runtime config update via POST won't work in a scaled environment. Needs a config
   server pattern or shared file storage (currently using Dial Storage as one option).

2. **`QueryTranslatorImpl.java`** — `determineStructureReturnFormat()`, `determineDataReturnFormat()`,
   `determineAvailabilityReturnFormat()`, `findMatchingFormat()` all have `//TODO WRITE TESTS FOR IT` comments. These
   format-determination methods lack dedicated unit tests.

3. **`GenericRegistryAdapterImpl.java`** — `getData21()` has `//TODO support other query params` for SDMX 2.1 data
   queries (issue #95). Only `startPeriod` and `endPeriod` are supported; other 2.1 data query params are not forwarded.

4. **`includeHistory` parameter** — `TranslatedDataQuery` carries this field but it's passed as String, not properly
   typed.

### Known Technical Debt

- **Custom sdmx-core overrides are fragile** — The ~10 custom classes in `services/sdmxsource/` duplicate significant
  portions of the sdmx-core library. Any library upgrade requires re-verifying and potentially re-porting these
  customizations.

- **No XML 3.0 output support** — `StreamingStructureConversionService.writeAsXml()` throws
  `UnsupportedOperationException` for SDMX 3.0 XML output.

- **Cache invalidation** — no explicit cache invalidation mechanism beyond TTL. Config changes (via POST /config) don't
  invalidate cached responses.

- **Filter merging assumes sorted dimension order** — `QueryTranslatorImpl.mergeFilters()` sorts dimension IDs
  alphabetically for position-based key construction. This may not match actual DSD dimension order in all cases.

- **Error handling in streaming** — exceptions during streaming (`StreamingResponseBody` execution) may result in
  partial/corrupt responses since headers are already sent.

- **sdmx-core JAR management** — JARs are committed to `lib-repo/` rather than published to an artifact repository. This
  makes upgrades manual and versioning opaque.

### Potential Issues

- **Fan-out error handling** — if some registries fail during fan-out, partial results are returned (only fails if ALL
  registries fail). This may be surprising to consumers.
- **Rate limiter not shared across instances** — rate limiting is per-JVM (Resilience4j in-memory). In a multi-instance
  deployment, effective rate is N × configured rate.
- **Redis serialization** — raw byte arrays are stored in Redis. No versioning or schema for cache entries — changing
  response format requires cache flush.

---

## 8. Glossary

### SDMX Concepts

| Term                                    | Definition                                                                                                              |
|-----------------------------------------|-------------------------------------------------------------------------------------------------------------------------|
| **Registry**                            | An SDMX web service that provides statistical data and metadata (e.g., IMF, BIS, Eurostat)                              |
| **Agency / AgencyID**                   | Organization maintaining data (e.g., `IMF`, `IMF.STA`, `BIS`). Used to route requests to the correct registry           |
| **Dataflow**                            | A structure that defines how data is organized and published. Referenced by agencyID:resourceID(version)                |
| **DSD (Data Structure Definition)**     | Defines the structure of a dataset: dimensions, attributes, measures, and their codelists                               |
| **MSD (Metadata Structure Definition)** | Defines the structure of metadata. Relevant for `MetadataAttributeUsageToAttributeJsonFixture`                          |
| **Codelist**                            | Enumeration of allowed values for a dimension or attribute                                                              |
| **Concept Scheme**                      | Collection of concepts (abstract statistical ideas) that dimensions/attributes reference                                |
| **Content Constraint / Cube Region**    | Defines which values are available for a dataset's dimensions (used in availability responses)                          |
| **Key**                                 | Position-based filter string for SDMX 2.1 data queries: `val1.val2.val3` where each position corresponds to a dimension |
| **c[] parameter**                       | SDMX 3.0 filter syntax: `c[DIMENSION_ID]=value` query parameter                                                         |
| **SdmxBeans**                           | sdmx-core's in-memory representation of SDMX structural metadata (DSDs, codelists, dataflows, etc.)                     |

### SDMX Versions & Formats

| Term                        | Description                                                                   |
|-----------------------------|-------------------------------------------------------------------------------|
| **SDMX 2.1**                | Older REST API version. XML-centric. Different URL patterns and filter syntax |
| **SDMX 3.0**                | Current REST API version (REST 2.2.0). JSON-centric. Uses c[] filter params   |
| **SDMX-JSON 1.0.0**         | JSON format for SDMX 2.1 data                                                 |
| **SDMX-JSON 2.0.0**         | JSON format for SDMX 3.0 data and structures                                  |
| **SDMX-ML 2.1**             | XML format for SDMX 2.1 (structures and data)                                 |
| **Structure-Specific Data** | XML data format where elements are named after dimensions (vs Generic Data)   |

### Internal Terms

| Term                      | Definition                                                                                                |
|---------------------------|-----------------------------------------------------------------------------------------------------------|
| **Bypass**                | Passing registry response directly to client without format conversion (when formats match)               |
| **Conversion**            | Parsing registry response into SdmxBeans and re-serializing to the client's requested format              |
| **Fixture**               | A JSON patch applied to raw registry responses before parsing, to fix known registry bugs                 |
| **Fan-out**               | Querying multiple registries in parallel when agencyID is `*` or spans multiple registries                |
| **ReturnFormat**          | The format the proxy requests FROM the upstream registry (not the client's requested format)              |
| **TranslatedQuery**       | DTO produced by QueryTranslator containing all resolved parameters (registry, version, format, key, etc.) |
| **Raw structures**        | Cached byte[] of the registry's structure response (before conversion, after fixtures)                    |
| **Ready response**        | Cached byte[] of the fully converted response ready to send to the client                                 |
| **Dial Storage**          | AI DIAL platform's file storage API, used as an alternative configuration source                          |
| **unwrapStarComponentId** | Config flag that expands `componentId=*` into explicit comma-separated dimension IDs (needed for BIS)     |
| **sdmx-core / fusion**    | The underlying SDMX library (EPAM's fork, v2.3.9) used for parsing and serialization                      |

### Reference Directories (Read-Only)

| Directory         | Content                                                                                                      |
|-------------------|--------------------------------------------------------------------------------------------------------------|
| `sdmx-core-2.3.9` | Source code of the sdmx-core library — useful for understanding internal APIs and debugging custom overrides |
| `sdmx-rest-2.2.0` | SDMX REST API 2.2.0 specification — the REST standard the proxy's external API follows                       |
| `sdmx-json-2.0.0` | SDMX-JSON 2.0.0 format specification — defines the JSON schema for structure and data responses              |
