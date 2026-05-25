# SDMX Proxy Config

Pure Lombok data classes representing the registry configuration model. This module has no Spring
dependency and no runtime behavior. It is shared as a compile dependency by:

- `sdmx-proxy` (main application)
- `sdmx-proxy-config-server` (configuration server)
- `sdmx-proxy-e2e` (end-to-end tests)

## Environment variables

This module does not use any environment variables. It contains only data classes (POJOs).

## Configuration schema

All options are supplied via a single JSON document (delivered through classpath, filesystem, or
the config server -- see `sdmx-proxy/README.md`). The root object is `ProxyConfiguration`.

Unless otherwise noted, missing optional fields fall back to the defaults listed below. Boolean
fields default to `false` when omitted.

### Root: `ProxyConfiguration`

| Field                    | Required | Description                                                                                                                                                            | Available Values                 | Default |
|--------------------------|:--------:|------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------|---------|
| `configs`                |   Yes    | List of registries the proxy can route to                                                                                                                              | Array of `RegistryConfiguration` |         |
| `agencies`               |    No    | Explicit agency-to-registry routing overrides and sub-agency allowances                                                                                                | Array of `AgencyConfiguration`   | (empty) |
| `structureFanOutEnabled` |    No    | When true, `GET /structure/{type}/*/.../...` is fanned out to every registry that supports the requested structure type and the parsed structures are merged into one response. Comma-separated agency IDs remain rejected with HTTP 501 | `true`, `false`                  | `false` |

### `RegistryConfiguration`

A single upstream SDMX registry (e.g. BIS, IMF, Eurostat). Each registry may expose multiple SDMX
versions, configured independently.

| Field         | Required | Description                                                                  | Available Values                                                             | Default |
|---------------|:--------:|------------------------------------------------------------------------------|------------------------------------------------------------------------------|---------|
| `name`        |   Yes    | Registry name. Used as the primary routing key and as the agency ID fallback |                                                                              |         |
| `description` |    No    | Human-readable description                                                   |                                                                              |         |
| `versions`    |   Yes    | Map of SDMX version to per-version configuration                             | Keys: `SDMX_2_1`, `SDMX_3_0`; Values: `VersionSpecificRegistryConfiguration` |         |

### `VersionSpecificRegistryConfiguration`

All settings that differ between SDMX versions on the same registry.

| Field                        | Required | Description                                               | Available Values                    | Default |
|------------------------------|:--------:|-----------------------------------------------------------|-------------------------------------|---------|
| `sdmxVersion`                |   Yes    | SDMX version this block applies to                        | `SDMX_2_1`, `SDMX_3_0`              |         |
| `structureEndpointConfig`    |   Yes    | Structure-query endpoint settings                         | `StructureEndpointConfiguration`    |         |
| `dataEndpointConfig`         |    No    | Data-query endpoint settings                              | `DataEndpointConfiguration`         |         |
| `availabilityEndpointConfig` |    No    | Availability-query endpoint settings                      | `AvailabilityEndpointConfiguration` |         |
| `resilienceConfig`           |    No    | Timeouts, circuit breaker, retry, and rate-limit settings | `RegistryResilienceConfig`          |         |

### Common endpoint fields (`EndpointConfiguration`)

Shared by every `*EndpointConfig` block below.

| Field              | Required | Description                                                                                                     | Available Values        | Default |
|--------------------|:--------:|-----------------------------------------------------------------------------------------------------------------|-------------------------|---------|
| `url`              |   Yes    | Base URL for this endpoint type                                                                                 |                         |         |
| `supportedFormats` |    No    | Formats the registry can return natively; used for bypass matching                                              | Array of `ReturnFormat` | (empty) |
| `defaultFormat`    |    No    | Format requested from the registry when bypass is disabled or the requested format is not in `supportedFormats` | `ReturnFormat`          |         |
| `bypassEnabled`    |    No    | When true, responses are streamed through unchanged if the requested format is in `supportedFormats`            | `true`, `false`         | `false` |

### `StructureEndpointConfiguration` (extends `EndpointConfiguration`)

| Field                 | Required | Description                                                                                 | Available Values                                                                                       | Default |
|-----------------------|:--------:|---------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------|---------|
| `supportedStructures` |    No    | Structure types the registry supports. Queries for other types are rejected before dispatch | e.g. `datastructure`, `dataflow`, `codelist`, `conceptscheme`, `hierarchy`, `hierarchyassociation` ... | (empty) |
| `fixtures`            |    No    | Response patches applied before conversion/bypass, in order                                 | Array of `FixtureConfiguration<StructureFixtureType>`                                                  | (empty) |

### `DataEndpointConfiguration` (extends `EndpointConfiguration`)

