# Design 020: Unified exception hierarchy and ad-hoc throw migration

**Status:** implemented (2026-05-19).

## Context

The SDMX Proxy currently throws a mix of JDK runtime exceptions (`IllegalArgumentException`,
`IllegalStateException`, `RuntimeException`, `UnsupportedOperationException`) and a handful
of custom exceptions in `com.epam.sdmxproxy.exception`. The custom exceptions sit on three
different parents (`RuntimeException`, `IllegalArgumentException`, ad-hoc) and the
`GlobalExceptionHandler` has a dedicated `@ExceptionHandler` for each one. Ad-hoc throws
either fall through to the catch-all `Exception.class` handler (returning HTTP 500 with a
sanitised "An unexpected error occurred." message) or are caught generically by
`IllegalArgumentException.class` (returning HTTP 400 regardless of whether the cause was a
client mistake or an internal bug).

The result has two concrete problems:

1. **Wrong HTTP status for many failures.** Configuration errors raised at request time --
   for example a missing `dataEndpointConfig` for the routed registry -- bubble up as
   plain `IllegalArgumentException` and are reported to the client as 400 Bad Request. The
   client cannot fix this; it is an operator misconfiguration and should be 500.
2. **Sensitive internal detail leaks.** The handler echoes `ex.getMessage()` verbatim for
   most known exceptions, but it also does so for raw `IllegalArgumentException`. A
   message like `"Failed to convert structures"` followed by the stack trace's root cause
   is fine in logs but not in an API response. Today the only message that is
   deliberately scrubbed is the catch-all 500 handler.

The handler is also growing: nine `@ExceptionHandler` methods today, all with near-identical
bodies (`log.warn(...) ; buildErrorResponse(ex.getMessage(), HttpStatus.X)`). Each new
custom exception means another method. The cost is low per addition, but the file is
already noisy and the pattern invites copy-paste mistakes (inconsistent logging severity,
inconsistent stack-trace inclusion -- both already present in the current code).

This design proposes a single hierarchy under one base class, a single status-aware
handler, and a migration plan for the ad-hoc throws scattered across the codebase. It
covers only exceptions originating in **our** code; `FeignException`,
`HttpMediaTypeNotAcceptableException`, and `NoResourceFoundException` are external and
keep their dedicated handlers.

## Non-goals

- **Replacing `FeignException` handling.** Upstream registry errors keep their dedicated
  handler -- they need bespoke logic (`extractUpstreamErrorMessage`) and the status is
  pass-through, not fixed.
- **Replacing Spring's built-in exceptions.** `HttpMediaTypeNotAcceptableException`,
  `NoResourceFoundException`, and any future Spring exceptions remain handled by
  Spring-aware methods.
- **Adding error codes / problem details.** This refactor keeps the existing
  `ErrorResponse` shape (`message`, `status`, `traceparent`). Switching to RFC 7807
  `application/problem+json` is a separate change.
- **i18n of error messages.** Messages stay in English.
- **Wrapping JDK exceptions thrown by libraries we depend on.** sdmx-core, Jackson, Feign,
  etc. continue to throw their own types; only **our** code's throws are migrated.

## Current state

### Custom exceptions in `com.epam.sdmxproxy.exception`

| Class                                    | Parent today                | Handler maps to        | Where thrown                                                  |
|------------------------------------------|-----------------------------|------------------------|---------------------------------------------------------------|
| `AgencyRoutingException`                 | `RuntimeException`          | 400 Bad Request        | `AgencyRoutingServiceImpl` (4 sites)                          |
| `FilterValidationException`              | `RuntimeException`          | 400 Bad Request        | `QueryTranslatorImpl` (2 sites; one appends a hint in handler) |
| `IllegalRegistryConfigurationException`  | `RuntimeException`          | 400 Bad Request        | `Resilience4jComponentFactory` (3), `SdmxApiClientProviderImpl` (4) |
| `RateLimitExceededException`             | `RuntimeException`          | 429 Too Many Requests  | `RateLimitingInvocationHandlerFactory`                        |
| `RegistryUnavailableException`           | `RuntimeException`          | 503 Service Unavailable | Defined, handled, but never thrown directly. Likely intended for the circuit-breaker `CallNotPermittedException` path -- needs verification / wiring. |
| `UnsupportedAgencyWildcardException`     | `RuntimeException`          | 501 Not Implemented    | `QueryTranslatorImpl`                                         |
| `UnsupportedContextException`            | `IllegalArgumentException`  | 400 Bad Request        | `DataQuery30Controller`, `AvailabilityQuery30Controller` (3 sites total) |
| `UnsupportedMediaTypeParameterException` | `IllegalArgumentException`  | (catch-all IAE) 400    | `SdmxMediaType.validateCsvParameters`                         |
| `UnsupportedSdmxVersionException`        | `IllegalArgumentException`  | (catch-all IAE) 400    | `SdmxMediaType.determineSdmxVersionFromAcceptHeader` (2 sites) |

Observations:

- `IllegalRegistryConfigurationException` maps to 400, but every throw site of it
  represents an **operator** error (a missing endpoint config, a null selection). 500
  Internal Server Error is the right status. The current 400 is misleading: the client
  has no lever to fix it.
- `UnsupportedMediaTypeParameterException` and `UnsupportedSdmxVersionException` extend
  `IllegalArgumentException` and ride the catch-all IAE handler -- the dedicated handler
  doesn't exist. That works today but couples them to the IAE blanket handler.
- `AgencyRoutingException` returning 400 is borderline. The client did pass an unknown
  agency, so 400 is defensible; 404 ("no resource for that agency") is also defensible.
  Keeping 400 for backward compatibility is fine.
- `UnsupportedAgencyWildcardException` returns 501 because the proxy *could* implement
  it but currently doesn't. This is the correct semantic. `StructureWildcardE2ETest`
  asserts `statusCode == 501` -- this status is contractual.

### `GlobalExceptionHandler` today

Nine `@ExceptionHandler` methods, each with the same shape:

```java
@ExceptionHandler(SomeException.class)
public ResponseEntity<ErrorResponse> handleX(SomeException ex) {
    log.warn("...: {}", ex.getMessage());           // or log.warn(..., ex);  or log.error(..., ex);
    return buildErrorResponse(ex.getMessage(), HttpStatus.SOMETHING);
}
```

