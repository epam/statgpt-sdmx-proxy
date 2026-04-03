# Code Review: PR1 -- Dynamic Agency Routing + Config Server

**Commit:** `7241da6` ("pr1")
**Design docs:** `003-dynamic-agency-routing/CONFIG_SERVER.md`, `003-dynamic-agency-routing/AGENCY_ROUTING.md`
**Reviewer:** Claude Code
**Date:** 2026-03-24

---

## Summary

This PR implements both design documents from 003-dynamic-agency-routing:

1. **Config Server** (`sdmx-config-server` module) -- new microservice for DIAL Storage-backed config management
2. **Agency Routing simplification** -- removes fan-out, secondary registries, adds `allowSubAgencies`, synthetic
   AgencyScheme endpoint
3. **Proxy simplification** -- removes DIAL Storage/ENV source types, adds CONFIG_SERVER source type with polling

The implementation is **largely faithful to the design**, with good structural separation. Below are findings organized
by severity.

---

## Critical Issues

### C1. AgencySchemeService cache is not thread-safe (race condition)

**File:** `sdmx-proxy/.../services/agencyscheme/AgencySchemeService.java:42-54`

The caching logic has a check-then-act race condition. Two concurrent requests can both see a cache miss and both
execute `buildAgencySchemeJson()` (which makes upstream HTTP calls):

```java
String configHash = String.valueOf(configurationProvider.getConfiguration().hashCode());
String cachedHash = (String) cacheMetadata.get("configHash");

byte[] cached = cachedResponse.get();
if (cached != null && configHash.equals(cachedHash)) {  // Thread A checks
    return cached;                                       // Thread B also checks
}
// Both threads build the response
byte[] result = buildAgencySchemeJson();  // Expensive upstream call executed twice
```

**Impact:** Duplicate upstream dataflow queries. Not a correctness bug (both threads produce the same result), but
wastes resources and can hammer upstream registries.

**Recommendation:** Use a `synchronized` block or `ReentrantLock` around the check-and-build. Alternatively, use a
`Supplier`-based lazy pattern with `AtomicReference.compareAndSet`.

### C2. ConfigService.updateConfiguration() is not atomic

**File:** `sdmx-config-server/.../service/ConfigService.java:47-52`

```java
public void updateConfiguration(ProxyConfiguration configuration) {
    configValidator.validate(configuration);
    writeToDialStorage(configuration);     // Step 1: write to DIAL Storage
    currentConfig.set(configuration);       // Step 2: update in-memory
    log.info("Configuration updated successfully");
}
```

If two concurrent POST requests arrive, they can interleave: Thread A writes config-A to DIAL Storage, Thread B writes
config-B to DIAL Storage, then Thread A sets in-memory to config-A (but DIAL Storage has config-B). After restart,
config-B wins. In-memory and persisted state diverge.

**Recommendation:** Add synchronization (`synchronized` or lock) on the write path. The design doc doesn't address
concurrency for writes.

### C3. Config server does not return 503 when DIAL Storage is unavailable

**File:** `sdmx-config-server/.../controller/ConfigServerController.java:19-26`

The design doc (CONFIG_SERVER.md, line 111) states: "returns 503 on `GET /api/config` until DIAL Storage becomes
available". The controller only checks for null config (returns 404):

```java
ProxyConfiguration config = configService.getConfiguration();
if (config == null) {
    return ResponseEntity.notFound().build();  // Always 404, never 503
}
```

The `ConfigService.isDialStorageAvailable()` method exists but is never called by the controller.

**Recommendation:** Add a check for `!configService.isDialStorageAvailable()` and return 503 before the null check.

---

## High Severity

### H1. ConfigServerConfigExtractor.pollForUpdate() silently accepts deserialization of arbitrary JSON

**File:** `sdmx-proxy/.../extractor/ConfigServerConfigExtractor.java:47-57`

