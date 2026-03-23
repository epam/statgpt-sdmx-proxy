# Fan-Out Design Document

## Document Purpose

This document describes the architecture and design for implementing the fan-out pattern in SDMX-Proxy. Fan-out will be
used exclusively for structure queries.

## Current State

### Registry Selection Architecture

Currently, the system works as follows:

1. **Registry Selection**: A single registry is selected by `agencyID` through
   `QueryTranslatorImpl.getRegistryConfigurationForAgency()`
2. **Constraint**: If an agency is supported by multiple registries, an exception is thrown
3. **Query**: The query goes to only one selected registry

### Caching

The system has two levels of caching:

1. **Structure Cache (POJO)**: `CacheService.getParsedStructures()` / `putParsedStructures()`
    - Key: `structure:{agencyId}:{resourceId}:{version}:{references}:{detail}`
    - Stores: `SdmxBeans` (parsed structures)

2. **Ready Response Cache**: `CacheService.getReadyResponse()` / `putReadyResponse()`
    - Key: `response:structure:{agencyId}:{resourceId}:{version}:{references}:{detail}:{md5Hash(Accept+queryParams)}`
    - Stores: `byte[]` (ready responses in the required format)

### Current Structure Endpoints

```
GET /api/structure/{structureType}/{agencyId}/{resourceId}/{version}
```

All parameters are required (except `references` and `detail`).

## Fan-Out Requirements

### Use Cases

Fan-out supports queries in the following cases:

1. **Wildcard agency**: `agencyId = "*"` (SDMX 3.0 uses `"*"` as the placeholder for "any maintenance agency")
    - `GET /api/structure/{structureType}/*/{resourceId}/{version}` - All structures of specific type from all
      registries
    - `GET /api/structure/{structureType}/*` - All structures of specific type from all registries (when
      resourceId/version omitted)
    - **Note**: `"all"` is treated as a regular agency ID (not a wildcard). Only `"*"` triggers fan-out.

2. **Comma-separated agencies**: `agencyId = "BIS,AMF"` (SDMX 3.0 uses comma as separator)
    - Fan-out is triggered only if agencies map to different registries
    - If all agencies map to the same registry → normal query path (no fan-out)
    - Example: `GET /api/structure/{structureType}/BIS,AMF/{resourceId}/{version}`

**Note**:

- Structure type must always be specified. There is no "all structure types" query.
- SDMX 2.1 uses `"all"` keyword and `+` as separator, but our API is SDMX 3.0 only (`"*"` wildcard and comma separator).
  Conversion to SDMX 2.1 format for SDMX 2.1 registries will be handled in lower layers (future task).

### Operating Principles

1. **Parallelism**: Queries to all registries must be executed in parallel (multi-threaded)
2. **Aggregation**: Results from all registries must be combined into a single response
3. **Caching**: Fan-out works only through structure cache (POJO), response-level cache is not used
4. **Per-Registry Caching**: Each registry query is cached separately using existing cache key format
5. **Pre-fan-out Logic**: Cache must be checked per registry before querying registries

## Architecture Decisions

### 1. Implementation Location

**Decision**: Fan-out logic is implemented directly in `AdapterRouter`, not as a separate service.

**Rationale**:

- After aggregation, we still need to convert `SdmxBeans` to JSON/XML, which `AdapterRouter` handles
- Keeps aggregation and conversion logic together
- Avoids building a wrapper around `AdapterRouter`

### 2. Query Structure

**Decision**: Use `List<TranslatedStructureQuery>` instead of creating a new query type.

**Implementation**:

- `QueryTranslator` gets a new method: `translateToFanOutStructures(...)` that returns `List<TranslatedStructureQuery>`
- Each `TranslatedStructureQuery` in the list represents a query to one registry
- Controller detects fan-out requirement and calls the new method

### 3. Fan-Out Detection

**Decision**: Fan-out is needed when:

1. `agencyId = "*"` → always fan-out (wildcard placeholder for SDMX 3.0)
2. `agencyId` contains comma separator (`,`) AND agencies map to different registries → fan-out
3. `agencyId` contains comma separator but all agencies map to same registry → normal query (no fan-out)

