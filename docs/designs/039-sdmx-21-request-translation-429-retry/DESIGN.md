# Design: SDMX 2.1 request translation and HTTP 429 retry

## Context

The proxy exposes a single SDMX 3.0 REST interface and routes to registries that may speak
SDMX 2.1 or 3.0. Until now every configured registry (BIS, IMF) was 3.0, so the 2.1 code paths
existed but were never exercised end to end.

Integrating OECD (`https://sdmx.oecd.org/public/rest/`, NSI Web Service v8.19.8.0) added the first
genuine 2.1 registry. OECD has **no 3.0 endpoint**: `/public/rest/v2/...` resolves but its 406
`acceptable:` list contains only SDMX 2.1 / SDMX-JSON 1.0 structure types.

The **response** side of the pipeline already works. A throttled manual run through the proxy
returned HTTP 200 for structure (`structure+json;version=2.0.0`, `structure+xml;version=3.0.0`,
`structure+xml;version=2.1`), data (`data+json;version=2.0.0`, `data+xml;version=3.0.0`,
`data+csv;version=2.0.0`) and availability. What is missing is the **request** side: the proxy
forwards SDMX 3.0 query grammar verbatim to a 2.1 registry, and it has no handling for the HTTP 429
that a rate-limited registry returns.

Full integration report: `sdmx-proxy-e2e/build/e2e-report.md` (a build artifact -- regenerate with
the `e2e-report` skill if it has been cleaned; the numbers it contains are reproduced inline below).

## Problem

### P1 -- HTTP 429 is neither retried nor tolerated