The Feign API returns `String`, which is then deserialized. If the config server returns an error body (e.g., HTML 502
from a load balancer), `objectMapper.readValue` will throw and the catch block logs a warning. This is acceptable for
error responses, but if the config server returns a valid JSON that doesn't match `ProxyConfiguration` schema (e.g.,
partial config), Jackson will silently deserialize with null fields and the proxy will swap to a broken config.

**Recommendation:** Add basic validation (at minimum: configs and agencies not null/empty) before calling
`cachedConfig.set(config)`.

### H2. AgencySchemeService depends on QueryTranslator, creating a circular dependency risk

**File:** `sdmx-proxy/.../services/agencyscheme/AgencySchemeService.java:31-32`

`AgencySchemeService` -> `QueryTranslator` -> `AgencyRoutingService` -> `ProxyConfigurationProvider`
`AgencySchemeService` also depends on `AdapterRouter` -> `QueryTranslator`

The `AgencySchemeService` calls `queryTranslator.translateStructureQuery(...)` and `adapterRouter.getSdmxBeans(...)`.
`AdapterRouter` itself depends on `QueryTranslator`. This creates a tight coupling where the agency discovery service
uses the full query translation + adapter pipeline to discover sub-agencies. If the `QueryTranslator` throws
`UnsupportedAgencyWildcardException` for any reason (e.g., config issue), the entire AgencyScheme endpoint breaks.

**Recommendation:** Consider whether the sub-agency discovery should bypass `QueryTranslator` and go directly to the
registry adapter with a pre-built query, or at minimum document this dependency chain.

### H3. POST /api/config on proxy returns 404 instead of 405 when disabled

**File:** `sdmx-proxy/.../controller/ConfigController.java:37-42`

```java
if (!testEndpointEnabled) {
    log.warn("POST /config is disabled. Use the config server to update configuration.");
    return ResponseEntity.notFound().build();  // 404 Not Found
}
```

404 is misleading -- the endpoint exists but is intentionally disabled. 405 Method Not Allowed or 501 Not Implemented
would be more accurate. The design doc says "POST /api/config is removed entirely" (CONFIG_SERVER.md, line 281), but the
implementation keeps it behind a flag. This is a reasonable deviation for E2E test support, but the response code should
be clearer.

**Recommendation:** Return `ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(...)` with a message directing to
the config server.

### H4. AgencyScheme endpoint hardcodes `produces = "application/json"` only

**File:** `sdmx-proxy/.../api/AgencySchemeApi.java:29`

The design doc mentions: "Content-type negotiation (SDMX JSON, SDMX ML if supported)" in the test plan. The current
implementation only supports `application/json` and builds JSON manually rather than going through
`StreamingStructureConversionService`.

**Impact:** Clients requesting `application/vnd.sdmx.structure+json;version=2.0.0` will get a 406 or unexpected content
type.

**Recommendation:** For Phase 1 this is acceptable if documented as a known limitation. The design doc's "Implementation
approach" section (AGENCY_ROUTING.md lines 440-453) specifically calls for using sdmx-core's mutable bean API +
`StreamingStructureConversionService`, which would handle content negotiation. This should be a follow-up.

---

## Medium Severity

### M1. AgencySchemeService uses `hashCode()` for config change detection

**File:** `sdmx-proxy/.../services/agencyscheme/AgencySchemeService.java:43`

```java
String configHash = String.valueOf(configurationProvider.getConfiguration().hashCode());
```

`ProxyConfiguration` uses Lombok `@Data` which generates `hashCode()` based on all fields. This works, but:

- `hashCode()` collisions are possible (32-bit space)
- The hash is computed on every request, traversing the entire config object graph

**Recommendation:** Consider using a version counter or a content hash (SHA-256 of the serialized JSON) set at
config-load time, rather than recomputing on every request.

### M2. ConfigServerFeignConfig has hardcoded timeouts

**File:** `sdmx-proxy/.../configserver/ConfigServerFeignConfig.java:36`

```java
Request.Options options = new Request.Options(10_000, 15_000, true);
```

