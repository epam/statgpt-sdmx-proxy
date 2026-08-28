# SDMX Proxy Performance Patterns

Reference for the Performance Optimization Engineer skill. Read when checking
pattern compliance or drafting concrete fixes.

## Request path & I/O

**Do**
- Return responses via `StreamingResponseBody` so conversion can write directly to the client when possible.
- Use `InputStream.transferTo(OutputStream)` on bypass paths where no transformation is needed.
- Keep upstream calls inside Feign clients with configured connection/read timeouts (`Request.Options`).
- Close streams in try-with-resources; avoid holding upstream connections open longer than needed.

**Avoid**
- `readAllBytes()` / `ByteArrayOutputStream` buffering on large data responses unless a fixture or metadata-preservation step requires it.
- Re-fetching structures within the same request when `getSdmxBeans()` / `CacheService.getRawStructures()` already has them.
- Unbounded upstream queries (missing key/filters) when the registry would return huge payloads.

## Amplification (N+1 upstream calls)

**Do**
- Fetch structures once per data/availability flow and reuse via `getSdmxBeans()` and the raw-structures cache.
- Collapse limit-emulation work: TC fast-path first, then probe-budgeted bisect; cache shrunk query via `putLimitEmulationShrinkFilters`.
- Run independent structure fan-out legs concurrently (`CompletableFuture` + bounded executor) when fan-out is enabled.

**Avoid**
- Loop → separate registry HTTP call per item on the request path.
- Re-running availability probes for identical `(registry, agency, resource, version, key, filters, limit)` shapes.
- Structure fan-out that re-fetches the same registry/agency without cache benefit.

## Caching

Prefer existing utilities:
- `CacheService` — three domains: raw parsed structures, ready responses, limit-emulation shrink results
- `CacheKeyGenerator` — deterministic keys for structure, response, fan-out, and limit-emulation entries
- `InMemoryCacheService` (Caffeine) for single-instance; `RedisCacheService` with TTL jitter for distributed
- `CacheProperties` / `sdmxproxy.cache.ttl.*` for TTL configuration

**Do**
- Check ready-response cache before conversion (`getReadyResponse`).
- Check raw-structures cache before upstream fetch + parse (`getRawStructures`).
- Use format bypass (`FormatSupportChecker.canBypassStructureFormat` / `canBypassDataFormat`) when the upstream already returns the requested media type — skip conversion entirely.
- Add TTL jitter on Redis writes to reduce stampedes.
- Skip caching partial fan-out responses when any leg failed.

**Avoid**
- Caching responses whose keys omit relevant query parameters or `Accept` headers.
- Caching converted bytes without accounting for fixture differences.
- Unbounded in-memory caches without TTL (Caffeine configs must keep `expireAfterWrite`).

## Limit emulation (design 014)

**Do**
- Use TC fast-path when combinatorial cube size ≤ client limit — skip availability probes entirely.
- Respect `limitEmulationProbeBudget`; lock dimensions at last overshoot when budget exhausted.
- Cache successful bisect results in the limit-emulation domain.
- Truncate registry responses with the format-specific `SeriesLimitTruncator` rather than over-fetching indefinitely.

**Avoid**
- Availability probe loops without checking the shrink cache first.
- Issuing data calls before structure/DSD needed for TC or bisect is available.
- Ignoring native `supportsLimit` when the registry already honors `limit`.

## Conversion & fixtures

**Do**
- Prefer `StreamingDataConversionService.convert(..., outputStream, ...)` when no capture/re-inject step is required.
- Apply fixtures as stream transforms when the fixture implementation supports it (`StreamingFixtureIO`).
- Parse structures once from cached bytes via `StreamingStructureConversionService.parseStructures`.

**Avoid**
- Full-buffer → convert → buffer → write when streaming conversion is sufficient.
- Re-parsing the same structure XML/JSON multiple times in one request.
- Running metadata-attribute capture/re-inject unless the endpoint fixtures require it (those paths intentionally buffer).

## Concurrency & fan-out

**Do**
- Use a bounded executor for structure fan-out (`Executors.newFixedThreadPool(queries.size())` scoped to the request).
- Propagate partial failure correctly: merge successful legs, do not cache when any leg failed.
- Reuse shared Feign client instances from `SdmxApiClientProviderImpl`.

**Avoid**
- Global unbounded thread pools for per-request fan-out.
- Blocking joins without timeout on fan-out futures under load.
- Parallel fan-out for client-facing wildcard/multi-agency queries that the API intentionally rejects (501).

## Resilience (upstream registries)

**Do**
- Use per-registry, per-operation circuit breakers from `Resilience4jComponentFactory`.
- Retry only transient failures; use `RateLimitRetryClient` with bounded total wait for 429 responses.
- Configure timeouts per registry; fail fast rather than hanging servlet threads.
- See [docs/RESILIENCE_DOCUMENTATION.md](../../../docs/RESILIENCE_DOCUMENTATION.md) for defaults and overrides.

**Avoid**
- Retry storms on non-transient 4xx (except configured rate-limit retry).
- Sharing one circuit breaker across unrelated registries or operations.
- Timeouts shorter than expected conversion time for large structure payloads (false failures).

## Observability for validation

When suggesting validation, prefer:
- OpenTelemetry spans around upstream Feign calls, conversion, cache get/put, and limit-emulation probes
- Metrics: cache hit/miss ratio per domain, probe count per limit-emulation request, fan-out leg failures
- Before/after comparison on a representative structure/data query (latency p50/p95, heap allocation)
- E2E or load tests against real registries for regression detection

Runtime proof beats speculative micro-optimizations.

## Out of scope for this repo

These StatGPT-platform concerns do not apply here:
- Python async/await, asyncio, asyncpg, aiohttp
- LLM/agent pipelines, embedding batches, vector/hybrid search
- SQLAlchemy / ORM N+1

When reviewing cross-service flows (StatGPT → SDMX Proxy), focus on proxy-side latency, payload size, cache effectiveness, and upstream call count — not agent/LLM internals.