Inconsistencies in the current handler:

- Logging severity: 4xx use `log.warn`, the catch-all 5xx uses `log.error` -- mostly
  consistent. But `NoResourceFoundException` (404) and `HttpMediaTypeNotAcceptableException`
  (400) both log at `error` level, which over-reports legitimate client mistakes.
- Stack-trace inclusion: some `warn` calls pass `ex` (full stack), others pass only
  `ex.getMessage()`. No documented rule.
- Message leakage: `ex.getMessage()` is echoed verbatim except in the catch-all 500
  handler, where it's replaced by `"An unexpected error occurred. Please try again later."`.
  The custom-exception handlers trust the throw sites to produce client-safe messages,
  which is mostly true today but is not enforced.

### Ad-hoc throws in our code

Grep was run across `sdmx-proxy/src/main/java` for the standard JDK runtime exceptions.
NullPointerException: zero hits. The rest are grouped below by what the failure
semantically represents.

#### A. Client input error (target: 400)

| Site (file:line)                                                  | Today                          | Semantically                                              |
|-------------------------------------------------------------------|--------------------------------|-----------------------------------------------------------|
| `SdmxMediaType.java:131`                                          | `UnsupportedMediaTypeParameterException` (IAE) | CSV params on non-CSV media type                          |
| `SdmxMediaType.java:217, 227`                                     | `UnsupportedSdmxVersionException` (IAE)        | Unsupported SDMX version in Accept                        |

These already have dedicated custom exceptions. No new throws needed -- they migrate via
the parent-class rewrite (see Migration).

#### B. Server-side configuration error (target: 500)

These represent missing or invalid registry configuration discovered at request time.
The client cannot fix them.

| Site (file:line)                                                                      | Today                                       | Notes                                                  |
|---------------------------------------------------------------------------------------|---------------------------------------------|--------------------------------------------------------|
| `QueryTranslatorImpl.java:492, 539, 595`                                              | `IllegalArgumentException`                  | "endpoint configuration is missing for registry X"     |
| `QueryTranslatorImpl.java:516, 571, 619`                                              | `IllegalArgumentException`                  | "no default format is configured"                      |
| `QueryTranslatorImpl.java:145`                                                        | `IllegalArgumentException`                  | "No suitable SDMX version found for registry"          |
| `SdmxApiClientProviderImpl.java:95, 102, 113, 124` and `Resilience4jComponentFactory.java:36, 39, 67` | `IllegalRegistryConfigurationException`     | Same family; already custom but maps to 400 (wrong)    |
| `LimitEmulationServiceImpl.java:65`                                                   | `IllegalStateException`                     | "availabilityEndpointConfig is required for supportsLimit=false registries" |
| `GenericRegistryAdapterImpl.java:56, 103, 184`                                        | `IllegalStateException`                     | `switch` default for `SdmxVersion` enum                |
| `SeriesLimitTruncatorProvider.java:28, 41`                                            | `IllegalStateException`                     | Truncator registration / lookup -- bean-wiring concern |
| `RepresentationMapper.java:33`                                                        | `IllegalArgumentException`                  | Unknown representation type (parsed structure)         |
| `ProxyRedisCredentialsProviderFactory.java:26`                                        | `IllegalArgumentException`                  | Missing required env var for Redis auth                |

The QueryTranslator/SdmxApiClientProvider/Resilience4jComponentFactory cases all describe
the same condition (config missing for the routed registry+version). They should all map
to one exception that yields 500. Today they are split between `IllegalArgumentException`
(returns 400) and `IllegalRegistryConfigurationException` (returns 400). Both end up at
400, and both should be 500.

`SeriesLimitTruncatorProvider` cases technically can't fire at request time (they're
constructor-time wiring checks), but the second one (`forFormat` lookup miss) does fire
on request. Both deserve to surface as 500.

#### C. Server-side bug / runtime invariant violated (target: 500)

| Site (file:line)                              | Today                          | Notes                                                |
|-----------------------------------------------|--------------------------------|------------------------------------------------------|
| `CustomSdmxSuperBeanRetrievalManagerImpl.java:60, 63, 66` | `IllegalArgumentException` | Ref null / missing fields -- sdmx-core integration   |
| `AdapterRouterImpl.java:174, 271, 401`        | `IllegalArgumentException`     | "Failed to convert structures/data/availability"     |
| `AdapterRouterImpl.java:220`                  | `RuntimeException`             | "Failed to read structures"                          |
| `StreamingDataConversionService.java:181`     | `IllegalArgumentException`     | `switch` default ("Cannot use this format for data") |
| `CacheKeyGenerator.java:180`                  | `RuntimeException`             | "MD5 hashing not available" -- impossible in practice |
| `RateLimitingInvocationHandlerFactory.java:75` | `RuntimeException`             | Rethrow of arbitrary `Throwable` from delegate       |

The "Failed to convert X" sites in `AdapterRouterImpl` are particularly important: they
catch a generic `Exception` from the conversion pipeline and rethrow as IAE, which
becomes a 400 today. Conversion failure is almost always a server-side bug (or an upstream
registry returning malformed data after fixtures have run). 500 is correct.

#### D. Format / version not implemented (target: 501)

| Site (file:line)                                                | Today                          | Notes                                                |
|-----------------------------------------------------------------|--------------------------------|------------------------------------------------------|
| `StreamingStructureConversionService.java:76, 101, 109, 138`    | `UnsupportedOperationException` | XML 3.0 not supported, JSON 2.1 not supported, etc. |
| `StreamingStructureConversionService.java:121, 140`             | `RuntimeException`             | "Unsupported SDMX version for JSON conversion"       |
| `StreamingDataConversionService.java:90, 253`                   | `UnsupportedOperationException` | Conversion to unsupported target media type          |
| `StreamingAvailabilityConversionService.java:72, 77, 112, 116, 134` | `UnsupportedOperationException` | Availability XML / 2.1 not yet supported         |

These represent "the client asked for X, the proxy doesn't implement X yet". 501 Not
Implemented is the right status (matches the existing precedent of
`UnsupportedAgencyWildcardException` -> 501).