The design doc specifies these should come from `ConfigServerProperties`, but the implementation hardcodes them. The
proxy's own `application.yaml` has a commented-out config-server section but no timeout properties.

**Recommendation:** Use `properties.getConnectTimeout()` and `properties.getReadTimeout()` (add them to
`ConfigServerProperties`).

### M3. ConfigServerPoller puts `@EnableScheduling` on itself

**File:** `sdmx-proxy/.../configserver/ConfigServerPoller.java:13`

`@EnableScheduling` is typically placed on a `@Configuration` class or the main application class. Putting it on a
`@Component` works but is unconventional and can cause scheduling to be enabled even when it shouldn't be if component
scanning picks this up in unexpected contexts (e.g., test slices).

**Recommendation:** Move `@EnableScheduling` to the main application class or a dedicated configuration class.

### M4. ApiKeyAuthFilter uses field injection via @Value

**File:** `sdmx-config-server/.../security/ApiKeyAuthFilter.java:20-21`

```java
@Value("${sdmxproxy.configserver.auth.api-key:}")
private String configuredApiKey;
```

The project convention is constructor injection only. `OncePerRequestFilter` doesn't accept constructor args easily, but
the pattern can be addressed by injecting a properties class.

**Recommendation:** Create an `AuthProperties` config class or inject via a `@Bean` factory method. Alternatively,
accept this as a pragmatic exception and document it.

### M5. ConfigValidator does not validate agency names for leading/trailing dots

**File:** `sdmx-config-server/.../service/ConfigValidator.java`

The design doc (AGENCY_ROUTING.md, line 157) states: "Config validation should reject malformed agency names (
leading/trailing dots, empty strings)." The validator checks for null/blank but does not check for leading/trailing
dots.

**Recommendation:** Add validation:

```java
if (agency.getName().startsWith(".") || agency.getName().endsWith(".")) {
    throw new IllegalArgumentException("Agency name '" + agency.getName() + "' must not start or end with a dot");
}
```

### M6. ConfigValidator does not check for duplicate agency names

**File:** `sdmx-config-server/.../service/ConfigValidator.java:40-57`

Registry names are checked for duplicates, but agency names are not. Two agencies with the same `name` but different
`primaryRegistry` would be accepted, leading to non-deterministic routing.

**Recommendation:** Add a duplicate agency name check similar to the registry name check.

### M7. ConfigService.init() swallows startup failure without health indicator

**File:** `sdmx-config-server/.../service/ConfigService.java:29-37`

The design doc says the config server should "report unhealthy via `/actuator/health`" when DIAL Storage is unavailable
at startup. The `dialStorageAvailable` flag exists but there's no Spring Boot `HealthIndicator` bean that reads it.

**Recommendation:** Add a `HealthIndicator` implementation:

```java
@Component
@RequiredArgsConstructor
public class DialStorageHealthIndicator implements HealthIndicator {
    private final ConfigService configService;
    @Override
    public Health health() {
        return configService.isDialStorageAvailable() ? Health.up().build() : Health.down().build();
    }
}
```

### M8. AgencyScheme response does not include `isExternalReference` or proper SDMX structure envelope

**File:** `sdmx-proxy/.../services/agencyscheme/AgencySchemeService.java:110-133`

The manually built JSON does not include standard SDMX 3.0 structure message envelope fields (`meta`, `links`, etc.) and
is missing `isExternalReference` on agencies. While this works for StatGPT, other SDMX clients may reject it as
non-compliant.

**Recommendation:** Document this as a known deviation. The proper fix (per design doc) is to use sdmx-core's mutable
bean API, which would produce compliant output.

---

## Low Severity / Nits

### L1. `cacheMetadata` map is over-engineered

**File:** `sdmx-proxy/.../services/agencyscheme/AgencySchemeService.java:36`

```java
private final Map<String, Object> cacheMetadata = new ConcurrentHashMap<>();
```

This map only ever stores one key (`"configHash"`). A simple `AtomicReference<String>` would be clearer and avoid the
map overhead.