| Field                                | Required | Description                                                                                                                                                              | Available Values                                 | Default |
|--------------------------------------|:--------:|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------|---------|
| `replaceEmptyDimensionsWithWildcard` |    No    | Replace empty dot-separated key positions with `*` (e.g. `.L_T.P_F3` → `*.L_T.P_F3`). Required for IMF and similar registries                                            | `true`, `false`                                  | `false` |
| `mergeAllWildcardKey`                |    No    | Collapse a key whose every position is `*` to a single `*` (e.g. `*.*.*.*` → `*`). Required for BIS and similar registries                                               | `true`, `false`                                  | `false` |
| `fixtures`                           |    No    | Streaming response patches applied to the raw registry response before conversion, in the order listed                                                                   | Array of `FixtureConfiguration<DataFixtureType>` | (empty) |
| `supportsLimit`                      |    No    | When false, the proxy emulates the SDMX 3.0 `limit` parameter via availability probing and streaming series truncation                                                   | `true`, `false`                                  | `true`  |
| `limitEmulationTolerance`            |    No    | Overshoot factor for the emulation target band `[limit, floor(limit * limitEmulationTolerance)]`. Ignored when `supportsLimit` is true                                   | `[1.0, 10.0]`                                    | `1.2`   |
| `limitEmulationProbeBudget`          |    No    | Hard cap on the number of availability probes issued per request when emulating `limit`. Ignored when `supportsLimit` is true                                            | `[1, 64]`                                        | `8`     |
| `convertKeyToFilters`                |    No    | When true, every dim filter is moved into `c[]` and the path key is sent as a single `*` on outbound data requests. Workaround for BIS-style registries (see design 016) | `true`, `false`                                  | `false` |

### `AvailabilityEndpointConfiguration` (extends `EndpointConfiguration`)

| Field                    | Required | Description                                                                                                                                                                      | Available Values                                         | Default |
|--------------------------|:--------:|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------|---------|
| `availabilityEnabled`    |    No    | Enable availability queries for this version. If false, availability requests are rejected                                                                                       | `true`, `false`                                          | `false` |
| `unwrapStarComponentId`  |    No    | Omit the component ID segment from the upstream path when it is `*`                                                                                                              | `true`, `false`                                          | `false` |
| `unwrapFilterParameters` |    No    | Send filters as raw dimension query params (e.g. `FREQ=Q`) instead of the SDMX 3.0 `c[FREQ]=Q` wrapper                                                                           | `true`, `false`                                          | `false` |
| `mergeAllWildcardKey`    |    No    | Collapse a key whose every position is `*` to a single `*`. Required for BIS and similar registries                                                                              | `true`, `false`                                          | `false` |
| `fixtures`               |    No    | Response patches applied before conversion/bypass, in order                                                                                                                      | Array of `FixtureConfiguration<AvailabilityFixtureType>` | (empty) |
| `convertKeyToFilters`    |    No    | When true, every dim filter is moved into `c[]` and the path key is sent as a single `*` on outbound availability requests. Workaround for BIS-style registries (see design 016) | `true`, `false`                                          | `false` |

### `RegistryResilienceConfig`

All durations are in milliseconds.

| Field               | Required | Description                                                  | Available Values               | Default |
|---------------------|:--------:|--------------------------------------------------------------|--------------------------------|---------|
| `connectionTimeout` |    No    | HTTP connect timeout                                         | Integer (ms)                   | `30000` |
| `readTimeout`       |    No    | HTTP read timeout                                            | Integer (ms)                   | `30000` |
| `circuitBreaker`    |    No    | Circuit breaker settings; uses defaults when null            | `RegistryCircuitBreakerConfig` |         |
| `retry`             |    No    | Retry settings; uses defaults when null                      | `RegistryRetryConfig`          |         |
| `rateLimit`         |    No    | Rate-limit settings; when null, the app-wide default is used | `RegistryRateLimitConfig`      |         |

#### `RegistryCircuitBreakerConfig`

| Field                     | Required | Description                                                | Available Values | Default |
|---------------------------|:--------:|------------------------------------------------------------|------------------|---------|
| `failureRateThreshold`    |    No    | Failure rate percentage (0-100) at which the circuit opens | Float            | `50.0`  |
| `minimumNumberOfCalls`    |    No    | Minimum calls in the window before the circuit can open    | Integer          | `10`    |
| `waitDurationInOpenState` |    No    | Time in open state before transitioning to half-open (ms)  | Long (ms)        | `60000` |
| `slidingWindowSize`       |    No    | Sliding-window size used to compute the failure rate       | Integer          | `10`    |

#### `RegistryRetryConfig`

| Field                   | Required | Description                                                | Available Values | Default       |
|-------------------------|:--------:|------------------------------------------------------------|------------------|---------------|
| `maxAttempts`           |    No    | Maximum retry attempts; uses application default when null | Integer          | (app default) |
| `initialIntervalMillis` |    No    | Initial interval for exponential backoff (ms)              | Long (ms)        | (app default) |
| `multiplier`            |    No    | Exponential-backoff multiplier                             | Double           | (app default) |
| `maxIntervalMillis`     |    No    | Maximum interval for exponential backoff (ms)              | Long (ms)        | (app default) |

#### `RegistryRateLimitConfig`