Note: today these all fall through to the catch-all `Exception` handler and return 500
with the generic "An unexpected error occurred." message. The refactor is an
improvement on the wire, not just a refactor.

#### E. Upstream / infrastructure failure (target: 503)

| Site (file:line)                                          | Today              | Notes                              |
|-----------------------------------------------------------|--------------------|------------------------------------|
| `RedisCacheService.java:44, 58, 75, 89, 106, 120`         | `RuntimeException` | Redis op failed                    |
| `AzureRedisCredentialsProvider.java:55, 63, 69`           | `IllegalArgumentException` | JWT parse failure (token issuer)   |
| `AwsRedisCredentialsProvider.java:86`                     | `IllegalArgumentException` | AWS IAM token generation failure   |
| `GcpRedisCredentialsProvider.java:64`                     | `IllegalStateException` | GCP IAM client creation failure |
| `LimitEmulationServiceImpl.java:605`                      | `IllegalStateException` | Availability probe failed       |
| `JsonAvailabilityResponseParser.java:50, 69`              | IAE / ISE         | Availability response shape unexpected |
| `StreamingFixtureIO.java:55`                              | `IllegalStateException` | Piped stream setup failed       |
| `AgencySchemeService.java:131`                            | `IllegalStateException` | Jackson serialization failure   |
| `ConfigServerConfigExtractor.java:69, 75`                 | `IllegalStateException` | Config-source unreachable / unreadable |
| `ClasspathResourceConfigExtractorProxy.java:27`           | `IllegalStateException` | Classpath config missing        |
| `FileSystemConfigExtractorProxy.java:36`                  | `IllegalStateException` | Config file missing on disk     |

These split into two sub-groups:

1. **Boot-time config extraction** (`ConfigServerConfigExtractor`, `ClasspathResourceConfigExtractorProxy`,
   `FileSystemConfigExtractorProxy`). These fire during Spring startup, before the
   `GlobalExceptionHandler` is engaged, so the new hierarchy is mostly cosmetic for
   them -- the app will simply fail to start. They are still grouped here for
   completeness; rewriting them to a typed `ConfigurationLoadException` is
   recommended for symmetry but not behavior-changing.
2. **Runtime infrastructure failures** (Redis, IAM credentials, availability probe,
   piped streams, fixture IO). These should surface as 503 Service Unavailable when
   they reach the handler, not 500. The current path (catch-all 500) under-reports
   transient infrastructure problems.

## Goals

1. **One base class for everything our code throws.** Catching it should be enough to
   render an `ErrorResponse` with the right HTTP status.
2. **Status family is determined at throw time**, not at handler time. The thrower
   knows whether this is a 400, 500, 501, or 503 condition; the handler shouldn't have
   to guess.
3. **Single handler method for the whole hierarchy.** Plus the existing dedicated
   handlers for `FeignException` and Spring's built-ins.
4. **Stack-trace + severity policy is enforced by the handler**, not by every
   throw site / catch site.
5. **Client-safe messages.** The thrower decides what's safe to echo; 5xx defaults to
   a generic message unless the throw site opts in to detail.
6. **Cleanly migrate the ad-hoc throws** so no `IllegalArgumentException` /
   `IllegalStateException` / raw `RuntimeException` originates in our package after
   migration (excluding clearly-internal preconditions in private helpers where they
   indicate programming bugs).
7. **Preserve the causal chain on every wrap-and-rethrow.** Whenever we catch one
   exception and throw another, the caught exception MUST be passed as `cause`.
   Logs surface the full caused-by chain via Log4j2; the client at minimum gets a
   `traceparent` to correlate. Dropping the cause -- even for "expected" wraps -- is
   a defect, because it deletes the only signal an operator has about what
   actually broke. The hierarchy's contract enforces this at the constructor level
   (see "Cause preservation" below).

## Options considered

### Option A: Single base class with a `HttpStatus` field

```java
public abstract class BaseException extends RuntimeException {
    private final HttpStatus status;
    public BaseException(HttpStatus status, String message) { ... }
    public BaseException(HttpStatus status, String message, Throwable cause) { ... }
    public HttpStatus getStatus() { return status; }
}
```

Concrete exceptions pass the status to `super(...)`:

```java
public class AgencyRoutingException extends BaseException {
    public AgencyRoutingException(String message) { super(HttpStatus.BAD_REQUEST, message); }
}
```

Handler:

```java
@ExceptionHandler(BaseException.class)
public ResponseEntity<ErrorResponse> handle(BaseException ex) { ... }
```

**Pros**

- Minimal class count -- nine concrete classes today, no intermediate layer.
- One handler method covers everything.
- Adding a new exception is one line (the constructor passes a status).
- `@ApiResponse` documentation can be attached to each concrete class via a
  marker annotation if needed.

**Cons**

- Status is data, not type. The Springdoc / OpenAPI surface for individual concrete
  exceptions is less obvious -- the handler advertises a single response code unless
  we attach `@ApiResponse(...)` arrays per controller method.
- Loses some intent: "this is a not-found, this is a bad-request" is not visible in
  the class hierarchy, only in the constructor argument.
- Easier to mis-throw with the wrong status (no compile-time guard).

### Option B: Status-family intermediate classes

```java
public abstract class BaseException extends RuntimeException { ... }

public abstract class BadRequestException extends BaseException { ... }      // 400
public abstract class ServiceUnavailableException extends BaseException { ... } // 503
public abstract class NotImplementedException extends BaseException { ... } // 501
public abstract class TooManyRequestsException extends BaseException { ... } // 429
public abstract class ServerErrorException extends BaseException { ... }    // 500
```

Concrete exceptions pick a parent:

```java
public class AgencyRoutingException extends BadRequestException { ... }
public class IllegalRegistryConfigurationException extends ServerErrorException { ... }
```

Handler has one method per family (5 today, ~6 with NotFound if we add it):

```java
@ExceptionHandler(BadRequestException.class)        // -> 400
@ExceptionHandler(ServerErrorException.class)        // -> 500
@ExceptionHandler(ServiceUnavailableException.class) // -> 503
...
```

**Pros**

- Status is encoded in the type. Compile-time guarantee that a `BadRequestException`
  yields 400.
- Springdoc / OpenAPI can advertise responses per family with `@ApiResponse` on the
  family-handler method -- closer to the current Swagger surface.