**Implementation**:

- Controller checks if fan-out is needed:
    - If `agencyId.equals("*")` → fan-out (wildcard placeholder)
    - If `agencyId.contains(",")` → delegate to `QueryTranslator` to check if agencies are in different registries
    - If `agencyId.equals("all")` → treat as regular agency ID (normal query path, no special handling)
- `QueryTranslator` has logic to:
    - Split `agencyId` by comma
    - Validate all agencies exist in configured registries (fail fast if any invalid)
    - Create mapping: agency → registry
    - Group by registry
    - If all agencies map to one registry → return single `TranslatedStructureQuery` (normal path)
    - If agencies map to multiple registries → return `List<TranslatedStructureQuery>` (fan-out path)

### 4. SDMX Version Selection

**Decision**: Use the same version selection logic as regular queries (prefer 3.0, fallback to 2.1).

**Implementation**:

- For each registry, check if structure type is supported in 3.0
- If supported in 3.0, use 3.0 version configuration
- If not supported in 3.0 but supported in 2.1, use 2.1 version configuration
- If not supported in either version, skip that registry (log warning)

### 5. Registry Filtering

**Decision**: Query all configured registries that support the requested structure type.

**Implementation**:

- Get all registries from `ProxyConfiguration.getConfigs()`
- For each registry, check `StructureEndpointConfig.getSupportedStructures()`
- Only include registries that support the requested structure type
- Apply version selection logic (prefer 3.0, fallback to 2.1)

### 6. Caching Strategy

**Decision**: Cache per registry separately, then aggregate results.

**Implementation**:

- Each `TranslatedStructureQuery` uses existing `CacheKeyGenerator.generateStructureKey()`
- For wildcard fan-out (`agencyId = "*"`): cache key uses `"*"` as agencyId
    - Cache key format: `structure:*:{resourceId}:{version}:{references}:{detail}`
- For comma-separated agencies fan-out: cache key uses the combined agencyId as-is
    - Cache key format: `structure:BIS,AMF:{resourceId}:{version}:{references}:{detail}`
    - **Not** separate cache entries per agency - it's one query, one cache entry
- Check cache per registry before querying
- If cache hit for a registry, use cached `SdmxBeans`
- If cache miss, query registry and cache the result
- No fan-out-level cache (only per-registry cache)

### 7. Parallel Execution

**Decision**: Create `ExecutorService` per request with pool size = number of registries in the request.

**Implementation**:

- Create `ExecutorService` with `Executors.newFixedThreadPool(registryCount)` for each fan-out request
- Use `CompletableFuture` for each registry query
- Shutdown executor after request completes
- Overhead is acceptable (object creation is fast)

### 8. Timeouts

**Decision**: Use per-registry timeout from ResilienceConfig. No overall fan-out timeout.

**Implementation**:

- Each registry query respects its configured timeout from `RegistryRetryConfig`
- If a registry times out, log error and continue with other registries
- No overall timeout for the entire fan-out operation

### 9. Error Handling

**Decision**: Fail-fast validation, then partial results for runtime errors.

**Implementation**:

- **Pre-query validation** (fail-fast):
    - If any agency in comma-separated list is invalid (not found in any registry):
        - Throw exception immediately (don't proceed with any registry queries)
        - User must fix the request
- **Runtime errors** (partial results):
    - If a registry fails or times out during query execution:
        - Log error at WARN level with registry name
        - Continue processing other registries
        - Return results only from successful registries
    - If all registries fail:
        - Throw custom exception: `StructureFanOutException`
        - Include details about which registries failed

### 10. Structure Deduplication

**Decision**: Keep first occurrence, log WARN if duplicates found.

**Implementation**:

- Structures are identified by `agencyId:resourceId:version`
- If duplicate found (same structure from multiple registries):
    - Keep first occurrence
    - Log WARN: "Duplicate structure found: {agencyId}:{resourceId}:{version}, keeping first"
- Order of structures doesn't matter

**Note**: Multiple registries supporting the same agency is impossible (misconfiguration). This should never happen.

### 11. Empty Results Handling

**Decision**:

- If all registries return empty results → return empty `SdmxBeans`
- If some registries return empty → skip empty results, aggregate non-empty
- If all registries fail → throw `StructureFanOutException`

**Note**: Empty results are different from failures. Empty means successful query with no data. Failure means
error/timeout.

### 12. Aggregation

**Decision**: Aggregate `SdmxBeans` from multiple registries in `AdapterRouter` before conversion.

**Implementation**:

- Aggregate `SdmxBeans` objects into a single `SdmxBeans` instance
- Handle deduplication (keep first)
- Then convert aggregated `SdmxBeans` to requested format (JSON/XML)
- Mark aggregation logic with `// TODO: Implement aggregation here` if implementation details are unclear

### 13. Accept Header Handling

**Decision**: Use the same Accept header logic as regular queries.

**Implementation**:

- Parse media type from Accept header
- Determine SDMX version (3.0 or 2.1)
- Use same format selection logic as regular queries
- No special handling for fan-out queries

### 14. Method Naming

**Decision**:

- `QueryTranslator.translateToFanOutStructures(...)` - returns `List<TranslatedStructureQuery>`
- `AdapterRouter.getStructuresWithFanOut(List<TranslatedStructureQuery> queries)` - executes fan-out

### 15. No New Endpoints

**Decision**: Modify existing structure endpoint to support fan-out, don't add new endpoints.

**Implementation**:

- Existing endpoint: `GET /api/structure/{structureType}/{agencyId}/{resourceId}/{version}`
- When `agencyId = "*"`, trigger fan-out logic (wildcard placeholder for SDMX 3.0)
- When `agencyId = "all"`, treat as regular agency ID (normal query path, no special handling)
- When `agencyId` contains comma (`,`) and agencies map to different registries, trigger fan-out logic
- When `agencyId` contains comma but all agencies map to same registry, use normal query path
- No changes to endpoint signature

### 16. Comma-Separated Agencies Handling

**Decision**: Support comma-separated agencies (SDMX 3.0 format), fan-out only if agencies are in different registries.

**Implementation**:

- **Separator**: Only comma (`,`) - SDMX 3.0 format. Plus (`+`) is SDMX 2.1 format (conversion handled in lower layers,
  future task)
- **Wildcard**: Only `"*"` is valid wildcard placeholder for SDMX 3.0. `"all"` is treated as a regular agency ID (no
  special handling)
- **Agency validation**: Validate all agencies before proceeding (fail-fast if any invalid)
- **Registry mapping**: Create mapping agency → registry, then group by registry
- **Same registry case**: If all agencies map to one registry → normal query (no fan-out)
- **Different registries case**: If agencies map to multiple registries → fan-out (create queries per registry)
- **Cache keys**: Use combined agencyId as-is: `structure:BIS,AMF:{resourceId}:{version}:{references}:{detail}` (or
  `structure:*:...` for wildcard)
- **ResourceId and version**: Same for all agencies (cannot differ per agency)

## Architecture Components

### Modified Components

#### 1. QueryTranslator

**New Method**:

```java
/**
 * Translates structure query parameters to queries for fan-out.
 * Handles two cases:
 * 1. Wildcard agency ("*") → creates queries for all registries
 * 2. Comma-separated agencies → creates queries per registry (only if agencies are in different registries)
 *
 * If comma-separated agencies all map to same registry, returns single TranslatedStructureQuery (normal path).
 *
 * @param structureType Structure type (e.g., "datastructure")
 * @param agencyId Agency ID - can be "*" (wildcard) or comma-separated list (e.g., "BIS,AMF")
 *                  Note: "all" is treated as a regular agency ID (not a wildcard)
 * @param resourceId Resource ID (can be null for "all")
 * @param version Version (can be null for "latest")
 * @param references References parameter
 * @param detail Detail parameter
 * @param acceptHeader Accept header
 * @return List of TranslatedStructureQuery (one per registry for fan-out, or single query for normal path)
 * @throws IllegalArgumentException if any agency is invalid (not found in any registry)
 */
List<TranslatedStructureQuery> translateToFanOutStructures(
        String structureType,
        String agencyId,
        String resourceId,
        String version,
        String references,
        String detail,
        String acceptHeader
);
```

**Implementation Notes**:

- **Wildcard case** (`agencyId = "*"`):
    - Get all registries from `ProxyConfiguration`
    - Filter registries that support the structure type
    - For each registry, apply version selection (prefer 3.0, fallback to 2.1)
    - Create `TranslatedStructureQuery` with `agencyId = "*"` (for cache key)
- **Comma-separated case** (`agencyId = "BIS,AMF"`):
    - Split `agencyId` by comma
    - Validate all agencies exist (fail-fast if any invalid)
    - Create mapping: agency → registry (using `getRegistryConfigurationForAgency()`)
    - Group agencies by registry
    - If all agencies map to one registry → return single `TranslatedStructureQuery` (normal path)
    - If agencies map to multiple registries → return `List<TranslatedStructureQuery>` (fan-out path)
    - For each registry group, create query with comma-separated agencies for that registry
    - Cache key uses original `agencyId` as-is (e.g., `structure:BIS,AMF:...`)

#### 2. AdapterRouter

**New Method**:

```java
/**
 * Executes fan-out query across multiple registries in parallel.
 *
 * @param queries List of TranslatedStructureQuery, one per registry
 * @return Aggregated SdmxBeans from all registries
 */
SdmxBeans getStructuresWithFanOut(List<TranslatedStructureQuery> queries);
```

**Implementation Flow**:

1. Check cache per registry (for each query in the list)
2. Create `ExecutorService` with pool size = queries.size()
3. Submit parallel queries using `CompletableFuture`
4. Wait for all futures to complete
5. Aggregate results (handle deduplication)
6. Cache results per registry
7. Return aggregated `SdmxBeans`

**Modified Method**:

- `getSdmxBeans(TranslatedStructureQuery query)` - may need to handle `agencyId = "*"` case

#### 3. Controller

**Modified Logic**:

```java
boolean needsFanOut = "*".equals(agencyId) || agencyId.contains(",");

if(needsFanOut){
// Fan-out path (or normal path if comma-separated agencies map to same registry)
List<TranslatedStructureQuery> queries = queryTranslator.translateToFanOutStructures(
        structureType, agencyId, resourceId, version, references, detail, accept
);
    
    if(queries.

size() ==1){
// All comma-separated agencies map to same registry → normal path
StreamingResponseBody response = adapterRouter.getStructures(queries.get(0));
// ...
    }else{
// Fan-out path (multiple registries)
SdmxBeans aggregatedBeans = adapterRouter.getStructuresWithFanOut(queries);
// Convert to StreamingResponseBody...
    }
            }else{
// Normal path (existing logic)
TranslatedStructureQuery query = queryTranslator.translateStructureQuery(...);
StreamingResponseBody response = adapterRouter.getStructures(query);
// ...
}
```

### New Exception

```java
public class StructureFanOutException extends RuntimeException {
    public StructureFanOutException(String message, List<String> failedRegistries) {
        super(message);
        // Store failed registries for logging/debugging
    }
}
```

## Execution Flow

```
1. Request arrives at Controller
   ↓
2. Controller checks if fan-out is needed:
   ├─ If agencyId = "*" → fan-out path (wildcard placeholder)
   ├─ If agencyId = "all" → normal path (treated as regular agency ID)
   ├─ If agencyId contains "," → delegate to QueryTranslator to check
   └─ Otherwise → normal path (existing flow)
   ↓
3. Controller calls QueryTranslator.translateToFanOutStructures(...)
   ↓
4. QueryTranslator:
   a. If agencyId = "*":
      - Gets all registries from ProxyConfiguration
      - Filters registries that support structure type
      - Applies version selection (prefer 3.0, fallback 2.1)
      - Creates List<TranslatedStructureQuery> (one per registry)
   b. If agencyId contains ",":
      - Splits agencyId by comma
      - Validates all agencies exist (fail-fast if any invalid)
      - Creates mapping: agency → registry
      - Groups agencies by registry
      - If all map to one registry → returns single TranslatedStructureQuery (normal path)
      - If map to multiple registries → returns List<TranslatedStructureQuery> (fan-out path)
   ↓
5. If QueryTranslator returned single query → normal path (existing flow)
   If QueryTranslator returned list → fan-out path
   ↓
6. Controller calls AdapterRouter.getStructuresWithFanOut(queries)
   ↓
7. AdapterRouter.getStructuresWithFanOut():
   a. For each query, check cache (CacheService.getParsedStructures)
      ├─ If cache hit → use cached SdmxBeans
      └─ If cache miss → proceed to step b
   b. Create ExecutorService with pool size = queries.size()
   c. Submit parallel queries:
      ├─ Registry 1 → CompletableFuture<Optional<SdmxBeans>>
      ├─ Registry 2 → CompletableFuture<Optional<SdmxBeans>>
      └─ Registry N → CompletableFuture<Optional<SdmxBeans>>
   d. Wait for all futures (CompletableFuture.allOf)
   e. Handle errors:
      ├─ If registry fails → log WARN, continue
      └─ If all fail → throw StructureFanOutException
   f. Aggregate results:
      - Combine SdmxBeans from all registries
      - Deduplicate (keep first, log WARN if duplicates)
   g. Cache results per registry (CacheService.putParsedStructures)
   h. Return aggregated SdmxBeans
   ↓
8. Convert aggregated SdmxBeans to StreamingResponseBody (existing conversion logic)
   ↓
9. Return response
```

## Implementation Plan

### Phase 1: Exception and Base Infrastructure

- [ ] Create `StructureFanOutException`
- [ ] Verify cache key format works with `agencyId = "*"` or `"all"`

### Phase 2: QueryTranslator Enhancement

- [ ] Implement `translateToFanOutStructures()` method
- [ ] Add logic to filter registries by structure type support
- [ ] Apply version selection logic per registry
- [ ] Unit tests for `translateToFanOutStructures()`

### Phase 3: AdapterRouter Fan-Out Implementation

- [ ] Implement `getStructuresWithFanOut()` method
- [ ] Implement per-registry cache checking
- [ ] Implement parallel execution with `ExecutorService`
- [ ] Implement aggregation logic (combine `SdmxBeans`, deduplicate)
- [ ] Implement error handling (partial results, logging)
- [ ] Unit tests for `getStructuresWithFanOut()`

### Phase 4: Controller Integration

- [ ] Modify controller to detect `agencyId = "all"` or `"*"`
- [ ] Add fan-out path in controller
- [ ] Integration tests with mocked registries

### Phase 5: Testing

- [ ] Unit tests for all components
- [ ] Integration tests with multiple registries
- [ ] Performance tests (verify parallelism works)
- [ ] Error scenario tests (some registries fail, all fail, etc.)

### Phase 6: Documentation

- [ ] Update API documentation
- [ ] Add examples of fan-out queries
- [ ] Document error handling behavior

## Open Questions / Future Considerations

1. **SDMX 2.1 Separator Conversion**: SDMX 2.1 uses `+` as separator, but our API is SDMX 3.0 (comma). When querying
   SDMX 2.1 registries, we need to convert comma to plus. This is a future task - check if `GenericRegistryAdapter` or
   lower layers support this conversion.

2. **Registry Configuration Changes**: If registry configuration changes at runtime, cached results may become stale.
   Current implementation relies on TTL-based cache expiration.

3. **Performance Optimization**: If fan-out queries become common, we might want to add a fan-out-level cache in the
   future. For now, per-registry caching is sufficient.

4. **Monitoring**: Consider adding metrics for:
    - Fan-out query count
    - Average number of registries queried per fan-out
    - Success/failure rates per registry
    - Aggregation time
    - Comma-separated vs wildcard fan-out ratio

## References

- SDMX REST 2.1 Specification: Section 4.3 (Structural Metadata Queries)
    - Keyword `"all"` for agencyID: "Returns artefacts maintained by any maintenance agency"
- Current implementation: `QueryTranslatorImpl`, `AdapterRouterImpl`, `CacheService`