| Field                | Required | Description                                                        | Available Values | Default       |
|----------------------|:--------:|--------------------------------------------------------------------|------------------|---------------|
| `enabled`            |    No    | Enable rate limiting; when null, uses the application-wide default | `true`, `false`  | (app default) |
| `limitForPeriod`     |    No    | Maximum requests allowed within the refresh period                 | Integer          | (app default) |
| `limitRefreshPeriod` |    No    | Period (ms) over which `limitForPeriod` is enforced                | Long (ms)        | `60000`       |

### `AgencyConfiguration`

Declares an agency and the registry that owns it. Queries for this agency route to `primaryRegistry`
regardless of the registry's own `name`.

| Field              | Required | Description                                                                    | Available Values | Default |
|--------------------|:--------:|--------------------------------------------------------------------------------|------------------|---------|
| `name`             |   Yes    | Agency ID as it appears in SDMX URLs (e.g. `IMF`, `BIS`, `ECB`)                |                  |         |
| `primaryRegistry`  |   Yes    | Name of the `RegistryConfiguration` that serves this agency                    |                  |         |
| `allowSubAgencies` |    No    | Allow routing of sub-agencies (dot-separated descendants) to the same registry | `true`, `false`  | `false` |

### `FixtureConfiguration<T>`

Used by `StructureEndpointConfiguration.fixtures` and `AvailabilityEndpointConfiguration.fixtures`.
Fixtures are applied as a chain of responsibility in the order listed.

| Field    | Required | Description                                                        | Available Values                          | Default |
|----------|:--------:|--------------------------------------------------------------------|-------------------------------------------|---------|
| `type`   |   Yes    | Fixture identifier; the type parameter `T` constrains valid values | See fixture type tables below             |         |
| `config` |    No    | Free-form key/value map passed to the fixture implementation       | Object with string keys and string values | (empty) |

#### `StructureFixtureType`

| Value                                | Description                                                                                                                                                                                                              |
|--------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `DSD_ATTRIBUTE_ATTACHMENT_LEVEL`     | Repair invalid DSD attribute attachment levels                                                                                                                                                                           |
| `VERSION_WILDCARD`                   | Patch wildcard version handling on structure responses                                                                                                                                                                   |
| `ANNOTATION_VALUE_TO_TEXT`           | Rewrite annotation `value` (non-localised string) into `text` so sdmx-core's SDMX-JSON 2.0 reader preserves the field. Applied only when `text`/`texts` is absent on the annotation                                      |
| `PRESERVE_METADATA_ATTRIBUTE_USAGES` | Capture raw `metadataAttributeUsages` arrays before conversion and re-inject them onto the matching DSD in the converted output. Works on the single-leg structure path; restores the field sdmx-core's bean model omits |

#### `AvailabilityFixtureType`

| Value                                       | Description                                                           |
|---------------------------------------------|-----------------------------------------------------------------------|
| `MOVE_CUBE_REGION_COMPONENTS_TO_KEY_VALUES` | Move misplaced `components` entries under `keyValues` on cube regions |

#### `DataFixtureType`

| Value                               | Description                                                                                                                                                                                                                             |
|-------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `TIME_PERIOD_MONTHLY_NORMALIZATION` | Rewrite monthly `TIME_PERIOD` values to canonical `YYYY-Mmm` (e.g. `2024-03` → `2024-M03`). Supported for `JSON_1_0_0`, `JSON_DATA_2_0_0`, `XML_GENERICDATA_2_1`, `XML_STRUCTURE_SPECIFIC_2_1`, `CSV_DATA_1_0_0`, and `CSV_DATA_2_0_0`. |

### Enum: `SdmxVersion`

`SDMX_2_1`, `SDMX_3_0`.

### Enum: `ReturnFormat`

Values are the enum names used in JSON (e.g. `JSON_STRUCTURE_2_0_0`). Each maps to a media type
consumed on the wire:

| Value                        | Media type                                                   |
|------------------------------|--------------------------------------------------------------|
| `JSON_1_0_0`                 | `application/vnd.sdmx.data+json;version=1.0.0`               |
| `JSON_2_1_DRAFT`             | `application/vnd.sdmx.draft-sdmx-json+json; version=2.1`     |
| `JSON_DATA_2_0_0`            | `application/vnd.sdmx.data+json; version=2.0.0`              |
| `JSON_STRUCTURE_2_0_0`       | `application/vnd.sdmx.structure+json; version=2.0.0`         |
| `XML_2_1`                    | `application/vnd.sdmx.structure+xml;version=2.1`             |
| `XML_GENERICDATA_2_1`        | `application/vnd.sdmx.genericdata+xml;version=2.1`           |
| `XML_STRUCTURE_SPECIFIC_2_1` | `application/vnd.sdmx.structurespecificdata+xml;version=2.1` |
| `CSV_DATA_1_0_0`             | `application/vnd.sdmx.data+csv;version=1.0.0`                |
| `CSV_DATA_2_0_0`             | `application/vnd.sdmx.data+csv;version=2.0.0`                |