- Easy to add documentation conventions per family (default logging severity,
  default message scrubbing).
- Refactors well: rename a concrete exception's parent to move its HTTP status.

**Cons**

- Five extra abstract classes for a small hierarchy.
- Handler has more methods (one per family), though still significantly fewer than
  today's nine.
- Two-level hierarchy can feel like ceremony for the smaller families (`TooManyRequests`
  has exactly one concrete child today).

### Option C: Hybrid -- base + sealed enum of categories

```java
public abstract class BaseException extends RuntimeException {
    private final Category category;
    public enum Category { BAD_REQUEST, NOT_FOUND, NOT_IMPLEMENTED, TOO_MANY_REQUESTS,
                           SERVER_ERROR, SERVICE_UNAVAILABLE; ... }
    public HttpStatus toHttpStatus() { return category.toHttpStatus(); }
}
```

**Pros**

- Closed set of statuses (sealed enum).
- One handler method, status from enum lookup.

**Cons**

- Same downside as Option A: status is data, not type, so the class hierarchy doesn't
  encode the response shape.
- More moving parts than Option A with little practical benefit.

### Recommendation

**Option B (status-family intermediates).** Reasoning:

1. The migration table is dominated by 500-target sites (most ad-hoc throws are
   server-side bugs or misconfig). Having a `ServerErrorException` parent
   means every site that catches `Exception e` and wants to rethrow as a server error
   has an obvious target, and it's impossible to accidentally tag it as a 400.
2. The handler stays small: 5-6 methods total, one per family, instead of 9
   per-class methods. The number does not grow when new concrete exceptions are added.
3. Each family can carry its own default logging severity and message policy via the
   family handler -- e.g. 4xx families log `warn` without stack, 5xx families log
   `error` with stack. The per-class handlers today implement this inconsistently;
   centralising it at the family level enforces the rule.
4. `@ApiResponse` on the controller methods remains expressive: we can keep listing
   the concrete exception families per endpoint in OpenAPI without re-listing the
   numeric status alongside.
5. Type-level intent ("this is a not-found") is preserved, which is the main weakness
   of Option A.

The cost (five abstract classes) is one-time and small. Option A is acceptable if the
team prefers fewer classes, but the design proceeds with Option B.

## Recommendation -- proposed hierarchy

```
BaseException                        // abstract, extends RuntimeException
+- BadRequestException           // abstract; handler -> 400
|  +- AgencyRoutingException
|  +- FilterValidationException
|  +- UnsupportedContextException
|  +- UnsupportedMediaTypeParameterException
|  +- UnsupportedSdmxVersionException
|  +- (new) MalformedRedisCredentialsException     // see Migration: AzureRedisCredentialsProvider
+- NotImplementedException       // abstract; handler -> 501
|  +- UnsupportedAgencyWildcardException
|  +- (new) UnsupportedConversionException         // covers "JSON 2.1 not supported", "XML 3.0 not supported", etc.
+- TooManyRequestsException      // abstract; handler -> 429
|  +- RateLimitExceededException
+- ServerErrorException          // abstract; handler -> 500
|  +- IllegalRegistryConfigurationException        // moves from BadRequest semantics to ServerError
|  +- (new) RegistryConfigurationMissingException  // alternative name; see Open Questions Q1
|  +- (new) StructureConversionException           // replaces "Failed to convert structures" IAE
|  +- (new) DataConversionException                // replaces "Failed to convert data" IAE
|  +- (new) AvailabilityConversionException        // replaces "Failed to convert availability" IAE
|  +- (new) UnexpectedStateException               // replaces ad-hoc IllegalStateException in switch defaults / invariants
+- ServiceUnavailableException   // abstract; handler -> 503
   +- RegistryUnavailableException        // already exists, status moves cleanly under this parent
   +- (new) CacheUnavailableException                 // replaces Redis RuntimeException wrappers
   +- (new) CredentialProvisioningException          // replaces credential-provider IAE / ISE
   +- (new) AvailabilityProbeException                // replaces LimitEmulationServiceImpl ISE on probe IOException
   +- (new) ConfigurationLoadException                // replaces extractor ISE (boot-time, mostly cosmetic)
```

Notes on the new classes:

- `UnsupportedConversionException` collapses the 8+ `UnsupportedOperationException`
  sites in the streaming converters into one type. Message stays specific; only the
  type changes.
- `UnexpectedStateException` is the bucket for `switch` defaults on exhaustive enums
  and other invariants that should be unreachable. We keep one type rather than
  inventing a new exception per site.
- `CacheUnavailableException` is intentionally named for "the cache backend is the
  problem", not "the cache content was wrong". Redis op failures surface as 503; we
  rely on cache-miss-as-success at call sites (cache outage degrades, doesn't fail
  requests) so this exception is only reached when a caller treats a cache write as
  fatal -- worth reviewing whether any do (see Open Questions Q3).

### Renames

| Existing                                   | Proposed                                | Reason                                                  |
|--------------------------------------------|-----------------------------------------|---------------------------------------------------------|
| `IllegalRegistryConfigurationException`    | (keep)                                  | Common, well-known                                      |
| `UnsupportedAgencyWildcardException`       | (keep)                                  | Single specific use case, contractual status            |
| `UnsupportedMediaTypeParameterException`   | (keep)                                  | Verbose but accurate                                    |

No renames are required. The class names are reasonable already; the migration is
primarily about parent class and ad-hoc throws.

## Migration plan

### Migration table -- existing custom exceptions

| Class                                    | Today extends             | New parent                            | Status today -> new |
|------------------------------------------|---------------------------|---------------------------------------|---------------------|
| `AgencyRoutingException`                 | `RuntimeException`        | `BadRequestException`        | 400 -> 400          |
| `FilterValidationException`              | `RuntimeException`        | `BadRequestException`        | 400 -> 400          |
| `IllegalRegistryConfigurationException`  | `RuntimeException`        | `ServerErrorException`       | 400 -> 500 (fix)    |
| `RateLimitExceededException`             | `RuntimeException`        | `TooManyRequestsException`   | 429 -> 429          |
| `RegistryUnavailableException`           | `RuntimeException`        | `ServiceUnavailableException` | 503 -> 503         |
| `UnsupportedAgencyWildcardException`     | `RuntimeException`        | `NotImplementedException`    | 501 -> 501          |
| `UnsupportedContextException`            | `IllegalArgumentException` | `BadRequestException`        | 400 -> 400          |
| `UnsupportedMediaTypeParameterException` | `IllegalArgumentException` | `BadRequestException`        | 400 (via IAE) -> 400 |
| `UnsupportedSdmxVersionException`        | `IllegalArgumentException` | `BadRequestException`        | 400 (via IAE) -> 400 |