### L2. `ConfigServerConfigExtractor` doesn't close the resource stream

**File:** `sdmx-proxy/.../extractor/ConfigServerConfigExtractor.java:62`

```java
var resourceStream = this.getClass().getClassLoader().getResourceAsStream(RESOURCE_FILE_NAME);
// ... no try-with-resources
byte[] fileBytes = resourceStream.readAllBytes();
```

**Recommendation:** Wrap in try-with-resources.

### L3. Feign decoder in config server returns `String` for config API

**File:** `sdmx-proxy/.../configserver/ConfigServerFeignApi.java:11`

```java
String getConfig(@Param("apiKey") String apiKey);
```

The Feign client on the proxy side returns `String`, which then requires manual deserialization in
`ConfigServerConfigExtractor`. If the Feign client returned `ProxyConfiguration` directly (with `JacksonDecoder` already
configured in `ConfigServerFeignConfig`), the extra deserialization step could be eliminated.

### L4. `FeignDialStorageClient` has no `dialStorageAvailable` tracking

**File:** `sdmx-config-server/.../dialstorage/FeignDialStorageClient.java`

The agent summary mentioned a `dialStorageAvailable` flag, but the actual code does not track availability within
`FeignDialStorageClient`. It just throws on failure. The `ConfigService` sets `dialStorageAvailable` on successful init
but not on subsequent write failures (line 70 sets it to `true` on write, but doesn't set `false` on write failure since
it throws).

### L5. `ConfigController.updateConfig` returns `notFound()` body with no message

**File:** `sdmx-proxy/.../controller/ConfigController.java:41`

```java
return ResponseEntity.notFound().build();
```

No response body explaining why POST is disabled. Clients get an empty 404.

### L6. Unused `includeHistory` parameter on `translateDataQuery`

**File:** `sdmx-proxy/.../services/translator/QueryTranslatorImpl.java:346`

The `includeHistory` parameter is accepted but never used in the method body or passed to the builder.

### L7. AgencySchemeApi missing error response annotations

**File:** `sdmx-proxy/.../api/AgencySchemeApi.java`

Only `@ApiResponse(responseCode = "200")` is declared. Should also document 500 for internal errors during sub-agency
discovery.

---

## Design Conformance Assessment

### Fully Implemented

| Design requirement                                                     | Status |
|------------------------------------------------------------------------|--------|
| New `sdmx-config-server` module with DIAL Storage backend              | Done   |
| `CONFIG_SERVER` source type with polling                               | Done   |
| Remove `ENV`, `DIAL_STORAGE` source types                              | Done   |
| Remove `secondaryRegistries` from `AgencyConfiguration`                | Done   |
| Add `allowSubAgencies` field                                           | Done   |
| Two-step `findAgencyConfig` with longest-prefix matching               | Done   |
| Reject `agency=*` and comma-separated with 501                         | Done   |
| `POST /api/config` removed from proxy (behind flag for E2E)            | Done   |
| `GET /api/config` remains on proxy for debugging                       | Done   |
| Config server API key auth                                             | Done   |
| Config server validation (registries, agencies, referential integrity) | Done   |
| Proxy polling with configurable interval                               | Done   |
| Proxy starts with classpath config as baseline                         | Done   |
| Synthetic AgencyScheme endpoint                                        | Done   |
| Sub-agency discovery via upstream dataflow queries                     | Done   |
| Config-hash-based cache invalidation for AgencyScheme                  | Done   |

### Partially Implemented

| Design requirement                              | Gap                                        |
|-------------------------------------------------|--------------------------------------------|
| Config server 503 when DIAL Storage unavailable | Controller doesn't check availability (C3) |
| Health indicator for DIAL Storage connectivity  | No `HealthIndicator` bean (M7)             |
| Agency name validation (leading/trailing dots)  | Not implemented (M5)                       |
| AgencyScheme via sdmx-core mutable bean API     | Uses manual JSON building instead (H4)     |
| Config server request size limit (1MB)          | Not configured in application.yaml         |
| Duplicate agency name validation                | Not implemented (M6)                       |