OECD caps at roughly 20 requests/minute (measured: 40 back-to-back requests return 200 for #1-19
and 429 from #20; the same 60 requests spaced ~3.7 s apart all return 200). A single E2E run
produced **96 HTTP 429 responses**, every one of which propagated straight to the client.

Root cause: `Resilience4jComponentFactory.upstreamFailurePredicate()`
(`sdmx-proxy/src/main/java/com/epam/sdmxproxy/registry/api/Resilience4jComponentFactory.java:159`)
matches only `IOException` and `FeignException.status() >= 500`, and **the same predicate instance
is used for both** the circuit breaker (`recordException`, line 58) and the retry policy
(`retryOnException`, line 90). So 429 is invisible to both.

Naively adding 429 to that shared predicate would also make 429 count toward the circuit-breaker
failure rate -- which must not happen, because a rate limit is a client-pacing signal, not an
upstream-health signal. The predicates have to be split.

The existing per-registry `rateLimit` knob is not the mechanism: `RateLimitingInvocationHandlerFactory`
builds its limiter with `timeoutDuration(Duration.ZERO)`
(`sdmx-proxy/src/main/java/com/epam/sdmxproxy/registry/api/RateLimitingInvocationHandlerFactory.java:108`),
so it fails fast with `RateLimitExceededException` rather than pacing requests.

Backoff scale matters. The current retry defaults (`maxAttempts` 5, `initialIntervalMillis` 500,
`multiplier` 2.0, `maxIntervalMillis` 2000) total ~6.5 s of waiting -- far short of OECD's ~60 s
window. 5xx retries and 429 retries operate on different time scales and need independent settings.

`Retry-After` cannot be trusted blindly. OECD sits behind Cloudflare and returns literally
`Retry-After: 0` on its 429:

```
HTTP/1.1 429 Too Many Requests
Retry-After: 0
Server: cloudflare
```

Honouring that verbatim would retry immediately and burn every attempt.

### P2 -- `references=none` is forwarded to 2.1 and breaks the whole availability endpoint

`AvailabilityQuery30Controller.java:53` (and `AvailabilityQuery30Api.java:142`) declare
`@RequestParam(value = "references", required = false, defaultValue = "none")`.
`QueryTranslatorImpl.translateAvailabilityQuery` stores it as-is
(`.references(references != null ? references : "none")`, line 380), and
`GenericRegistryAdapterImpl.getAvailability21` (line 206) passes it to `Sdmx21AvailabilityClient`.

OECD's NSI answers `availableconstraint?references=none` with **HTTP 500**:

```
GET .../availableconstraint/OECD.ENV.EPI,DSD_AIR_GHG@DF_AIR_GHG,1.0/all/all/all?mode=exact&references=none
-> 500  Could not find structure type with class 'none'

GET .../availableconstraint/OECD.ENV.EPI,DSD_AIR_GHG@DF_AIR_GHG,1.0/all/all/all?mode=exact
-> 200
```

`none` is spec-legal and is in fact the documented default (`sdmx-rest-2.2.0/doc/availability.md:22`),
so this is an NSI defect -- but the proxy needs a workaround. Because `none` and "parameter absent"
are semantically identical per the spec, **omitting the parameter is behaviour-preserving**, not a
compromise.

This single default blocks every availability request against OECD, and by extension limit
emulation (which probes availability).

### P3 -- SDMX 3.0 wildcards are not translated into 2.1 grammar

`QueryTranslatorImpl.getVersionSpecificQueryId` (line 63) maps `*` to `all`, but it is only applied
to structure **path slots** (resourceId, version) in `translateStructureQuery`. The data/availability
**key** and the availability **componentId** are passed through untouched.

Verified against OECD:

| Sent | Result |
|------|--------|
| key `*` | 422 `Not enough key values in query, expecting 5 got 1` |
| key `USA.A.*.*.*` | 404 `NoResultsFound` |
| key `USA.A....` (2.1 form) | 200 |
| key `all` | 200 |
| componentId `*` | 500 |
| componentId `all` | 200 |
| componentId `REF_AREA` | 200 |
| componentId `REF_AREA,FREQ,POLLUTANT,MEASURE,UNIT_MEASURE` | 500 `Requested component at position 0 not found` |

`mergeAllWildcardKey` (`QueryTranslatorImpl.java:770`) collapses `*.*.*` to `*` -- the 3.0 spelling,
still wrong for 2.1. `replaceEmptyDimensionsWithWildcard` is the opposite-direction knob (IMF) and
has no inverse.

Because the proxy's public interface is always 3.0, real clients always send `*`. Data and
availability are therefore unusable against any 2.1 registry today.

The comma-joined componentId in the last row is not a hypothetical: `LimitEmulationServiceImpl`
builds exactly that. NSI's error message shows it treats the whole comma string as a single
component id. No SDMX-REST 2.1 specification is present in this workspace (`sdmx-rest-2.2.0/` is the
**3.0** spec), so this is stated as **empirically established against OECD NSI 8.19**, not as a
spec-derived claim. Note the 3.0 spec explicitly marks `componentId` as multiple-valued
(`sdmx-rest-2.2.0/doc/availability.md:19`, "Multiple values? Yes", default `*`) -- which is why the
normalizer must not collapse a *client-supplied* list (see Step 4 and Edge Case 10).

### P4 -- SDMX 3.0 `version` / `resourceID` path slots are not translated either

The same gap extends past the key. `translateDataQuery` sets `.version(version)`
(`QueryTranslatorImpl.java:541`) and `translateAvailabilityQuery` sets `.version(version)` (line 374)
verbatim, and `GenericRegistryAdapterImpl.getFlowRef` (line 136) concatenates them into the 2.1
`flowRef`:

```java
private String getFlowRef(String agencyId, String resourceId, String version) {
    return agencyId + "," + resourceId + "," + version;
}
```

`normalizePathSlot` is called only from `SdmxStructure30Controller` (lines 62-64) and
`translateStructureQuery` / `translateWildcardStructureFanOut` -- **never** from the data or
availability controllers. Per `sdmx-rest-2.2.0/doc/availability.md:17-18` both `version` and `key`
default to `*` and are multiple-valued, so `GET /data/dataflow/OECD.ENV.EPI/DF_AIR_GHG/*/...`
produces the 2.1 flowRef `OECD.ENV.EPI,DF_AIR_GHG,*`.

This is also internally inconsistent: `AdapterRouterImpl.getStructureQuery` routes the *same*
`version` through `translateStructureQuery`, which **does** map it to `all` -- so the DSD lookup
succeeds and only the data/availability call fails.

The E2E suite cannot catch this: `BaseRegistryTestSuite` always injects a concrete version from the
parsed URN (lines 410, 1054).

### P5 -- limit emulation constructs 2.1 availability probes directly

`LimitEmulationServiceImpl.toAvailabilityQuery21` (line 647) builds `TranslatedAvailabilityQuery`
**without going through `QueryTranslator`**, hardcoding `.references("none")` (line 669) and a
comma-joined `componentId` (line 653). Its probe and shrink keys are also built by
`keyParser.buildKey(...)` (lines 554 and 567), which emits `*` for every unconstrained dimension
(`KeyParserImpl.java:57`) -- i.e. exactly the `USA.*.*.*.*` form P3 shows OECD rejects. Fixing P2/P3
only in the translator would leave this path broken.

(`toAvailabilityQuery30`, line 611, is reached only via `runBisect30` from the non-2.1 branch at
line 192, so it is 3.0-only and needs no change.)

## Current Architecture

```
Client (always SDMX 3.0 grammar)
  |
  v
AvailabilityQuery30Controller / DataQuery30Controller
  |  references defaults to "none"; key/componentId are 3.0 (`*`)
  v
QueryTranslatorImpl.translateDataQuery / translateAvailabilityQuery
  |  applies mergeAllWildcardKey / replaceEmptyDimensionsWithWildcard (3.0-registry knobs)
  |  does NOT translate key/componentId/references for 2.1
  v
AdapterRouterImpl
  |  may overwrite key via convertKeyToFilters (normalizeOutboundFilters -> key = "*")
  |  may call LimitEmulationServiceImpl, which builds its own TranslatedAvailabilityQuery
  v
GenericRegistryAdapterImpl.getData21 / getAvailability21     <-- last choke point before HTTP
  |
  v
Feign (Sdmx21DataClient / Sdmx21AvailabilityClient)
  |  Resilience4jFeign decorators: Retry (outer) -> CircuitBreaker (inner)
  |  both driven by the SAME upstreamFailurePredicate -> 429 ignored by both
  v
OECD NSI 8.19 (SDMX 2.1 only, ~20 req/min, Cloudflare 429 with Retry-After: 0)
```

Decorator order is confirmed by the observed stack trace: `Retry.lambda$decorateCheckedFunction$3`
calls `CircuitBreaker.lambda$decorateCheckedFunction$10`, so Retry is outer and each retry attempt
passes through the circuit breaker.

## Solution

Three independent changes.

**A. Split the resilience predicates and add a dedicated 429 retry.** The circuit-breaker predicate
keeps today's semantics (IOException + 5xx). A separate 429 retry is implemented as a `feign.Client`
decorator wrapping the shared OkHttp client, configured per registry from a new `rateLimitRetry`
config block. Placing it at the `Client` level (below Feign's decoders, inside the circuit breaker)
means it sees the raw `feign.Response` -- so `Retry-After` is available directly without unwrapping
a `FeignException` -- and a retried-then-successful call never reaches the circuit breaker as a
failure at all.

*Alternative considered:* a single resilience4j `Retry` with `intervalBiFunction` branching on the
exception type. Rejected because resilience4j exposes one `maxAttempts` per `Retry` instance, so the
5xx and 429 attempt caps would have to be merged into `max(...)` -- silently changing 5xx retry
behaviour. The `Client` decorator keeps the two attempt **caps** separate.

They are not, however, fully independent in wall-clock terms: the outer `Retry` can restart the inner
429 cycle on `IOException`, so attempts multiply. That is quantified and bounded in Step 3.

**B. Omit `references=none` for 2.1.** Substitute `null`, which Feign drops from the query string.
This is verified behaviour, not an assumption: the failing request logged as
`...?mode=exact&references=none` omitted `startPeriod`, `endPeriod` and `updatedAfter` precisely
because those `@Param`s were null.

**C. Translate 3.0 wildcards into 2.1 grammar.** A new injectable `Sdmx21QueryNormalizer` service
owns four rules: key, componentId, references, and path slot (agency / resourceID / version, for the
2.1 `flowRef` -- see P4).

The first three are applied in **both** places per the agreed approach: in `QueryTranslatorImpl` (the
primary path, where the user wants the translation to live) and again defensively in
`GenericRegistryAdapterImpl`'s 2.1 methods (the last choke point, which also catches queries built by
`LimitEmulationServiceImpl` and keys rewritten by `AdapterRouterImpl.normalizeOutboundFilters`). All
four rules are **idempotent**, so running them twice is safe by construction -- this is what makes the
belt-and-braces placement viable.

`toSdmx21PathSlot` is the exception: it is applied **only** in the adapter, inside `getFlowRef`
(Step 6). The translator keeps `.version(version)` as the client sent it, because the untranslated
value is still needed for structure lookups and cache keys; only the 2.1 wire format needs `all`.

Every rule is version-gated on `SDMX_2_1`, so all of Steps 4-7 are strict no-ops for BIS and IMF.
(Steps 2-3 are *not* version-gated -- they are gated on the new `rateLimitRetry.enabled` flag
instead, which defaults to false. See Step 1.)

`LimitEmulationServiceImpl` is additionally fixed at source so its probes are correct on their own.

## Implementation Plan

### Step 1: Add the `rateLimitRetry` config block

**File (new):** `sdmx-proxy-config/src/main/java/com/epam/sdmxproxy/configuration/data/RegistryRateLimitRetryConfig.java`

A **dedicated** class, not a reuse of `RegistryRetryConfig`. Two extra fields are needed that must not
leak onto `retry`: an `enabled` flag (see below) and `maxTotalWaitMillis` (Step 3). Adding them to
`RegistryRetryConfig` would make `retry.enabled` / `retry.maxTotalWaitMillis` settable but silently
ignored, since `getOrCreateRetry` reads only its four fields.

```java
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RegistryRateLimitRetryConfig {

    /**
     * Enable retrying HTTP 429 responses for this registry.
     * If null, the application-wide default is used (which is {@code false}).
     */
    private Boolean enabled;

    /**
     * Maximum attempts per 429 cycle, including the initial call.
     * If null, the application default is used.
     */
    private Integer maxAttempts;

    private Long initialIntervalMillis;

    private Double multiplier;

    /**
     * Ceiling for a single wait, including a wait derived from a {@code Retry-After} header.
     */
    private Long maxIntervalMillis;

    /**
     * Ceiling for the summed wait across one 429 cycle. If null, defaults to the exact
     * geometric sum implied by the other fields (not a slack multiple of it).
     */
    private Long maxTotalWaitMillis;
}
```

**File:** `sdmx-proxy-config/src/main/java/com/epam/sdmxproxy/configuration/data/RegistryResilienceConfig.java`

```java
    /**
     * Retry configuration applied specifically to HTTP 429 (rate-limit) responses.
     * Kept separate from {@link #retry} because rate-limit backoff operates on a much
     * longer time scale than 5xx backoff (minutes vs seconds), and because it is opt-in.
     * If null, 429 retrying is disabled for this registry.
     */
    private RegistryRateLimitRetryConfig rateLimitRetry;
```

**`enabled` defaults to false, and that is load-bearing.** Both the 429 wrapper (Step 3) and the
relaxed slow-call threshold (Step 2) are installed in `SdmxApiClientProviderImpl.buildClient`, which
runs for **every** registry -- there is no version gate there. Without an opt-in flag this design
would silently give BIS and IMF a multi-minute in-request retry loop and disable their slow-call
detection, neither of which is intended. With `enabled: false` as the default, 3.0 registries keep
exactly today's behaviour and only OECD opts in.

**File:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/registry/api/config/ResilienceProperties.java`

Add `private RateLimitRetryProperties defaultRateLimitRetry = new RateLimitRetryProperties();`, with a
new `RateLimitRetryProperties` class (a peer of `RetryProperties`, not a subclass -- it carries
`enabled` and `maxTotalWaitMillis`). Exact defaults:

| Field | Default | Rationale |
|-------|---------|-----------|
| `enabled` | `false` | opt-in; preserves BIS/IMF behaviour |
| `maxAttempts` | `5` | 4 waits |
| `initialIntervalMillis` | `5000` | OECD's window is ~60 s |
| `multiplier` | `2.0` | matches the 5xx family |
| `maxIntervalMillis` | `60000` | one full rate-limit window |
| `maxTotalWaitMillis` | `75000` | the exact sum 5+10+20+40 s, so the budget actually binds |

**File:** `sdmx-proxy/src/main/resources/application.yaml`

Add a `defaultRateLimitRetry` block under `sdmxproxy.registry.resilience` (lines 44-67) spelling out
all six values. Every other resilience default is written out there (`defaultRetry`,
`defaultCircuitBreaker`, `defaultRateLimit`, `defaultRateLimitingEnabled`) even though the Java
classes carry the same defaults; follow that pattern. Note the file is camelCase throughout, so use
`defaultRateLimitRetry`, not kebab-case.

**Per CLAUDE.md, update `sdmx-proxy-config/README.md` in the same change:** add a `rateLimitRetry`
row to the `RegistryResilienceConfig` table (around line 104) **and** a new
`RegistryRateLimitRetryConfig` schema table documenting all six fields.

### Step 2: Split the resilience predicates

**File:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/registry/api/Resilience4jComponentFactory.java`

Replace the single `upstreamFailurePredicate()` (line 159) with two predicates. The circuit-breaker
one keeps today's exact behaviour.

```java
    /**
     * Exceptions that signal upstream-health failures (network errors, 5xx). Used by the circuit
     * breaker only. HTTP 429 is deliberately excluded: a rate limit is a client-pacing signal, not
     * an upstream-health signal, and must never open the breaker.
     */
    private Predicate<Throwable> circuitBreakerFailurePredicate() {
        return throwable -> {
            if (throwable instanceof java.io.IOException) {
                return true;
            }
            if (throwable instanceof feign.FeignException feignEx) {
                return feignEx.status() >= 500;
            }
            return false;
        };
    }
```

Use it at line 58 (`.recordException(...)`) and line 90 (`.retryOnException(...)`). The resilience4j
`Retry` continues to handle 5xx/IO only -- 429 is handled by Step 3, not here.

**Key point:** do NOT add 429 to `retryOnException` on this `Retry`. Doing so would work, but the
attempt cap would then be shared with the 5xx family, which is exactly what Step 3 avoids.

**Critical: also raise the slow-call threshold when 429 retrying is enabled.** Excluding 429 from
`recordException` is *not enough* to keep 429 from opening the breaker. `CircuitBreakerConfig.custom()`
(line 53) never touches the slow-call settings, and resilience4j 2.3.0's defaults are
`DEFAULT_SLOW_CALL_RATE_THRESHOLD = 100` and `DEFAULT_SLOW_CALL_DURATION_THRESHOLD = 60` (seconds)
-- verified with `javap -constants` on `resilience4j-circuitbreaker-2.3.0.jar`. Crucially, a call
whose exception is *not* recorded is still timed: `CircuitBreakerStateMachine.handleThrowable` routes
it to `onSuccess(duration, unit)`, which classifies it slow when it exceeds the threshold. The Step 3
retry sleeps *inside* the breaker's timed span, so a rate-limited call routinely exceeds 60 s; once
every call in the window is "slow" the rate hits 100 % and the breaker opens -- precisely the outcome
this design exists to prevent.

`slowCallRateThreshold` needs no change: it already defaults to 100, its maximum. Duration is the
only usable lever.

```java
        CircuitBreakerConfig.Builder circuitBreakerConfigBuilder = CircuitBreakerConfig.custom()
                .failureRateThreshold(failureRateThreshold)
                .minimumNumberOfCalls(minimumNumberOfCalls)
                .waitDurationInOpenState(Duration.ofMillis(waitDuration))
                .slidingWindowSize(slidingWindowSize)
                .recordException(circuitBreakerFailurePredicate());
        if (rateLimitRetryEnabled) {
            // A rate-limited call is slow by construction -- Step 3 sleeps inside this span, and a
            // non-recorded exception is still timed via onSuccess(duration). Left at the 60s default,
            // slow-call detection would open the breaker on a 429 storm.
            circuitBreakerConfigBuilder.slowCallDurationThreshold(slowCallDurationThreshold(selectedRegistry));
        }
        CircuitBreakerConfig circuitBreakerConfig = circuitBreakerConfigBuilder.build();
```

**Sizing.** The breaker times the whole inner cycle -- sleeps **plus** the network time of every
attempt -- so the threshold must exceed both:

```
slowCallDurationThreshold = maxTotalWaitMillis + (rateLimitRetry.maxAttempts * readTimeout)
```

With the OECD config (`maxTotalWaitMillis` 75 s, `maxAttempts` 6, `readTimeout` 120 s) that is
~795 s. Sizing it against the sleep budget alone would leave the breaker opening on exactly the
scenario this guards. Note `defaultReadTimeout` is **240000 ms** (`application.yaml:48`), not the
30000 ms in `ResilienceProperties`' Java default -- use the resolved value.

**Gated on `enabled`.** `getOrCreateCircuitBreaker` is shared by every registry and is not version-
gated, so applying this unconditionally would silently disable slow-call detection for BIS and IMF,
where the 60 s default is live and reachable (their `readTimeout` can exceed it). Guarding on the
registry's resolved `rateLimitRetry.enabled` keeps 3.0 registries exactly as they are today.

Cover with a unit test asserting both directions: enabled -> threshold exceeds
`maxTotalWaitMillis + maxAttempts * readTimeout`; disabled -> config is untouched.

### Step 3: Add the 429 retry `Client` decorator

**File (new):** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/registry/api/http/RateLimitRetryClient.java`

Implements `feign.Client`, delegates to the wrapped client, and retries on HTTP 429 with exponential
backoff. Verified APIs: `feign.Client.execute(Request, Request.Options)` returns `feign.Response`;
`Response.status()` and `Response.headers()` (`Map<String, Collection<String>>`) are available in
feign 13.6.

```java
@Slf4j
@RequiredArgsConstructor
public class RateLimitRetryClient implements Client {

    private static final int TOO_MANY_REQUESTS = 429;

    private final Client delegate;
    private final int maxAttempts;
    private final long initialIntervalMillis;
    private final double multiplier;
    private final long maxIntervalMillis;
    private final long maxTotalWaitMillis;
    private final Sleeper sleeper;

    @Override
    public Response execute(Request request, Request.Options options) throws IOException {
        Response response = delegate.execute(request, options);
        long totalWaited = 0L;
        for (int attempt = 1; attempt < maxAttempts && response.status() == TOO_MANY_REQUESTS; attempt++) {
            long waitMillis = resolveWaitMillis(response, attempt);
            if (totalWaited + waitMillis > maxTotalWaitMillis) {
                log.warn("Registry returned 429 for {}; total wait budget {} ms exhausted, giving up", request.url(), maxTotalWaitMillis);
                break;
            }
            log.warn("Registry returned 429 for {}; retry {}/{} in {} ms", request.url(), attempt, maxAttempts - 1, waitMillis);
            closeQuietly(response);
            if (!sleeper.sleep(waitMillis)) {
                break;
            }
            totalWaited += waitMillis;
            response = delegate.execute(request, options);
        }
        return response;
    }
}
```

`Sleeper` is a tiny injected seam (`boolean sleep(long millis)` returning `false` when interrupted,
after restoring the interrupt flag) so unit tests do not actually wait.

**Key points:**

- **The 429 body must be closed before retrying.** `SdmxApiClientProviderImpl` sets
  `.doNotCloseAfterDecode()` (line 169), so nothing else will close it -- leaking it would exhaust
  the OkHttp connection pool. Only the discarded 429 responses are closed; the final returned
  `Response` is left open for `InputStreamFeignDecoder`.
- Retrying is safe: every SDMX client method is a `GET` with no request body, so replaying the
  `Request` is idempotent and needs no buffering.
- `resolveWaitMillis` implements the agreed floor rule **with a ceiling**:

  ```
  wait = min( max(exponentialBackoff(attempt), parseRetryAfter(response)), maxIntervalMillis )
  ```

  The clamp is not optional. Without it the `Retry-After` branch bypasses every cap, and a registry
  answering `Retry-After: 3600` would `Thread.sleep` for an hour per attempt on a Tomcat request
  thread. `parseRetryAfter` returns 0 when the header is absent, unparseable, `0`, or negative --
  which is what neutralises Cloudflare's `Retry-After: 0`. Support the delta-seconds form; the
  HTTP-date form may be treated as 0 (state this limitation in the javadoc rather than silently
  ignoring it).
- **The wait budget must fit inside `spring.mvc.async.request-timeout`, accounting for the outer
  multiplier.** `application.yaml` sets it to `600000` (10 min). The proxy answers via
  `StreamingResponseBody`, so once total request time exceeds that, Spring aborts with
  `AsyncRequestTimeoutException` and the in-flight Feign call dies with
  `InterruptedIOException: interrupted`. Because the outer resilience4j `Retry` can restart the inner
  429 cycle, the bound to check is:

  ```
  retry.maxAttempts * (rateLimitRetry.maxTotalWaitMillis + network time)  <  async.request-timeout
  ```

  Observed during implementation: `maxTotalWaitMillis: 135000` with `retry.maxAttempts: 3` blew the
  10-minute ceiling on OECD and failed two data tests. Dropping the OECD budget to 45 s (worst case
  135 s with the multiplier) fixed it. Size this deliberately per registry; it is the binding
  constraint, not the client's own timeout.
- **Enforce a total wait budget per invocation.** Track cumulative sleep and stop retrying once the
  next wait would exceed `maxTotalWaitMillis`. Its default is the **exact** geometric sum implied by
  the other fields (75 000 ms for the Step 1 defaults), not a slack multiple like
  `maxAttempts * maxIntervalMillis` -- a slack default would never bind and would make both this
  bullet and Edge Case 9 misleading. It binds only on the `Retry-After`-inflated path, which is
  precisely its job.
- Restore the interrupt flag on `InterruptedException` and abort the retry loop rather than
  swallowing it (the `Sleeper` seam owns this).

**File (new):** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/registry/api/http/RateLimitRetryClientProvider.java`

A small `@Service` that resolves the per-registry 429 settings (via the existing
`ConfigUtils.getWithFallbackToDefault` pattern) and returns either a wrapped `Client` or, when
`rateLimitRetry.enabled` resolves false, **the delegate unchanged**. Kept **out of**
`Resilience4jComponentFactory`: that class's sole role is minting resilience4j components, and the
wrapper contains no resilience4j at all. This also matches the project preference for injectable
`Provider` services over factories.

Both `RateLimitRetryClient` and this provider go in the new `registry/api/http/` package --
`registry/api/client/` currently holds only the six SDMX Feign API interfaces (all `extends
SdmxApiBase`), and a transport-level `feign.Client` does not belong among them. The unit test path
follows: `sdmx-proxy/src/test/java/com/epam/sdmxproxy/registry/api/http/RateLimitRetryClientTest.java`.

**File:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/registry/api/SdmxApiClientProviderImpl.java`

Wrap the shared client per registry inside `buildClient` (line 150), which already has
`selectedRegistry` in scope. The shared `baseOkHttpClient` bean stays untouched.

```java
        var builder = Resilience4jFeign.builder(decorators)
                .client(rateLimitRetryClientProvider.wrap(baseOkHttpClient, selectedRegistry))
                .options(getOptions(selectedRegistry.getVersionConfiguration()))
```

**Known limitation -- state it in the design, do not silently rely on the opposite.** Built clients
are cached in `builtApiClients` keyed on `(clientClass, baseUrl)` only
(`SdmxApiClientProviderImpl.buildKey`, line 216) with **no eviction anywhere in the codebase**.
Therefore:

- two registries sharing a base URL silently share whichever one's `rateLimitRetry` was built first;
- a `POST /config` hot-reload that changes `rateLimitRetry` has **no effect** once the client exists.

This is pre-existing behaviour for the circuit breaker and retry too, but it must not be described as
"each cached target gets its own settings". Add `registryConfiguration.getName()` to `buildKey` --
a one-line change that fixes the **first** bullet only.

**The hot-reload limitation stays open, and the registry-name fix does not close it.** When
`BaseRegistryTestSuite` re-pushes a mutated config between tests (lines 826-895) it changes only
`supportedFormats` / `defaultFormat`; the registry is still named `OECD` and the endpoint URLs are
unchanged, so the key is identical and the stale client is reused. Document this in
`sdmx-proxy-config/README.md`; proper eviction on `POST /config` is out of scope here.

Verified harmless: `SdmxApiClientProviderImplTest`'s caching tests survive the key change (all
helpers use one registry name, and `testClientCaching_DifferentUrls_CreatesDifferentInstances` still
differs by URL), cache growth stays bounded by clientClass x registry x url, and the
`synchronized (this)` double-check is unaffected.

**Risk -- the two retry families multiply, they are not independent.** The resilience4j `Retry` sits
*above* the `Client`, so each of its attempts re-enters a full 429 cycle. `RateLimitRetryClient.execute`
declares `throws IOException` and does not catch, so an `IOException` on any attempt propagates to
the outer `Retry`, whose predicate matches `IOException`, restarting the inner loop from attempt 1.
With the Step 8 OECD config (`retry.maxAttempts` 3, `rateLimitRetry.maxAttempts` 6) the worst case is
**18 upstream calls and ~405 s of sleeping** plus 3 x `readTimeout` (120 s) on one Tomcat thread --
not the ~75 s a naive reading suggests. The `maxTotalWaitMillis` budget above is what bounds this;
size it deliberately and restate the resulting figure.

### Step 4: Add `Sdmx21QueryNormalizer`

**File (new):** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/translator/Sdmx21QueryNormalizer.java` (interface)
**File (new):** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/translator/Sdmx21QueryNormalizerImpl.java` (`@Service`)

```java
public interface Sdmx21QueryNormalizer {

    /**
     * SDMX 3.0 key -> SDMX 2.1 key. {@code null}, {@code ""}, {@code "*"} and an all-wildcard
     * positional key all become {@code "all"}; a per-position {@code "*"} becomes an empty
     * position ({@code "USA.A.*.*.*"} -> {@code "USA.A..."}). Idempotent.
     */
    String toKey(String key);

    /**
     * SDMX 3.0 availability componentId -> SDMX 2.1. {@code null} and {@code "*"} become
     * {@code "all"}; every other value -- including a comma-joined list -- is passed through
     * unchanged. Idempotent.
     */
    String toComponentId(String componentId);

    /**
     * SDMX 3.0 {@code references} -> SDMX 2.1. {@code "none"} becomes {@code null} so Feign drops
     * the query parameter entirely; every other value is passed through. Idempotent.
     */
    String toReferences(String references);

    /**
     * SDMX 3.0 path slot (agency / resourceID / version) -> SDMX 2.1. {@code "*"} and {@code null}
     * become {@code "all"}; everything else is passed through. Same rule as
     * {@code QueryTranslatorImpl.getVersionSpecificQueryId}, exposed for the 2.1 flowRef. Idempotent.
     *
     * <p>Note this is the OUTBOUND direction. Do not confuse it with
     * {@code QueryTranslator.normalizePathSlot}, which is the inbound direction
     * ({@code "all"} -> {@code "*"}) and would make things worse here.
     */
    String toSdmx21PathSlot(String slot);
}
```

**Naming matters here.** `QueryTranslatorImpl.normalizePathSlot` (line 72) is
`SDMX_21_ALL_WILDCARD.equals(slot) ? SDMX_30_ALL_WILDCARD : slot` -- it maps `all` to `*`, the exact
inverse. P4 cites its absence from the data/availability path as evidence of the gap, but calling it
there would not help; the new method is what is missing. The longer name keeps the two apart.

**`toComponentId` deliberately does NOT collapse comma-joined lists.** The 3.0 spec marks
`componentId` as multiple-valued (`sdmx-rest-2.2.0/doc/availability.md:19`, "Multiple values? Yes"),
so a client asking for `componentId=REF_AREA,FREQ` means two dimensions. Rewriting that to `all`
would silently widen the request and the proxy does not narrow the response back down -- unlike the
`references` substitution, which *is* behaviour-preserving. The only comma-joined componentIds that
must become `all` are the ones the proxy itself generates, and those are fixed at their producers
(Step 7 for `LimitEmulationServiceImpl`; `AdapterRouterImpl.unwrapStarComponentId` is a 3.0-only
knob -- see Edge Case 11).

**Constants:** do **not** try to reuse `KeyParserImpl`'s constants. They are package-private
(`KeyParserImpl.java:13-16`, `static final` with no modifier) in `com.epam.sdmxproxy.services.limit`,
while the normalizer lives in `com.epam.sdmxproxy.services.translator` -- it will not compile. Reuse
`QueryTranslatorImpl.SDMX_30_ALL_WILDCARD` / `SDMX_21_ALL_WILDCARD` instead (already `public static
final`, same package, lines 49-50) and define the `.` / `+` separators locally.

`KeyParser` itself is **not** reusable here either: `parseKey`/`buildKey` require the dimension-id
list, which the adapter-level call site does not have. `toKey` is a pure string transform that needs
no DSD.

Value-level OR must be preserved: `A.B+C.*` -> `A.B+C.` (only whole positions equal to `*` are
emptied).

### Step 5: Apply the normalizer in `QueryTranslatorImpl`

**File:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/translator/QueryTranslatorImpl.java`

Inject `Sdmx21QueryNormalizer` as a new `private final` field (constructor injection via the existing
`@RequiredArgsConstructor`).

In `translateDataQuery` (line ~522), after the existing `replaceEmptyDimensionsWithWildcard` /
`mergeAllWildcardKey` block so the 2.1 form wins:

```java
        if (versionConfig.getSdmxVersion() == SdmxVersion.SDMX_2_1) {
            processedKey = sdmx21QueryNormalizer.toKey(processedKey);
        }
```

In `translateAvailabilityQuery` (line ~357), same placement for the key, plus componentId and
references on the builder (lines 376 and 380):

```java
        boolean is21 = selectedRegistry.getVersionConfiguration().getSdmxVersion() == SdmxVersion.SDMX_2_1;
        ...
                .componentId(is21 ? sdmx21QueryNormalizer.toComponentId(componentId) : componentId)
                .references(is21 ? sdmx21QueryNormalizer.toReferences(references) : (references != null ? references : "none"))
```

### Step 6: Apply the normalizer defensively in the 2.1 adapter methods

**File:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/adapter/GenericRegistryAdapterImpl.java`

Inject `Sdmx21QueryNormalizer` alongside the existing `SdmxApiClientProvider`.

In `getData21` (line 108), replace `getKey(query)`:

```java
        String key = sdmx21QueryNormalizer.toKey(query.getKey());
```

`getKey` (line 132) can then be deleted -- `toKey(null)` already yields `"all"`, which is exactly
what it did.

In `getAvailability21` (line 189):

```java
        String key = sdmx21QueryNormalizer.toKey(query.getKey());
        String componentId = sdmx21QueryNormalizer.toComponentId(query.getComponentId());
        String references = sdmx21QueryNormalizer.toReferences(query.getReferences());
```

and pass those three into `availability21Client.getAvailability(...)`.

**Also normalize the flowRef slots (P4).** `getFlowRef` (line 136) is the single choke point shared
by `getData21` and `getAvailability21`, so fix it there:

```java
    private String getFlowRef(String agencyId, String resourceId, String version) {
        return sdmx21QueryNormalizer.toSdmx21PathSlot(agencyId) + "," + sdmx21QueryNormalizer.toSdmx21PathSlot(resourceId) + "," + sdmx21QueryNormalizer.toSdmx21PathSlot(version);
    }
```

`getFlowRef` is already `private String` (non-static) with exactly two callers -- `getData21` (line
110) and `getAvailability21` (line 191) -- so using the injected field needs no signature change and
is a strict no-op for 3.0, which never reaches it. Without it a spec-conforming 3.0 client
sending the documented default `version=*` produces the 2.1 flowRef `AGENCY,FLOW,*`, while the DSD
lookup for the very same request succeeds -- because `AdapterRouterImpl.getStructureQuery` routes it
through `translateStructureQuery`, which *does* map `*` to `all`.

**Key point:** this is the layer that catches keys rewritten after translation --
`AdapterRouterImpl.normalizeOutboundFilters` sets `query.setKey("*")` (lines 587/594) when
`convertKeyToFilters` is on, and `LimitEmulationServiceImpl` builds queries from scratch.

### Step 7: Fix the limit-emulation 2.1 probes at source

**File:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/limit/LimitEmulationServiceImpl.java`

Inject `Sdmx21QueryNormalizer` as a new `private final` field (constructor injection via the existing
`@RequiredArgsConstructor`; the class currently has six collaborators at lines 56-61). This changes
the constructor arity -- see the test impact table.

- `toAvailabilityQuery21` (line 647): replace the comma-joined
  `String componentId = String.join(",", nonTimeDimensionIds(sdmxBeans));` (line 653) with `"all"`,
  and change `.references("none")` (line 669) to `.references(null)`.

  The `SdmxBeans sdmxBeans` **parameter** (line 649) then becomes entirely unused -- it feeds only
  that componentId join. Remove the parameter and update the single call site at line 555.

  Correct the method javadoc precisely. Its first claim -- that 2.1 does not accept a bare `*` for
  componentId -- is **confirmed** by the P3 table (componentId `*` -> 500) and must stay. Only the
  second half, "componentId must be the comma-joined list of non-time dims", is wrong. Reword it to
  say `all`, and attribute it to observed NSI behaviour rather than to a 2.1 spec (none is available
  in this workspace).

- **Probe and shrink keys still carry 3.0 wildcards.** `probe21` builds its key with
  `keyParser.buildKey(keyState, nonTimeDims, mergeAllWildcardAvail)` (line 554) and `finalize21` does
  the same for the shrunk data key (line 567). `KeyParserImpl.buildKey` emits `positions.add(WILDCARD)`
  -- i.e. `"*"` -- for every unconstrained dimension (line 57), producing keys like `USA.*.*.*.*`.
  Route both call sites through `Sdmx21QueryNormalizer.toKey` **unconditionally** -- `probe21` and
  `finalize21` are reachable only from `runBisect21`, selected at lines 190-192 by
  `version == SdmxVersion.SDMX_2_1 ? runBisect21(...) : runBisect30(...)`, so a version guard here
  would be dead code for the same reason it was dropped from `toAvailabilityQuery30`.

  This matters beyond the wire format: the shrunk key from `finalize21` is what gets serialised into
  the limit-emulation cache entry (`AdapterRouterImpl.java:473`). Leaving it in 3.0 form and relying
  on Step 6 to fix it at the adapter would make the cached value and the wire value differ.

- `toAvailabilityQuery30` (line 611) needs **no change**. It is called only from `probe30` (line 457),
  reached only from `runBisect30`, which `getShrunkQuery` selects only on the non-2.1 branch
  (lines 190-192). A version guard there would be dead code.

### Step 7b: Add an SDMX-ML 2.1 availability response parser

**Discovered during implementation — this design's assumption that Steps 1-7 would unblock limit
emulation was wrong.** Fixing the probe *request* is not enough: the probe *response* could not be
parsed either.

`JsonAvailabilityResponseParser` was the only implementation of `AvailabilityResponseParser`, and it
rejects anything that is not `JSON_STRUCTURE_2_0_0`. A 2.1 registry answers availability with
`XML_STRUCTURE_2_1`, so every probe died with
`AvailabilityProbeException: Unsupported availability return format for JSON parser: XML_STRUCTURE_2_1`.

**File (new):** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/limit/XmlAvailabilityResponseParser.java`

A StAX parser over `ContentConstraint / CubeRegion / KeyValue / Value`. Two behaviours that are not
obvious from the shape of the document and that were derived from OECD's real response:

- **Skip a `KeyValue` with no `Value` children.** The time dimension is reported as a `TimeRange`,
  not a value list. Recording it as an empty dimension drives
  `AvailabilityProjection.combinatorialUpperBound()` to zero -- i.e. "the cube is empty" -- and the
  bisect silently degrades instead of failing.
- **Honour `CubeRegion include="false"`.** An exclusion region lists values that are explicitly
  *un*available. Treating them as available would make the bisect narrow onto values the cube does
  not contain -- silently, with no error, since nothing downstream validates the projection.
  Inclusions and exclusions are accumulated separately and reconciled after the document is read, so
  region order does not matter; a missing `include` attribute defaults to `true`. OECD only ever
  emits `include="true"`, so this is defence against the next 2.1 registry rather than an observed
  OECD behaviour -- stated as such rather than as something measured.
- **Only `series_count` feeds `seriesCount`.** OECD emits an `obs_count` annotation, which counts
  observations rather than series; using it would over-estimate and over-shrink. Falling back to the
  combinatorial bound matches what the JSON parser already does on this registry.

**File (new):** `.../services/limit/DelegatingAvailabilityResponseParser.java`

`@Primary` router that picks the parser whose `supports(SdmxFormat)` matches. The interface already
declared `supports()`, so per-format dispatch was the original intent; there had simply never been a
second implementation. `LimitEmulationServiceImpl` keeps its single `AvailabilityResponseParser`
field, so no call-site or test churn.

### Step 8: Enable availability + limit in the OECD E2E config

**File:** `sdmx-proxy-e2e/src/test/resources/com/epam/sdmxproxy/e2e/tests/registry/oecd/2_1/oecd_2_1_registry_config.json`

Add the `rateLimitRetry` block so the suite survives OECD's cap without any test-side throttle
(explicitly the mechanism chosen over an E2E throttle):

```json
            "retry": {
              "maxAttempts": 3,
              "initialIntervalMillis": 1000,
              "multiplier": 2.0,
              "maxIntervalMillis": 4000
            },
            "rateLimitRetry": {
              "enabled": true,
              "maxAttempts": 6,
              "initialIntervalMillis": 5000,
              "multiplier": 2.0,
              "maxIntervalMillis": 60000,
              "maxTotalWaitMillis": 135000
            }
```

**File:** `sdmx-proxy-e2e/src/test/resources/.../oecd/2_1/oecd_2_1_test_config.json`

Once P3 is fixed, change the data keys from the hand-written 2.1 form to the 3.0 form a real client
sends, so the suite actually pins the translation:

Every key in the file is currently hand-written 2.1 grammar. Enumerated so nothing is guessed:

| Line | Block | From | To |
|------|-------|------|----|
| 63 | `dataTestSuitConfiguration` (AIR_GHG, 5 dims) | `"USA.A...."` | `"USA.A.*.*.*"` |
| 63 | same dataflow | -- | add `"*"` as a second key |
| 70 | `dataTestSuitConfiguration` (LFS INDIC, 9 dims) | `"USA.EMP.......A"` | `"USA.EMP.*.*.*.*.*.*.A"` |
| 90 | `limitTestSuitConfiguration.key` | `"USA.A...."` | `"USA.A.*.*.*"` |
| 105 | `availabilityTestSuitConfiguration` (AIR_GHG) | `"all"` | `"*"` |
| 112 | `availabilityTestSuitConfiguration` (LFS INDIC) | `"all"` | `"*"` |

Line 90 matters specifically: left in 2.1 grammar, the limit path would never exercise
`runBisect21` -> `keyParser.buildKey` -> `getAvailability21` with a 3.0 key.

Also add `"enabled": true` to the `rateLimitRetry` block -- it is opt-in and defaults to false, so
omitting it silently disables the whole of P1 for this suite.

**Known E2E coverage gaps (do not assume the suite pins these):**

- `BaseRegistryTestSuite` hardcodes the availability componentId path segment to `all`
  (line 804: `.../%s/%s/%s/%s/all?mode=%s`), so `toComponentId("*")` is never exercised on the 2.1
  path. Cover it in the manual verification and in unit tests instead.
- The suite always injects a concrete version parsed from the URN (lines 410, 1054), so the P4
  flowRef fix is likewise unpinned by E2E. Cover it with a unit test on `getFlowRef` plus the manual
  `curl` below.

**Expected runtime.** With `rateLimitRetry.maxAttempts: 6` the per-request sleep budget is
5+10+20+40+60 = 135 s (not the 75 s quoted for the Step 1 defaults, which use `maxAttempts: 5`).
Against OECD's measured ~20 req/min a 144-test suite needs on the order of 15 minutes of wall clock
even on a fully green run. Reconcile the two numbers when choosing what ships, and confirm the
Gradle/RestAssured timeouts accommodate it.

## No Changes Required

These files need **no modification**:

- **`AvailabilityQuery30Controller.java` / `AvailabilityQuery30Api.java`** -- the `references=none`
  default is correct for the proxy's 3.0 public contract. The substitution belongs on the 2.1
  outbound path, not in the controller.
- **`RateLimitingInvocationHandlerFactory.java`** -- the fail-fast `rateLimit` knob is a separate,
  outbound-pacing feature. It stays off for OECD and is unrelated to 429 handling.
- **`FeignConfig.java`** -- `baseOkHttpClient` stays a plain shared bean; wrapping happens per
  registry in `SdmxApiClientProviderImpl.buildClient`.
- **`KeyParser` / `KeyParserImpl`** -- used by limit emulation with a known dimension list; the new
  normalizer is a DSD-free string transform. Note its constants are package-private and therefore
  **not** shareable with the translator package (see Step 4); leave the class alone and normalize
  `buildKey`'s *output* at the call sites instead (Step 7).
- **`GlobalExceptionHandler.java`** -- verified: line 105 already does
  `HttpStatus.resolve(ex.status())`, so a terminal 429 reaches the client as 429.
- **`AvailabilityQuery30Api.java` / structure controllers** -- `SdmxStructure30Controller` already
  calls `normalizePathSlot` on all three slots (lines 62-64) and declares `references` as `@Nullable`
  with no default, so the structure 2.1 path does not have P2/P4 (one exception, agency-scheme
  discovery, is listed under Out of Scope).
- **Conversion services** (`StreamingStructureConversionService`, `StreamingDataConversionService`,
  `StreamingAvailabilityConversionService`) -- the 2.1 -> 3.0 response path is already verified
  working end to end.
- **`sdmx-proxy-config/src/main/resources/sdmx_registries_config.json`** -- OECD is not being added
  to the shipped default config by this design; it exists only as an E2E fixture.

## Files Affected

| File | Change Type | Description |
|------|-------------|-------------|
| `sdmx-proxy-config/.../configuration/data/RegistryRateLimitRetryConfig.java` | New | Per-registry 429 retry settings (`enabled`, backoff, `maxTotalWaitMillis`) |
| `sdmx-proxy-config/src/main/java/com/epam/sdmxproxy/configuration/data/RegistryResilienceConfig.java` | Modified | Add `rateLimitRetry` field |
| `sdmx-proxy-config/README.md` | Modified | `rateLimitRetry` row + new `RegistryRateLimitRetryConfig` table (required by CLAUDE.md) |
| `sdmx-proxy/src/main/java/com/epam/sdmxproxy/registry/api/config/ResilienceProperties.java` | Modified | Add `defaultRateLimitRetry` |
| `sdmx-proxy/src/main/java/com/epam/sdmxproxy/registry/api/config/RateLimitRetryProperties.java` | New | App-wide 429 retry defaults |
| `sdmx-proxy/src/main/resources/application.yaml` | Modified | Spell out the `defaultRateLimitRetry` block |
| `sdmx-proxy/src/main/java/com/epam/sdmxproxy/registry/api/Resilience4jComponentFactory.java` | Modified | Split predicates; raise `slowCallDurationThreshold` when 429 retry is enabled |
| `sdmx-proxy/src/main/java/com/epam/sdmxproxy/registry/api/http/RateLimitRetryClient.java` | New | `feign.Client` decorator retrying 429 with clamped backoff + `Retry-After` floor |
| `sdmx-proxy/src/main/java/com/epam/sdmxproxy/registry/api/http/RateLimitRetryClientProvider.java` | New | `@Service` resolving per-registry 429 settings and wrapping the client |
| `sdmx-proxy/src/main/java/com/epam/sdmxproxy/registry/api/SdmxApiClientProviderImpl.java` | Modified | Wrap the client per registry; add registry name to `buildKey` |
| `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/translator/Sdmx21QueryNormalizer.java` | New | Interface: key / componentId / references / path-slot rules |
| `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/translator/Sdmx21QueryNormalizerImpl.java` | New | Implementation |
| `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/translator/QueryTranslatorImpl.java` | Modified | Apply normalizer for 2.1 in data + availability translation |
| `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/adapter/GenericRegistryAdapterImpl.java` | Modified | Defensive normalization in `getData21` / `getAvailability21` and `getFlowRef`; drop `getKey` |
| `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/limit/LimitEmulationServiceImpl.java` | Modified | `componentId` -> `all`, `references` -> null, normalize probe/shrink keys, drop unused `sdmxBeans` param |
| `sdmx-proxy/src/test/java/com/epam/sdmxproxy/registry/api/SdmxApiClientProviderImplTest.java` | Modified | Stub the new client wrapper (otherwise ~20 tests NPE) |
| `sdmx-proxy/src/test/java/com/epam/sdmxproxy/services/adapter/GenericRegistryAdapterImplTest.java` | Modified | Constructor arity; add the missing 2.1 coverage |
| `sdmx-proxy/src/test/java/com/epam/sdmxproxy/services/translator/QueryTranslatorImplTest.java` | Modified | Constructor arity; add 2.1 translation cases |
| `sdmx-proxy/src/test/java/com/epam/sdmxproxy/services/limit/LimitEmulationServiceImplTest.java` | Modified | Constructor arity; two 2.1 assertions change to `"all"` |
| `sdmx-proxy-e2e/src/test/resources/.../oecd/2_1/oecd_2_1_registry_config.json` | Modified | Add `rateLimitRetry` |
| `sdmx-proxy-e2e/src/test/resources/.../oecd/2_1/oecd_2_1_test_config.json` | Modified | Switch data, availability and limit keys to 3.0 wildcard form |

## Edge Cases

1. **Normalizer runs twice** (translator then adapter) -- all four rules are idempotent:
   `toKey("all")` = `"all"`, `toKey("USA.A...")` is unchanged, `toComponentId("all")` = `"all"`,
   `toReferences(null)` = `null`, `toSdmx21PathSlot("all")` = `"all"`. `toSdmx21PathSlot` is applied
   only once (adapter only), so idempotence is not load-bearing for it, but it holds anyway.
2. **`replaceEmptyDimensionsWithWildcard` set on a 2.1 registry** -- it expands `..` to `*.*`, the
   normalizer then collapses back to `..`. Net no-op. These knobs are 3.0-registry workarounds and
   should not be set for 2.1; ordering makes a misconfiguration harmless rather than fatal.
3. **`convertKeyToFilters` set on a 2.1 registry** -- `AdapterRouterImpl` rewrites the key to `*` and
   moves narrowings into `c[]`, which SDMX 2.1 has no concept of. Step 6 converts the key back to
   `all`, so the request succeeds but returns the **unnarrowed** cube. This knob must not be enabled
   for 2.1 registries; consider logging a warning at config load. Not fixed by this design.
4. **All-wildcard positional key** (`*.*.*.*.*`) -> `all` rather than `....`. Both are accepted by
   OECD; `all` is chosen because it is the canonical 2.1 spelling and is what `mergeAllWildcardKey`
   was reaching for.
5. **Single-component availability query** -- `componentId=REF_AREA` must pass through untouched
   (verified 200). Only `*` and `null` map to `all`.
6. **429 on the very last attempt** -- the decorator returns the 429 `Response`; Feign turns it into
   a `FeignException` with status 429, which the circuit-breaker predicate ignores. Verified that it
   already surfaces correctly: `GlobalExceptionHandler.java:105` does
   `HttpStatus status = HttpStatus.resolve(ex.status())`, so the client sees 429. No change needed.
7. **`Retry-After` in HTTP-date form** -- treated as 0 (falls back to exponential backoff). Document
   the limitation.
8. **Interrupt during backoff sleep** -- restore the interrupt flag and abort retrying.
9. **Registry that legitimately returns 429 forever** -- bounded by `maxTotalWaitMillis` per
   invocation and `maxAttempts` per cycle. Note the outer resilience4j `Retry` can restart the cycle
   on `IOException`, so the true bound is `retry.maxAttempts x maxTotalWaitMillis`, not a single
   geometric series -- see the multiplication risk in Step 3.
10. **Client-supplied multi-component availability query** -- `componentId=REF_AREA,FREQ` is
    spec-legal 3.0 (`availability.md:19`, multiple values Yes) and OECD's NSI rejects it with 500.
    The normalizer passes it through unchanged rather than silently widening it to `all`, so the
    client gets an honest upstream error instead of a wrong-but-200 answer. Narrowing a multi-component
    request into per-component 2.1 calls and merging the results is out of scope.
11. **`unwrapStarComponentId` set on a 2.1 registry** -- `AdapterRouterImpl` (lines 520-522, 563-578)
    replaces a `*` componentId with a comma-joined dimension list *after* translation, which NSI
    rejects. Two reasons it is latent, not live: no 2.1 registry sets the flag today, and after Step 5
    rewrites `*` to `all` the guard `"*".equals(getComponentId()) || == null` (line 576) never fires
    on the 2.1 path anyway. Like `convertKeyToFilters`, this is a 3.0-only knob; consider a
    config-load warning. Not fixed by this design.

## Verification

### Unit Tests

**File:** `sdmx-proxy/src/test/java/com/epam/sdmxproxy/services/translator/Sdmx21QueryNormalizerImplTest.java` (new)

| Test Method | Description |
|-------------|-------------|
| `shouldMapStarKeyToAll()` | `"*"` -> `"all"` |
| `shouldMapNullAndEmptyKeyToAll()` | `null` / `""` -> `"all"` |
| `shouldEmptyPerPositionWildcards()` | `"USA.A.*.*.*"` -> `"USA.A..."` |
| `shouldCollapseAllWildcardPositionalKeyToAll()` | `"*.*.*"` -> `"all"` |
| `shouldPreserveOrValues()` | `"A.B+C.*"` -> `"A.B+C."` |
| `shouldLeave21KeyUnchanged()` | `"USA.A..."` unchanged (idempotence) |
| `shouldMapStarComponentIdToAll()` | `"*"` and `null` -> `"all"` |
| `shouldLeaveSingleComponentIdUnchanged()` | `"REF_AREA"` unchanged |
| `shouldLeaveMultiComponentIdUnchanged()` | `"REF_AREA,FREQ"` unchanged (no silent widening) |
| `shouldMapNoneReferencesToNull()` | `"none"` -> `null`; `"all"` unchanged; `null` -> `null` |
| `shouldMapStarPathSlotToAll()` | `toSdmx21PathSlot("*")` / `null` -> `"all"`; `"1.0"` unchanged |

**File:** `sdmx-proxy/src/test/java/com/epam/sdmxproxy/registry/api/http/RateLimitRetryClientTest.java` (new)

| Test Method | Description |
|-------------|-------------|
| `shouldRetryOn429AndReturnFirstNon429Response()` | Stub client returns 429, 429, 200 -> 200 returned, delegate called 3x |
| `shouldStopAfterMaxAttempts()` | Always 429 -> returns 429, delegate called exactly `maxAttempts` times |
| `shouldNotRetryNon429()` | 500 passes through untouched, delegate called once |
| `shouldCloseDiscarded429Responses()` | Discarded responses closed; final response left open |
| `shouldIgnoreZeroRetryAfterAndUseBackoff()` | `Retry-After: 0` -> exponential backoff used |
| `shouldHonourLargerRetryAfter()` | `Retry-After: 30` with a 5 s backoff -> 30 s wait |
| `shouldClampRetryAfterToMaxInterval()` | `Retry-After: 3600` -> capped at `maxIntervalMillis` |
| `shouldStopWhenTotalWaitBudgetExhausted()` | Cumulative wait beyond `maxTotalWaitMillis` ends the loop |
| `shouldPassThroughWhenDisabled()` | `enabled: false` -> provider returns the delegate, no wrapping |

The `Sleeper` seam makes these run instantly.

**File:** `sdmx-proxy/src/test/java/com/epam/sdmxproxy/registry/api/Resilience4jComponentFactoryTest.java` (exists -- extend)

| Test Method | Description |
|-------------|-------------|
| `circuitBreakerShouldNotRecord429()` | Predicate returns false for a 429 `FeignException` |
| `circuitBreakerShouldRecord5xxAndIoException()` | Unchanged behaviour preserved |
| `circuitBreakerShouldNotOpenOnSlowRateLimitedCalls()` | `slowCallDurationThreshold` exceeds the 429 wait budget |

**New 2.1 adapter tests are the real gap.** `GenericRegistryAdapterImplTest` currently contains
**zero** 2.1 tests -- all seven mock `Sdmx30AvailabilityClient` and set
`versionConfig.setSdmxVersion(SdmxVersion.SDMX_3_0)`. Without new tests, nothing verifies that
`getData21` / `getAvailability21` hand normalized values to `Sdmx21DataClient` /
`Sdmx21AvailabilityClient`, and the entire defensive-placement rationale of Step 6 ships unverified.
Add tests mirroring the existing `ArgumentCaptor` pattern (lines 133-138) against the 2.1 clients,
covering key, componentId, references and the `getFlowRef` slots.

**Existing suites: what actually breaks.** The earlier claim that these assert the current
pass-through was wrong -- verified:

| File | Real impact |
|------|-------------|
| `.../services/adapter/GenericRegistryAdapterImplTest.java` | Only constructor arity at line 40 (`new GenericRegistryAdapterImpl(clientProvider)`). No assertion breaks -- the suite has no 2.1 coverage at all. |
| `.../services/translator/QueryTranslatorImplTest.java` | Only constructor arity at line 77. Every key/wildcard assertion (lines 1342-1561) uses `SDMX_3_0`, so the version-gated change cannot reach them; the one 2.1 key assertion, `assertEquals("all", query.getKey())` (line 559), still passes because `toKey("all") == "all"`. |
| `.../registry/api/SdmxApiClientProviderImplTest.java` | **Breaks hard.** Line 60 does `mock(Resilience4jComponentFactory.class)`; once `buildClient` routes `.client(...)` through a collaborator, an unstubbed mock returns `null` and feign's `SynchronousMethodHandler$Factory` calls `Util.checkNotNull(client, "client")` -- every `testGet*Client_*`, `testClientCaching_*`, `testResilienceIntegration_*` and `testOperationName_*` case fails with NPE. Stub `RateLimitRetryClientProvider.wrap` to return the delegate. The caching tests themselves survive the `buildKey` change. |
| `.../services/limit/LimitEmulationServiceImplTest.java` | **Breaks three ways.** (a) Constructor arity: line 61 passes 6 collaborators; Step 7 adds the normalizer. (b) Line 318 `assertThat(lastProbeQuery.get().getComponentId()).isEqualTo("FREQ,REF_AREA")` in `getShrunkQuery_sdmx21_passesConcreteComponentIdAndNoFiltersToAvailability` -- expected value becomes `"all"`, and the method name should be renamed since it encodes the removed behaviour. (c) Line 366 `assertThat(shrunk.getKey()).isEqualTo("*")` in `getShrunkQuery_sdmx21_appliesMergeAllWildcardKey_whenAllWildcard` (built from `baseQuery21`) -- becomes `"all"`. Verified unaffected: line 145 (same assertion but `baseQuery30`) and line 303 (both positions concrete, so `toKey` is a no-op). |

`sdmx-proxy/src/test/java/com/epam/sdmxproxy/services/limit/KeyParserImplTest.java` is a useful
reference for the key-grammar cases to mirror in the normalizer tests.

### Manual Verification

```bash
SDMXPROXY_TEST_CONFIG_ENDPOINT_ENABLED=true SERVER_PORT=8060 ./gradlew :sdmx-proxy:bootRun
curl -X POST -H "Content-Type: application/json" \
  --data @sdmx-proxy-e2e/src/test/resources/com/epam/sdmxproxy/e2e/tests/registry/oecd/2_1/oecd_2_1_registry_config.json \
  http://localhost:8060/statgpt/sdmx-proxy/api/v0/config

B=http://localhost:8060/statgpt/sdmx-proxy/api/v0/sdmx/3.0

# P3: 3.0 wildcard key must now work (was 404)
curl -s -o /dev/null -w "%{http_code}\n" -H "Accept: application/vnd.sdmx.data+json;version=2.0.0" \
  "$B/data/dataflow/OECD.ENV.EPI/DSD_AIR_GHG@DF_AIR_GHG/1.0/USA.A.*.*.*"

# P3: whole-key wildcard (was 422) -- large response, expect 200
curl -s -o /dev/null -w "%{http_code}\n" -H "Accept: application/vnd.sdmx.data+json;version=2.0.0" \
  "$B/data/dataflow/OECD.ENV.EPI/DSD_AIR_GHG@DF_AIR_GHG/1.0/*"

# P2 + componentId `*` (E2E hardcodes `all`, so this is the only coverage): was 500
curl -s -o /dev/null -w "%{http_code}\n" -H "Accept: application/vnd.sdmx.structure+json;version=2.0.0" \
  "$B/availability/dataflow/OECD.ENV.EPI/DSD_AIR_GHG@DF_AIR_GHG/1.0/*/*?mode=exact"

# P4: wildcard version slot -- the spec default (E2E always sends a concrete version)
curl -s -o /dev/null -w "%{http_code}\n" -H "Accept: application/vnd.sdmx.data+json;version=2.0.0" \
  "$B/data/dataflow/OECD.ENV.EPI/DSD_AIR_GHG@DF_AIR_GHG/*/USA.A.*.*.*"
```

Expected: 200 for all four. Confirm in the proxy log that the outbound 2.1 URLs carry
`USA.A...` / `all`, componentId `all`, flowRef `OECD.ENV.EPI,DSD_AIR_GHG@DF_AIR_GHG,all`, and
**no** `references` parameter.

For P1, fire 25+ requests in a burst and confirm the proxy logs
`Registry returned 429 ...; retry n/m in X ms` and still answers 200, with no
`CircuitBreaker ... is OPEN` for the 429s.

**Also verify the content of `componentId=all`, not just its status.** Step 7 swaps the limit probe's
comma-joined componentId for `all` on the strength of a 200; nothing yet proves the `all` response
carries the same per-dimension value lists. If it does not, limit emulation degrades **silently**:
`doProbe` only logs `Availability parser returned EMPTY projection` and returns
(`LimitEmulationServiceImpl.java:596-604`), after which `runBisect21` finds no bisectable dims and
falls through to `finalize21`. Diff the two bodies directly against the registry:

```bash
A=https://sdmx.oecd.org/public/rest/availableconstraint/OECD.ENV.EPI,DSD_AIR_GHG@DF_AIR_GHG,1.0/all/all
curl -s -H "Accept: application/vnd.sdmx.structure+xml;version=2.1" "$A/all?mode=exact" \
  | grep -o '<common:KeyValue id="[^"]*"'
```

Expected: one `KeyValue` per non-time dimension (`REF_AREA`, `FREQ`, `POLLUTANT`, `MEASURE`,
`UNIT_MEASURE`). Consider asserting a non-empty projection in the 2.1 limit diagnostic so a silent
degradation fails loudly.

### E2E Tests

```bash
./gradlew :sdmx-proxy-e2e:e2eTest --tests "*.OECD_2_1_RegistryTestSuit" -Dtest.ignoreFailures=true
```

Baseline before this change: 144 tests, 24 passed, 102 failed, 18 skipped -- 96 failures caused by
unhandled 429. Target after: no failures attributable to 429, availability cases green, and both
limit diagnostics either passing or skipping for a stated reason rather than failing with HTTP 500.

Expect the run to take substantially longer than 55 s, because retries now absorb the rate limit
instead of failing fast. This is the intended trade-off.

Re-run `:sdmx-proxy:test` and `./gradlew build -x :sdmx-proxy-e2e:e2eTest` (green before this change).

## Out of Scope

- **3.0 `hierarchy` vs 2.1 `hierarchicalcodelist` structure-type mapping.** `hierarchy` is omitted
  from the OECD config's `supportedStructures`. Deferred by explicit decision.
- **Any request throttle in the E2E framework.** Proxy-side retries are the chosen mechanism.
- **Adding OECD to the shipped `sdmx_registries_config.json`.**
- **`convertKeyToFilters` / `unwrapStarComponentId` guards for 2.1 registries** (edge cases 3 and 11)
  -- documented, not fixed.
- **Agency-scheme discovery leaks a raw `*` agency slot to 2.1.**
  `QueryTranslatorImpl.translateStructureQueryForAgencySchemaDiscovery` (line 214) normalizes the
  resourceId and version slots but passes the agency literally as `"*"`, unlike the fan-out path
  (line 255) which normalizes all three. OECD sets `allowSubAgencies: true`, so sub-agency discovery
  will issue `GET /dataflow/*/all/all?references=children&detail=full` against NSI. It degrades
  quietly -- `AgencySchemeService` catches the exception, logs a warning and falls back to the
  configured agencies -- so it is not a blocker, but it does contradict a strict reading of "three
  choke points cover everything". Fix separately.

## SDMX Standard References

- Availability `references` parameter, allowed values and `none` default:
  `sdmx-rest-2.2.0/doc/availability.md:22`
- Availability `componentId` -- multiple values allowed, default `*`:
  `sdmx-rest-2.2.0/doc/availability.md:19`
- Availability `version` / `key` -- default `*`, multiple values allowed:
  `sdmx-rest-2.2.0/doc/availability.md:17-18`
- Availability request template: `sdmx-rest-2.2.0/doc/availability.md:10`
- Data key grammar (positional, `+` for OR): `sdmx-rest-2.2.0/doc/data.md`

**Caveat:** `sdmx-rest-2.2.0/` is the SDMX **3.0** REST specification. No SDMX-REST 2.1 document
exists in this workspace, so every claim in this design about what a 2.1 endpoint accepts
(`all` as the all-wildcard key, empty positions as per-dimension wildcards, `all` or a single id for
`componentID`, rejection of comma-joined componentIds) is **empirically established against OECD NSI
Web Service v8.19.8.0** and is stated as observed behaviour, not as spec compliance. Per CLAUDE.md,
do not restate these as documented-standard requirements in code comments.

## Review Log

### Iteration 1 — 2026-07-31

Two critics run in parallel (correctness/feasibility emphasis; completeness/spec/tests emphasis),
findings deduped. **Every finding below was independently re-verified by the main agent against the
real code before being accepted** — all cited line numbers in the original design also checked out.

**Critic findings (verified):**

1. **[High] Step 2 / `Resilience4jComponentFactory.java:53-59`** — Excluding 429 from
   `recordException` does not stop 429 from opening the breaker, because slow-call detection is on by
   default and the Step 3 sleep happens inside the breaker's timed span.
   - Evidence: `javap -constants resilience4j-circuitbreaker-2.3.0.jar` →
     `DEFAULT_SLOW_CALL_RATE_THRESHOLD = 100`, `DEFAULT_SLOW_CALL_DURATION_THRESHOLD = 60` (seconds).
     `CircuitBreakerConfig.custom()` at line 53 sets neither. A 75-135 s retry budget exceeds 60 s, so
     a 429 storm makes 100 % of calls "slow" and opens the breaker.
   - Resolution: **Accepted** — Step 2 now mandates `.slowCallDurationThreshold(...)` above the 429
     budget, with a unit test (`circuitBreakerShouldNotOpenOnSlowRateLimitedCalls`). This defeated the
     design's central invariant and was the most valuable finding of the round.

2. **[High] Step 3 / Edge case 9** — `max(backoff, Retry-After)` applies `maxIntervalMillis` only to
   the exponential term, so a hostile or buggy `Retry-After: 3600` blocks a Tomcat thread for an hour
   per attempt; the "bounded by the geometric series, ~75 s" claim was false.
   - Evidence: design-internal inconsistency; nothing in the stated algorithm clamps the header. The
     proxy streams on the request thread (`StreamingResponseBody`), so each sleep holds a container thread.
   - Resolution: **Accepted** — rule is now `min(max(backoff, retryAfter), maxIntervalMillis)` plus a
     new `maxTotalWaitMillis` per-invocation budget; edge case 9 rewritten.

3. **[High] Solution A** — "keeps the two families exactly independent" is wrong: the outer
   resilience4j `Retry` restarts the inner 429 cycle on `IOException`, so attempts multiply.
   - Evidence: `RateLimitRetryClient.execute` declares `throws IOException` and does not catch;
     the outer retry predicate matches `IOException` (`Resilience4jComponentFactory.java:161-163`).
     With the Step 8 OECD config that is 18 upstream calls and ~405 s of sleeping, not ~75 s.
   - Resolution: **Accepted** — claim narrowed to "attempt caps separate", multiplication quantified
     in Step 3, bounded by `maxTotalWaitMillis`.

4. **[High] New problem P4 — `version` / `resourceID` path slots never translated** — a
   spec-conforming 3.0 client sending the documented default `version=*` produces the 2.1 flowRef
   `AGENCY,FLOW,*`.
   - Evidence: `QueryTranslatorImpl:541` and `:374` set `.version(version)` verbatim;
     `normalizePathSlot` is called only from `SdmxStructure30Controller:62-64` and the structure
     translators. `GenericRegistryAdapterImpl.getFlowRef:136` concatenates raw slots. Spec:
     `availability.md:17` — `version` default `*`. Internally inconsistent, since the DSD lookup for
     the same request goes through `translateStructureQuery`, which *does* map `*` → `all`.
   - Resolution: **Accepted** — added as problem P4; `toPathSlot` added to the normalizer and applied
     in `getFlowRef` (Step 6). This was a genuine scope gap, not a nitpick.

5. **[High] Verification / `SdmxApiClientProviderImplTest.java:60`** — routing `.client(...)` through
   a collaborator breaks ~20 existing tests with NPE; the suite was not listed in the design.
   - Evidence: line 60 `mock(Resilience4jComponentFactory.class)`; an unstubbed mock returns `null`
     and feign's `SynchronousMethodHandler$Factory` calls `Util.checkNotNull(client, "client")`.
   - Resolution: **Accepted** — added to Files Affected and to the "what actually breaks" table.

6. **[High] Step 4 / `KeyParserImpl.java:13-16`** — "reuse the constants" will not compile; they are
   package-private in `services.limit`, the normalizer lives in `services.translator`.
   - Evidence: `static final String POSITION_SEPARATOR = ".";` etc., no access modifier.
   - Resolution: **Accepted** — reuse `QueryTranslatorImpl.SDMX_30_ALL_WILDCARD` /
     `SDMX_21_ALL_WILDCARD` (public, same package) instead; `No Changes Required` updated.

7. **[Medium] Step 4 / `availability.md:19`** — collapsing *any* comma-joined componentId to `all`
   silently widens a legitimate client request.
   - Evidence: spec marks `componentId` "Multiple values? Yes", default `*` (verified by extracting
     the table column). The design justified the `references` substitution as behaviour-preserving but
     offered no such argument for componentId.
   - Resolution: **Accepted** — `toComponentId` now maps only `*` / `null` to `all` and passes lists
     through; proxy-generated comma lists are fixed at their producers instead. New edge case 10.

8. **[Medium] Step 7 / `LimitEmulationServiceImpl.java:554,567`** — Step 7 did not make 2.1 probes
   "correct on their own": the probe and shrink keys still carry `*`.
   - Evidence: both call `keyParser.buildKey(...)`, which emits `WILDCARD` per unconstrained dim
     (`KeyParserImpl.java:57`) → `USA.*.*.*.*`, the form OECD 404s. The shrunk key is also what is
     cached (`AdapterRouterImpl:473`), so cache and wire form would diverge.
   - Resolution: **Accepted** — Step 7 now routes both call sites through `toKey`.

9. **[Medium] Step 7 / `LimitEmulationServiceImpl.java:190-192,457`** — the `toAvailabilityQuery30`
   version guard is dead code.
   - Evidence: `getShrunkQuery` dispatches `version == SDMX_2_1 ? runBisect21 : runBisect30`;
     `toAvailabilityQuery30` is reachable only from `runBisect30`.
   - Resolution: **Accepted** — bullet deleted, replaced with an explicit "needs no change" note.

10. **[Medium] Step 3 / `SdmxApiClientProviderImpl.java:216`** — "each cached target gets its own
    settings" is false; the cache is keyed on `(class, baseUrl)` with no eviction.
    - Evidence: `buildKey(Class<?> c, String baseUrl)`; `builtApiClients` has only get/put in the
      whole codebase. So registries sharing a base URL share settings, and `POST /config` hot-reload
      has no effect once a client exists — relevant because `BaseRegistryTestSuite:878-888` re-pushes
      mutated configs between tests.
    - Resolution: **Accepted** — limitation documented and adding the registry name to `buildKey`
      recommended.

11. **[Medium] Verification** — the claim that `QueryTranslatorImplTest` and
    `GenericRegistryAdapterImplTest` "assert the current pass-through" was wrong.
    - Evidence: `GenericRegistryAdapterImplTest` contains **zero** occurrences of `SDMX_2_1` (all
      seven tests are 3.0); in `QueryTranslatorImplTest` every key assertion uses `SDMX_3_0`, and the
      one 2.1 assertion `assertEquals("all", query.getKey())` (line 559) still passes. Only
      constructor arity breaks in both.
    - Resolution: **Accepted** — claim corrected; the real gap (no 2.1 adapter coverage at all) is now
      called out as required new work.

12. **[Medium] Step 8 vs Step 3** — backoff arithmetic inconsistent (75 s for `maxAttempts: 5` vs
    135 s for the Step 8 `maxAttempts: 6`), and `limitTestSuitConfiguration.key` was left in 2.1 form
    so the limit path would not pin the translation.
    - Evidence: `oecd_2_1_test_config.json:90` is `"USA.A...."`; Step 8 only changed the data and
      availability blocks.
    - Resolution: **Accepted** — limit key switched to `"USA.A.*.*.*"`, both figures reconciled,
      expected suite duration (~15 min) stated.

13. **[Medium] Edge cases / `AdapterRouterImpl.java:520-522,563-578`** — `unwrapStarComponentId`
    would be silently neutralised on 2.1 and was undocumented.
    - Evidence: it sets a comma-joined list post-translation; also its guard
      `"*".equals(componentId) || == null` (line 576) never fires once Step 5 rewrites `*` → `all`.
      Latent only — no 2.1 registry sets the flag.
    - Resolution: **Accepted** — new edge case 11, mirroring the `convertKeyToFilters` entry.

14. **[Low] Out of Scope / `QueryTranslatorImpl.java:214`** — agency-scheme discovery passes agency
    `"*"` unnormalized, unlike the fan-out path (line 255).
    - Evidence: confirmed at line 214; OECD sets `allowSubAgencies: true`. Degrades quietly —
      `AgencySchemeService` catches and falls back.
    - Resolution: **Deferred** — documented under Out of Scope with evidence; outside the agreed
      three-item scope and non-blocking.

15. **[Low] SDMX references / CLAUDE.md** — the design asserted "Per SDMX-REST 2.1 the `componentID`
    segment is one component id or `all`" with no citable source.
    - Evidence: only `sdmx-rest-2.2.0/` (the **3.0** spec) exists in the workspace; no 2.1 REST doc.
      CLAUDE.md explicitly forbids fabricating documented-pattern claims.
    - Resolution: **Accepted** — all 2.1 grammar claims reworded as empirically established against
      OECD NSI 8.19, with an explicit caveat block in SDMX Standard References.

16. **[Low] Assorted** — `application.yaml` is camelCase, not kebab-case (lines 44-67); the
    `sdmxBeans` **parameter** of `toAvailabilityQuery21` (line 649) becomes fully unused, not just a
    local; `RateLimitRetryClient` placement in `registry/api/client/` sits oddly beside the six
    `SdmxApiBase` interfaces and a factory-shaped wrapper cuts against the project's
    `Provider`-over-`Factory` preference; the `e2e-report.md` pointer is a gitignored build artifact.
    - Resolution: **Accepted** — yaml key corrected; parameter removal specified with its call site;
      wrapper moved to a new `registry/api/http/` package behind a `RateLimitRetryClientProvider`
      `@Service`; report pointer annotated.

**Closed as already-satisfied (verified, no design change needed):** edge case 6 asked to "confirm
`GlobalExceptionHandler` maps 429 sensibly" — `GlobalExceptionHandler.java:105` already does
`HttpStatus.resolve(ex.status())`. Both critics independently confirmed the final (non-discarded) 429
response cannot leak, since feign's `ResponseHandler` closes the body on the exception path
regardless of `doNotCloseAfterDecode`; the requirement to close *discarded* 429s inside the loop
stands. Both also confirmed no regression risk to BIS/IMF: `replaceEmptyDimensionsWithWildcard`,
`mergeAllWildcardKey`, `convertKeyToFilters` and `unwrapStarComponentId` are set only on `SDMX_3_0`
blocks, and every change here is version-gated. Cache safety was verified independently: data and
availability responses are not cached, and `CacheKeyGenerator.generateLimitEmulationKey` is computed
from the post-translator key.

**Discarded (unverified):** none — every finding raised by either critic held up against the code.

### Iteration 2 — 2026-07-31

Two fresh critics, told what iteration 1 changed and instructed not to re-raise resolved findings.
One targeted the newly added material, one targeted internal coherence after the heavy editing.
Findings deduped; all re-verified by the main agent.

**Critic findings (verified):**

1. **[High] Verification / `LimitEmulationServiceImplTest.java:61,318,366`** — a **fourth** test suite
   breaks, and it was absent from the iteration-1 breakage table (which had presented itself as the
   verified, corrected list).
   - Evidence: line 61 constructs the service with 6 collaborators (Step 7 adds a 7th); line 318
     asserts `getComponentId()).isEqualTo("FREQ,REF_AREA")`, which Step 7 replaces with `"all"`;
     line 366 asserts `shrunk.getKey()).isEqualTo("*")` in a `baseQuery21` test, which becomes
     `"all"`. Both critics found this independently. I confirmed the discrimination they drew:
     line 145 carries the identical assertion but uses `baseQuery30`, and line 303 is unaffected
     because both key positions are concrete.
   - Resolution: **Accepted** — added to Files Affected and to the breakage table with the exact
     assertions, expected new values, and the unaffected lines called out.

2. **[High] Step 2 sizing** — `slowCallDurationThreshold > 429 wait budget` is short by roughly an
   order of magnitude, because the breaker times the sleeps **plus** the network time of every attempt.
   - Evidence: OECD sets `readTimeout: 120000`; `application.yaml:48` sets `defaultReadTimeout: 240000`
     (not the 30000 in the Java default). With `maxAttempts: 6` the span is ~135 s sleep + 6 x 120 s
     network. Also confirmed via disassembly that a non-recorded exception still reaches
     `onSuccess(duration)` and is slow-call accounted, and that `slowCallRateThreshold` is already at
     its maximum of 100 so duration is the only lever.
   - Resolution: **Accepted** — rule restated as
     `maxTotalWaitMillis + maxAttempts * readTimeout`, with the resolved-timeout caveat and a
     two-direction unit test.

3. **[High] Steps 2 and 3 are not version-gated, contradicting the iteration-1 no-regression
   conclusion** — both are installed in `buildClient`, which runs for every registry, so BIS and IMF
   would silently acquire a multi-minute retry loop and lose slow-call detection.
   - Evidence: `getOrCreateCircuitBreaker` and `rateLimitRetryClientProvider.wrap` are both called
     unconditionally from `SdmxApiClientProviderImpl.buildClient` (lines 153-163); there is no
     per-registry slow-call knob in `RegistryCircuitBreakerConfig`. The iteration-1 Review Log
     sentence "every change here is version-gated" is true of Steps 4-7 and false of Steps 2-3.
   - Resolution: **Accepted, with a design change** — introduced an opt-in `rateLimitRetry.enabled`
     flag defaulting to **false**. Both the wrapper and the relaxed threshold are now gated on it, so
     3.0 registries keep today's behaviour exactly. This also forced `rateLimitRetry` onto a dedicated
     `RegistryRateLimitRetryConfig` class rather than reusing `RegistryRetryConfig`, which resolves
     finding 4 as a side effect. The Solution section now states the gating for both families
     explicitly, and Step 8 adds `"enabled": true` to the OECD config.

4. **[Medium] Step 1 self-contradiction on `maxTotalWaitMillis`** — "the four fields are exactly what
   is needed" followed by "add a fifth"; owning class unspecified; absent from the Step 3 snippet and
   the Step 8 JSON; and its proposed default `maxAttempts * maxIntervalMillis` never binds (300-360 s
   against a real worst case of 75-135 s).
   - Evidence: `RegistryRetryConfig` has exactly four fields and `getOrCreateRetry` reads only those,
     so a fifth there would be settable-but-ignored on `retry`.
   - Resolution: **Accepted** — dedicated config class (see 3), exact defaults tabulated, default
     pinned to the **exact** geometric sum so it actually binds, added to the client snippet and to
     the OECD JSON.

5. **[Medium] `buildKey` rationale does not survive scrutiny** — adding the registry name does not fix
   the hot-reload case it was justified by.
   - Evidence: `BaseRegistryTestSuite` (lines 826-895) mutates only `supportedFormats` /
     `defaultFormat` and re-posts; the name stays `OECD` and the URLs are unchanged, so the key is
     identical and the stale client is still returned.
   - Resolution: **Accepted** — change kept for the shared-base-URL case, rationale corrected, and the
     hot-reload limitation explicitly recorded as **still open**.

6. **[Medium] Solution C stale after the iteration-1 edit** — claimed "four rules" and "all three
   rules" in adjacent sentences, and claimed dual placement that Step 5 does not implement for the
   path-slot rule.
   - Evidence: Step 5 applies only `toKey` / `toComponentId` / `toReferences`; `toSdmx21PathSlot` is
     applied only in Step 6's `getFlowRef`.
   - Resolution: **Accepted** — Solution C rewritten to state the split placement and why the
     translator deliberately keeps the untranslated version; Edge Case 1 and the Files Affected
     description updated.

7. **[Medium] Step 7 omitted the constructor injection** it depends on, and reintroduced the dead
   version guard that iteration-1 finding 9 removed.
   - Evidence: `LimitEmulationServiceImpl` is `@RequiredArgsConstructor` with six `private final`
     collaborators; `probe21`/`finalize21` are reachable only from `runBisect21`.
   - Resolution: **Accepted** — injection instruction added, guard dropped, `toKey` called
     unconditionally.

8. **[Medium] `componentId=all` rests on a status code, not on content** — nothing proves the `all`
   response carries the same per-dimension value lists as the comma-joined form, and a mismatch
   degrades limit emulation silently rather than loudly.
   - Evidence: `doProbe` (lines 596-604) only logs `Availability parser returned EMPTY projection`
     and returns; `BaseRegistryTestSuite:804` hardcodes the componentId segment to `all`, so E2E
     cannot catch it either.
   - Resolution: **Accepted** — added a concrete manual `KeyValue`-diff check and a recommendation to
     assert a non-empty projection in the 2.1 limit diagnostic.

9. **[Medium/Low] Package and naming inconsistencies introduced by the iteration-1 edit** — the
   design named `registry/api/client/` in the Step 3 header and the test path but
   `registry/api/http/` in the prose and Files Affected; and `toPathSlot` sits one word away from the
   existing `QueryTranslator.normalizePathSlot`, which is its exact inverse
   (`"all"` -> `"*"`, `QueryTranslatorImpl.java:72`).
   - Resolution: **Accepted** — `registry/api/http/` everywhere including the test; method renamed to
     `toSdmx21PathSlot` with a javadoc note and a clarifying paragraph in Step 4.

10. **[Low] Step 8 key edits under-enumerated** — the second data key `"USA.EMP.......A"` (9 positions,
    line 70) was never mentioned, "add `*` as a second key on one dataflow" did not say which, and the
    availability block has two `"all"` keys.
    - Evidence: keys confirmed at lines 63, 70, 90, 105, 112.
    - Resolution: **Accepted** — replaced with a line-by-line table covering all six edits.

11. **[Low] `application.yaml` missing from the plan** — every other resilience default is spelled out
    there (lines 44-67) even though the Java classes carry defaults.
    - Resolution: **Accepted** — added to Step 1 and to Files Affected.

**Discarded (unverified):** none. Both critics also re-verified large parts of the design and found
them sound — notably that `getFlowRef` has exactly two callers and is a strict no-op for 3.0, that
`toSdmx21PathSlot` on the agency slot cannot break routing (routing happens in
`selectRegistryAndVersion`, long before `getFlowRef`), that dropping the `SdmxBeans` parameter from
`toAvailabilityQuery21` compiles because line 556 still uses it for `doProbe`, that `KeyParserImpl.parseKey`
treats empty positions and `*` identically so the new 2.1-form key survives the limit-emulation
round-trip, that `DimensionServiceImpl` ignores the version argument so P4's `version=*` case cannot
break DSD resolution, and that the P1-P5 numbering and every sampled line citation are consistent.

### Loop ended after iteration 2 — stopped by the user, not by convergence

Iteration 3 was cancelled before its critics ran. The loop therefore exited on user instruction, **not**
on the convergence condition: iteration 2 still produced three verified High findings, and the fixes
applied for them have not themselves been adversarially reviewed.

Carry into implementation as the highest-risk, least-reviewed areas:

- The **opt-in `rateLimitRetry.enabled` flag** and the new `RegistryRateLimitRetryConfig` class were
  introduced late, in response to iteration-2 finding 3, and no critic has seen them. In particular
  the gating of `slowCallDurationThreshold` inside `Resilience4jComponentFactory.getOrCreateCircuitBreaker`
  needs checking against that method's real signature and available data while implementing.
- The `slowCallDurationThreshold` sizing formula (`maxTotalWaitMillis + maxAttempts * readTimeout`)
  is derived, not measured. Verify it empirically during the P1 burst test.
- The `componentId=all` content equivalence (iteration-2 finding 8) is still an open empirical
  question, with a manual check specified but not yet run.