The status change for `IllegalRegistryConfigurationException` is the only behavioural
shift. Open question Q1 below asks whether that change is acceptable now or whether
it should ship as a separate, advertised change.

### Migration table -- ad-hoc throws

Grouped by file/area as the brief requires.

| Area                                              | Today                          | New target                            | Status |
|---------------------------------------------------|--------------------------------|---------------------------------------|--------|
| `QueryTranslatorImpl` -- "config missing" sites   | `IllegalArgumentException` x7  | `IllegalRegistryConfigurationException` | 500   |
| `QueryTranslatorImpl` -- "no SDMX version found"  | `IllegalArgumentException`     | `IllegalRegistryConfigurationException` | 500   |
| `LimitEmulationServiceImpl:65`                    | `IllegalStateException`        | `IllegalRegistryConfigurationException` | 500   |
| `GenericRegistryAdapterImpl` -- switch defaults   | `IllegalStateException` x3     | `UnexpectedStateException`              | 500   |
| `SeriesLimitTruncatorProvider`                    | `IllegalStateException` x2     | `UnexpectedStateException`              | 500   |
| `CustomSdmxSuperBeanRetrievalManagerImpl`         | `IllegalArgumentException` x3  | `UnexpectedStateException` (server-side; client never sees ref directly) | 500 |
| `RepresentationMapper:33`                         | `IllegalArgumentException`     | `UnexpectedStateException`              | 500   |
| `AdapterRouterImpl` -- conversion catch blocks    | `IllegalArgumentException` x3  | `StructureConversionException` / `DataConversionException` / `AvailabilityConversionException` | 500 |
| `AdapterRouterImpl:220` -- read structures IOException | `RuntimeException`         | `StructureConversionException` (or new `StructureFetchException`)        | 500   |
| `StreamingStructureConversionService`             | `UnsupportedOperationException` x4, `RuntimeException` x2 | `UnsupportedConversionException` | 501 |
| `StreamingDataConversionService:90, 253`          | `UnsupportedOperationException` x2 | `UnsupportedConversionException`    | 501   |
| `StreamingDataConversionService:181`              | `IllegalArgumentException`     | `UnsupportedConversionException`        | 501   |
| `StreamingAvailabilityConversionService`          | `UnsupportedOperationException` x5 | `UnsupportedConversionException`    | 501   |
| `RedisCacheService` -- 6 sites                    | `RuntimeException`             | `CacheUnavailableException`             | 503   |
| `AzureRedisCredentialsProvider` -- 3 sites        | `IllegalArgumentException`     | `CredentialProvisioningException`       | 503   |
| `AwsRedisCredentialsProvider:86`                  | `IllegalArgumentException`     | `CredentialProvisioningException`       | 503   |
| `GcpRedisCredentialsProvider:64`                  | `IllegalStateException`        | `CredentialProvisioningException`       | 503   |
| `ProxyRedisCredentialsProviderFactory:26`         | `IllegalArgumentException`     | `IllegalRegistryConfigurationException` (boot-time misconfig) | 500 |
| `LimitEmulationServiceImpl:605`                   | `IllegalStateException`        | `AvailabilityProbeException`            | 503   |
| `JsonAvailabilityResponseParser:50, 69`           | IAE / ISE                      | `AvailabilityProbeException`            | 503   |
| `StreamingFixtureIO:55`                           | `IllegalStateException`        | `UnexpectedStateException`              | 500   |
| `AgencySchemeService:131`                         | `IllegalStateException`        | `UnexpectedStateException`              | 500   |
| `ConfigServerConfigExtractor`, `ClasspathResourceConfigExtractorProxy`, `FileSystemConfigExtractorProxy` | `IllegalStateException` x4 | `ConfigurationLoadException` | 503 (boot-time, app fails to start) |
| `RateLimitingInvocationHandlerFactory:75`         | `RuntimeException`             | `UnexpectedStateException`              | 500 (the `Throwable e` is rethrown wrapping the original cause -- the unwrap path should be reviewed; see Open Questions Q4) |
| `CacheKeyGenerator:180`                           | `RuntimeException`             | `UnexpectedStateException`              | 500   |

### Proposed `GlobalExceptionHandler` after refactor

The handler shrinks to 5 family methods + 3 external handlers (Feign, Spring 404,
Spring media-type) + 1 catch-all.

```java
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final ObjectMapper objectMapper;

    // ---------- our hierarchy: 4xx families log warn, no stack ----------

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(BadRequestException ex) {
        log.warn("Bad request: {}", ex.getMessage());
        return buildErrorResponse(ex.getMessage(), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(NotImplementedException.class)
    public ResponseEntity<ErrorResponse> handleNotImplemented(NotImplementedException ex) {
        log.warn("Not implemented: {}", ex.getMessage());
        return buildErrorResponse(ex.getMessage(), HttpStatus.NOT_IMPLEMENTED);
    }

    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<ErrorResponse> handleTooManyRequests(TooManyRequestsException ex) {
        log.warn("Too many requests: {}", ex.getMessage());
        return buildErrorResponse(ex.getMessage(), HttpStatus.TOO_MANY_REQUESTS);
    }

    // ---------- our hierarchy: 5xx families log error WITH stack, generic message ----------

    @ExceptionHandler(ServerErrorException.class)
    public ResponseEntity<ErrorResponse> handleServerError(ServerErrorException ex) {
        log.error("Server error", ex);
        return buildErrorResponse(clientMessage(ex), HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler(ServiceUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleServiceUnavailable(ServiceUnavailableException ex) {
        log.error("Service unavailable", ex);
        return buildErrorResponse(clientMessage(ex), HttpStatus.SERVICE_UNAVAILABLE);
    }

    // ---------- external exceptions: keep dedicated handlers ----------

    @ExceptionHandler(FeignException.class)
    public ResponseEntity<ErrorResponse> handleFeignException(FeignException ex) { /* unchanged */ }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(NoResourceFoundException ex) {
        log.warn("Resource not found: {}", ex.getResourcePath());
        return buildErrorResponse(ex.getMessage(), HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ErrorResponse> handleMediaTypeNotAcceptable(HttpMediaTypeNotAcceptableException ex) {
        log.warn("Media type not acceptable: {}", ex.getMessage());
        return buildErrorResponse(ex.getMessage(), HttpStatus.NOT_ACCEPTABLE); // see Open Questions Q2 (today this is 400)
    }

    // ---------- catch-all: anything unmapped is a bug ----------

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex) {
        log.error("Unhandled exception", ex);
        return buildErrorResponse("An unexpected error occurred. Please try again later.", HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
```