### Not Implemented (deferred or missing)

| Design requirement                             | Notes                                      |
|------------------------------------------------|--------------------------------------------|
| ETag-based conditional polling                 | Marked as future improvement in design doc |
| Config versioning and rollback                 | Explicitly out of scope                    |
| OpenTelemetry custom metrics for config server | Design doc deferred to implementation      |

---

## Removed Code Assessment

The following were cleanly removed from the proxy:

- `StructureFanOutException.java` -- replaced by `UnsupportedAgencyWildcardException`
- `DialStorageConfigExtractorProxy.java` -- moved to config server
- `DialStorageProxyConfigurationWriter.java` -- moved to config server
- `DialStorageClient.java`, `FeignDialStorageClient.java`, `DialStorageFeignApi.java` -- moved to config server
- `DialStorageConfigCache.java`, `RedisDialStorageConfigCache.java`, `CaffeineDialStorageConfigCache.java` -- no longer
  needed
- `DialStorageSourceProperties.java`, `DialStorageUploadRequest.java` -- moved to config server
- `EnvProxyConfigExtractor.java` -- source type removed
- `FileSystemProxyConfigurationWriter.java`, `StubProxyConfigurationWriter.java`, `ProxyConfigurationWriter.java` --
  writer interface removed entirely
- `DialStorageConfigTtlProperties.java` -- no longer needed
- Fan-out code in `AdapterRouterImpl` (`getStructuresWithFanOut()`) -- replaced by AgencyScheme endpoint
- Fan-out code in `QueryTranslatorImpl` (`requiresFanOut()`, `translateToFanOutStructures()`) -- replaced by 501
  rejection
- Fan-out code in `SdmxStructure30Controller` -- removed

The removal is thorough and no dead references were left behind.

---

## Test Coverage Assessment

### Good

- **`AgencyRoutingServiceImplTest`** (375 lines) -- comprehensive coverage of exact match, sub-agency routing,
  longest-prefix, cross-reference routing, error cases. Well-structured with nested test classes.
- **`QueryTranslatorImplTest`** (999 lines) -- extensive coverage including wildcard rejection, format determination,
  version selection. Updated to remove fan-out tests and add wildcard tests.
- **`AgencySchemeServiceTest`** (185 lines) -- covers configured agencies, sub-agency discovery, upstream failure
  handling, caching.
- **`ConfigValidatorTest`** (118 lines) -- covers null config, empty lists, missing fields, referential integrity,
  duplicates.

### Gaps

- No integration tests for `ConfigServerConfigExtractor` + `ConfigServerPoller` working together
- No tests for `ConfigServerFeignConfig` bean creation
- No tests for `ApiKeyAuthFilter` (auth bypass when key is blank, rejection when key is wrong, actuator bypass)
- No tests for `ConfigService` startup failure path (DIAL Storage unavailable at init)
- No tests for `ConfigServerController` HTTP layer (status codes, error responses)
- `AgencySchemeServiceTest` does not test the race condition scenario
- No test for `ConfigController` when `testEndpointEnabled=false`
- The `//TODO WRITE TESTS FOR IT` comments on `determineStructureReturnFormat`, `determineDataReturnFormat`,
  `determineAvailabilityReturnFormat`, and `findMatchingFormat` in `QueryTranslatorImpl` are pre-existing but still
  unaddressed

---

## Overall Assessment

**Verdict: Approve with required changes (C1-C3), recommended changes (H1-H4, M1-M7).**

The PR successfully implements a significant architectural change -- extracting config management into a separate
microservice and simplifying the proxy's routing model. The code is clean, follows project conventions (Lombok,
constructor injection, API+Controller pattern), and the test coverage for the core routing logic is strong.

The critical issues (C1-C3) should be fixed before merge as they represent correctness/conformance gaps. The
high-severity items are worth addressing in this PR or as an immediate follow-up. Medium and low items can be tracked as
tech debt.