`clientMessage(ex)` is the helper that implements the message policy below.

### Logging policy

Rule, enforced at the family handler:

| Family                | Log level | Stack trace?                                   |
|-----------------------|-----------|------------------------------------------------|
| `BadRequest`          | warn      | No (message only)                              |
| `NotImplemented`      | warn      | No (message only)                              |
| `TooManyRequests`     | warn      | No (message only)                              |
| `ServerError`         | error     | Yes (`log.error("...", ex)`)                   |
| `ServiceUnavailable`  | error     | Yes                                            |
| `FeignException`      | warn      | No -- upstream is responsible for its own logs |
| Spring 404 / 406      | warn      | No                                             |
| catch-all `Exception` | error     | Yes                                            |

This formalises the current handler's behaviour and corrects the existing inconsistencies
(`NoResourceFoundException` logged at `error`, raw `IllegalArgumentException` logged
with stack at `warn`, etc.).

### Message conventions

Two-tier policy on the base class:

```java
public abstract class BaseException extends RuntimeException {
    protected BaseException(String detailMessage) { super(detailMessage); }
    protected BaseException(String detailMessage, Throwable cause) {
        super(detailMessage, cause);
    }
    public String getClientMessage() { return getMessage(); }   // override to scrub
}
```

`getClientMessage()` defaults to `getMessage()` so existing call sites work
unchanged. Concrete exceptions that wrap an internal detail override
`getClientMessage()` to return a generic safe message; `getMessage()` keeps the
detail for logs.

### Cause preservation (contract)

The hierarchy enforces that the caused-by chain is never silently dropped.

**Contract for every concrete exception in `com.epam.sdmxproxy.exception`:**

1. MUST expose a `(String message, Throwable cause)` constructor, even when no
   current throw site has a cause. Adding it now means a future maintainer who
   needs to wrap a caught exception has the right constructor available and
   doesn't shortcut to `new IllegalArgumentException(...)` or to dropping the
   cause entirely.
2. SHOULD also expose a `(String message)` constructor (cause `null`) for
   "originated here, no upstream" cases like switch defaults or config-missing
   checks. These are the only cases where a null cause is acceptable.
3. MUST NOT define a `(Throwable cause)`-only constructor. Always require a
   message -- a bare wrap with no message loses semantic information about
   *what* the wrapper was trying to do.

**Contract for every wrap site (`catch ... throw new ...`):**

1. The caught exception MUST be passed as `cause`. Audit (below) shows current
   coverage is already high but not universal.
2. The message of the new exception describes *what we were doing*, not *what
   went wrong*. The "what went wrong" comes from the cause's message and class.
   Example: `"Failed to convert structures from JSON_DATA_2_0_0 to application/xml"`
   is correct; the cause carries the JsonParseException / ClassCastException /
   whatever-actually-broke detail.
3. The message should include enough request context to be useful in isolation
   (registry name, format pair, dataflow key) -- the cause's message often
   lacks that.
4. Never `catch (Throwable t) { throw new RuntimeException(t); }` without a
   message. `RateLimitingInvocationHandlerFactory:75` is the current offender;
   see migration table.

**Audit of current wrap sites:**

| Site                                                  | Cause preserved today? | Notes                                              |
|-------------------------------------------------------|------------------------|----------------------------------------------------|
| `AdapterRouterImpl:174` ("Failed to convert structures") | Yes (`new IAE("...", e)`) | Migrates to `StructureConversionException(msg, cause)` |
| `AdapterRouterImpl:271` ("Failed to convert data")       | Yes                       | Migrates to `DataConversionException`              |
| `AdapterRouterImpl:401` ("Failed to convert availability") | Yes                     | Migrates to `AvailabilityConversionException`      |
| `AdapterRouterImpl:220` ("Failed to read structures")    | Yes                       | Migrates to `StructureConversionException` (or new `StructureFetchException`) |
| `RedisCacheService` (6 sites)                            | Yes                       | Migrates to `CacheUnavailableException(msg, cause)` |
| `JsonAvailabilityResponseParser:69` ("Failed to parse availability response") | Yes | Migrates to `AvailabilityProbeException`           |
| `RateLimitingInvocationHandlerFactory:75`                | Partial: `new RuntimeException(e)` -- cause kept, message dropped | **Fix during migration**: pass the operation name as message. See also Q4 about unwrapping `BaseException` if `e instanceof BaseException`. |
| `AzureRedisCredentialsProvider` JWT parse failures (`:55`, `:69`) | Partial: some sites catch `JwtException` / `IOException` and rethrow without cause | **Fix during migration**: `CredentialProvisioningException(msg, cause)`. |
| `AwsRedisCredentialsProvider:86`, `GcpRedisCredentialsProvider:64` | Verify during phase 3 -- some wrap, some originate. | Wrap sites MUST pass cause; origin sites use `(message)` only. |
| `AgencySchemeService:131` (Jackson serialization)        | Verify -- likely already passes cause   | Migrates to `UnexpectedStateException(msg, cause)` |
| `StreamingFixtureIO:55` (piped stream setup)             | Verify                    | Migrates to `UnexpectedStateException` or `AvailabilityProbeException` |
| `ConfigServerConfigExtractor:69, 75`                     | Verify -- mixed: `:69` (resource missing) has no cause; `:75` (read failure) should wrap IOException | Migration must add cause where one exists. |
| `JsonAvailabilityResponseParser:50, 57`                  | No upstream -- synthetic check ("response is not a JSON object") | Origin site, `(message)` only is correct. |
| `QueryTranslatorImpl` config-missing sites (7+)          | No upstream -- check fails on a null/empty config field | Origin site, `(message)` only is correct. |
| `GenericRegistryAdapterImpl` switch defaults             | No upstream               | Origin site, `(message)` only is correct.          |
| `SeriesLimitTruncatorProvider`, `RepresentationMapper` enum lookups | No upstream      | Origin site.                                        |

The audit's takeaway: cause preservation in catch-and-rethrow code is mostly
correct today. The two real defects are `RateLimitingInvocationHandlerFactory:75`
(message dropped) and the Azure/AWS/GCP credential providers (some catch sites
re-throw `IllegalArgumentException` without passing the JWT-parse / IAM-token
exception as cause). Phase 3 must fix both and grep-verify before merging.

**Handler responsibilities for cause:**

1. **Log via `log.{level}("...", ex)` -- always pass the throwable as the last
   argument.** Log4j2 (the project's chosen logger) prints the full caused-by
   chain when the throwable is the last positional argument; passing only
   `ex.getMessage()` truncates it. Some current handlers do this correctly
   (`log.warn("Illegal argument: {}", ex.getMessage(), ex)`); some don't
   (`log.warn("Unsupported agency wildcard: {}", ex.getMessage())` -- chain
   lost). The family-handler refactor standardises on always passing `ex`.
2. **Do not unwrap the cause to substitute its message into the client
   response by default.** Cause messages are written for developers and often
   include sensitive paths, stack-traces-as-text, or implementation detail
   (e.g. Jackson's `JsonParseException` quotes the offending byte stream). The
   client message comes from `getClientMessage()`; the chain lives in logs and
   in the `traceparent`.
3. **Optional 4xx/501 enrichment (deferred, see Open Questions Q9).** For
   user-visible failures (BadRequest, NotImplemented), appending
   `cause.getClass().getSimpleName() + ": " + sanitise(cause.getMessage())`
   to the response could help client-side debugging, but it requires a
   sanitiser to scrub paths / tokens / SQL-like fragments. Out of scope for
   phase 1; raised in Q9.

**Enforcement:**

- Phase 3 grep-sweep: any `throw new BaseException` (or subclass) inside a
  `catch` block that doesn't pass the caught variable as the second arg is a
  defect. Reviewable mechanically.
- Optional Checkstyle/Spotbugs rule: forbid `new <BaseException>(String)`
  inside a catch block. Strict but mechanically enforceable.

Default policy by family:

- **4xx and 501**: `getClientMessage()` returns `getMessage()` verbatim. The thrower
  is responsible for producing a safe, client-actionable message (today's behaviour).
- **500**: `getClientMessage()` returns the generic
  `"An internal server error occurred."`. Concrete subclasses *may* override to
  expose detail when it's clearly client-safe (e.g.
  `IllegalRegistryConfigurationException` saying "Endpoint configuration is missing
  for registry X" is arguably useful for operators inspecting logs via response).
- **503**: `getClientMessage()` returns the generic
  `"A dependent service is currently unavailable."`. Same override option.

The handler's `clientMessage(ex)` helper just calls `ex.getClientMessage()`.

This avoids the current risk of `IllegalArgumentException` from a server-side bug
leaking "Failed to convert structures" to the client. Server-side bugs leak nothing;
operators see the full message + stack in logs.

### Backward compatibility

Audit of E2E and unit assertions for status / message dependencies:

- `StructureWildcardE2ETest:90` asserts `statusCode == 501` for
  `UnsupportedAgencyWildcardException`. **Preserved.**
- `LimitEmulationE2ETest` and the smoke / base suite test files assert
  `statusCode == 200` on success paths. **Unaffected.**
- `BaseRegistryTestSuite` asserts `statusCode == 200` only. **Unaffected.**
- No E2E test asserts on response message body text.
- No E2E test asserts on `statusCode == 400` / `500` / `503` -- these paths are
  unverified end-to-end today.

Unit-test grep is not exhaustive in this design; the migration phase should re-run
the search across `sdmx-proxy/src/test/java`.

Status changes that ship with the refactor:

1. `IllegalRegistryConfigurationException`: 400 -> 500. **Behavioural change.** An
   existing operator monitoring 4xx rates may see the volume shift to 5xx. The change
   is correct; flag it in the release notes.
2. Sites currently throwing `IllegalArgumentException` for server-side misconfig
   (mostly `QueryTranslatorImpl`): 400 -> 500. Same reasoning.
3. Sites currently throwing `UnsupportedOperationException` in conversion services:
   500 (catch-all) -> 501. Improvement; clients see a more accurate status.
4. Sites currently throwing raw `RuntimeException` / `IllegalStateException`: 500
   (catch-all) -> 500 or 503 depending on category. The message also changes -- today
   the catch-all scrubs the message; the new families either preserve it (with
   override) or emit a family-generic message.

No client of the proxy is expected to depend on the difference between 400 and 500
(they're both failures), but operators with dashboards filtering on status code will
see a shift. Recommended: announce in the release notes for the version that ships
phase 1.

## Implementation phasing

Three PRs to keep the diff reviewable.

### Phase 1 -- introduce hierarchy, retrofit handler, no semantic change

1. Add `BaseException` and the five family abstract classes under
   `com.epam.sdmxproxy.exception`. No status changes yet.
2. Re-parent the nine existing custom exceptions to their families. Keep their
   existing constructors so call sites compile. **No status flips in this PR.**
3. Refactor `GlobalExceptionHandler` to the five family methods + the three external
   handlers + catch-all. The nine per-class handlers go away because the family
   handlers cover them.
4. Add the message-scrubbing policy on the 5xx families (override
   `getClientMessage()` on the concrete classes as needed to preserve current
   wire messages).
5. **Critical:** test that `IllegalRegistryConfigurationException` still returns 400
   in this phase. This is a no-status-change PR.

End state: handler refactored, all custom exceptions in the hierarchy, **wire is
unchanged**.

### Phase 2 -- migrate exception/ package status fixes

1. Flip `IllegalRegistryConfigurationException` from `BadRequestException`
   to `ServerErrorException`. Release-note the 400 -> 500 change.
2. Re-evaluate `AgencyRoutingException` -- decision item in Open Questions Q5.

End state: existing custom exceptions all map to their semantically-correct statuses.

### Phase 3 -- migrate ad-hoc throws

1. Introduce the new concrete exceptions listed in the hierarchy (`UnexpectedStateException`,
   `UnsupportedConversionException`, `StructureConversionException`,
   `DataConversionException`, `AvailabilityConversionException`,
   `CacheUnavailableException`, `CredentialProvisioningException`,
   `AvailabilityProbeException`, `ConfigurationLoadException`).
2. Replace ad-hoc `throw new IllegalArgumentException(...)` /
   `IllegalStateException(...)` / `RuntimeException(...)` /
   `UnsupportedOperationException(...)` in our code with the appropriate concrete
   class. Use the migration table above.
3. Sweep: grep `sdmx-proxy/src/main/java` for the four ad-hoc throws and confirm zero
   hits (excepting clearly-internal preconditions in private helpers that document
   programming bugs -- discuss case by case during review).
4. Update Checkstyle / Spotbugs rules if available to forbid raw
   `IllegalArgumentException` / `IllegalStateException` / `RuntimeException` in
   `com.epam.sdmxproxy.*` source.

End state: no ad-hoc `IllegalArgumentException` / `RuntimeException` /
`IllegalStateException` originates in our code; everything maps to the right HTTP
status with the right logging policy.

Phases 2 and 3 can ship together if reviewers prefer; phase 1 should ship alone to
isolate the no-op refactor from the behavioural changes.

## Open Questions

**Q1. Status flip for `IllegalRegistryConfigurationException` -- now or later?**
The current 400 is semantically wrong but has been in production. Options:
(a) ship the flip in phase 2 with a release note; (b) keep it 400 for now and
revisit in a separate PR with telemetry to estimate blast radius. The design
recommends (a); flagging the question because operators may have dashboards
filtering on 4xx rates.

**Q2. `HttpMediaTypeNotAcceptableException` -- 400 or 406?**
The current handler returns 400 for this Spring exception. The semantically correct
status is 406 Not Acceptable. The example handler above silently fixes this. Confirm
whether to include this fix in phase 1 or skip it (it's not strictly part of the
custom-exception refactor).

**Q3. Should Redis op failures be 503 or silent degradation?**
Some `RedisCacheService` sites are called from contexts that could fall through to
"cache miss" instead of failing the request. If a cache outage should *not* take
down requests, those sites should swallow the exception locally (return empty
Optional / log warn) and not need a new exception class. Worth auditing the callers
of each cache method before phase 3 to decide which sites genuinely fail-fast.

**Q4. `RateLimitingInvocationHandlerFactory:75` cause unwrapping.**
The current code does `throw new RuntimeException(e)` from inside a Resilience4j
callable; the only intentional throw is the wrapped `RateLimitExceededException`
in the outer catch block. The `RuntimeException` wrapping a `Throwable` is suspect:
if the original cause is itself a `BaseException`, the wrapper may swallow it.
Should be reviewed as part of phase 3; the design assigns it to
`UnexpectedStateException` provisionally.

**Q5. `AgencyRoutingException` -- 400 or 404?**
"Agency not configured" can be argued either way. Current behaviour is 400. The
design keeps 400 for backward compatibility. If 404 is preferred (the agency is the
"resource" that doesn't exist), introduce a `SdmxProxyNotFoundException` family and
re-parent. Trivial change but a wire-visible one; defer to the team.

**Q6. `RegistryUnavailableException` is defined but never thrown.**
Today the class exists and has a 503 handler, but no code throws it. The likely
intent is to map Resilience4j's `CallNotPermittedException` (circuit-breaker open)
to it -- but that mapping is not in the codebase. Either remove the class as dead
code, or add the missing translation in the circuit-breaker wrapper. Recommendation:
add the translation in phase 3 so the 503 behaviour the handler claims actually
occurs. Confirm whether circuit breakers are deployed in any current registry
config.

**Q7. Boot-time `ConfigurationLoadException`.**
The config-extractor failures fire during Spring startup, before
`GlobalExceptionHandler` is engaged. The new class is mostly documentation /
symmetry. Worth keeping? The alternative is to leave those extractors throwing
`IllegalStateException` since the app won't start either way. Design proposes
introducing the class for code-quality reasons (no raw `IllegalStateException` in
our package); reasonable to defer.

**Q8. `UnsupportedOperationException` as a JDK marker.**
The `UnsupportedOperationException` sites in conversion services are semantically
"not implemented yet". The JDK convention is that `UnsupportedOperationException`
indicates an *unsupported* method on a type (e.g. immutable collections), not a
runtime feature gap. The design migrates them to `UnsupportedConversionException`
under the 501 family. Confirm the naming -- alternatives include
`NotImplementedException` or `ConversionNotSupportedException`.

**Q9. Surface root-cause info in 4xx / 501 client responses?**
Today, when `AdapterRouterImpl` wraps a conversion failure into
`"Failed to convert structures"`, the response body shows only that wrapper
message. The cause (e.g. a Jackson `JsonParseException` at a specific offset,
or a sdmx-core `SdmxSemmanticException` complaining about a malformed code) is
preserved in the exception object and printed in logs -- but the client gets no
hint about what actually broke and is left guessing. Should the handler
optionally append `caused by <CauseClass>: <safeMessage>` to the response body
for 4xx and 501 families? Considerations:
- Pros: clients (especially internal callers and operators reading curl output)
  see immediate diagnostic context instead of having to ask for a `traceparent`
  search.
- Cons: cause messages are not curated for end-user consumption; some may leak
  paths, internal IDs, or fragments that could surprise a security review.
  Requires a sanitiser allowlist (e.g. always-safe classes:
  `SdmxSemmanticException`, `SdmxNotImplementedException`, our own
  `BaseException` subtypes; everything else gets class name only, no
  message).
- 5xx never gets cause enrichment -- the generic client message stays.
Recommendation: defer to a follow-up; phase 1 keeps responses identical to
today. If we decide to do it, the implementation lives in the family handlers
and uses a small `CauseSanitiser` component.
