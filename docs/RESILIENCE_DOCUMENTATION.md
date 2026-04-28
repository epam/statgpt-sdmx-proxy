# Resilience Features Documentation

This document describes how resilience features are implemented and configured in the SDMX Proxy application. The application uses **Resilience4j** to provide circuit breakers, retry mechanisms, timeout configuration, and rate limiting for Feign HTTP clients.

---

## Table of Contents

1. [Overview](#overview)
2. [Architecture](#architecture)
3. [Configuration Structure](#configuration-structure)
4. [Circuit Breaker](#circuit-breaker)
5. [Retry Mechanism](#retry-mechanism)
6. [Timeout Configuration](#timeout-configuration)
7. [Rate Limiting](#rate-limiting)
8. [Exception Handling](#exception-handling)
9. [How It Works](#how-it-works)

---

## Overview

The SDMX Proxy application implements resilience patterns to handle failures gracefully when communicating with external SDMX registries. The resilience features are built on top of **Resilience4j** and integrated with **Feign** HTTP clients.

### Key Features

- **Circuit Breaker**: Prevents cascading failures by opening the circuit when failure rate exceeds threshold
- **Retry Mechanism**: Automatically retries failed requests with exponential backoff
- **Timeout Configuration**: Configurable connection and read timeouts per registry
- **Rate Limiting**: Controls request rate per registry and per method (optional, disabled by default)

### Design Principles

1. **Per-Registry Configuration**: Each registry can have its own resilience settings
2. **Fallback to Defaults**: Registry-specific settings fall back to application-wide defaults
3. **Per-Operation Circuit Breakers**: Separate circuit breakers for different operations (data, structures, availability)
4. **Flag-Based Rate Limiting**: Rate limiting is controlled by explicit flags (disabled by default)
5. **Exception-Based Retries**: Retries only on transient failures (timeouts, 5xx errors)

---

## Architecture

### Component Diagram

```
SdmxApiClientProviderImpl
    │
    ├── Resilience4jComponentFactory
    │   ├── Creates CircuitBreaker (per registry + operation)
    │   └── Creates Retry (per registry)
    │
    ├── RateLimitingInvocationHandlerFactory (conditional)
    │   └── Creates RateLimiter (per method)
    │
    └── Feign Client Builder
        ├── FeignDecorators (CircuitBreaker + Retry)
        ├── Request.Options (Timeouts)
        └── InvocationHandlerFactory (Rate Limiting)
```

### Integration Flow

```java
// Simplified flow in SdmxApiClientProviderImpl.buildClient()
private <T extends SdmxApiBase> T buildClient(
        Class<T> clientClass, 
        String baseUrl, 
        RegistryConfiguration registryConfiguration) {
    
    // 1. Create Resilience4j decorators (Circuit Breaker + Retry)
    FeignDecorators decorators = FeignDecorators.builder()
            .withCircuitBreaker(
                    resilience4JComponentFactory.getOrCreateCircuitBreaker(
                            registryConfiguration, 
                            getOperationName(clientClass)
                    )
            )
            .withRetry(
                    resilience4JComponentFactory.getOrCreateRetry(registryConfiguration)
            )
            .build();

    // 2. Build Feign client with Resilience4j decorators
    var builder = Resilience4jFeign.builder(decorators)
            .client(baseOkHttpClient)
            .options(getOptions(registryConfiguration))  // Timeouts
            .encoder(encoder)
            .decoder(decoder);

    // 3. Conditionally add rate limiting
    addRateLimiting(registryConfiguration, builder);

    return builder.target(clientClass, baseUrl);
}
```

---

## Configuration Structure

### Configuration Hierarchy

The resilience configuration follows a **hierarchical structure**:

```
RegistryConfiguration
    └── RegistryResilienceConfig (optional)
        ├── connectionTimeout
        ├── readTimeout
        ├── RegistryCircuitBreakerConfig (optional)
        ├── RegistryRetryConfig (optional)
        └── RegistryRateLimitConfig (optional)
            └── enabled (flag)
```

### Class Structure

```java
// RegistryConfiguration.java
@Data
public class RegistryConfiguration {
    private String name;
    private String description;
    private String baseUrl;
    private String dataUrl;
    // ... other registry properties ...
    
    /**
     * Resilience configuration.
     * Contains all resilience-related settings: timeouts, circuit breaker, retry, and rate limiting.
     * If null, all resilience settings will use application-wide defaults.
     */
    private RegistryResilienceConfig resilienceConfig;
}

// RegistryResilienceConfig.java
@Data
public class RegistryResilienceConfig {
    private Integer connectionTimeout;
    private Integer readTimeout;
    private RegistryCircuitBreakerConfig circuitBreaker;
    private RegistryRetryConfig retry;
    private RegistryRateLimitConfig rateLimit;
}

// RegistryRateLimitConfig.java
@Data
public class RegistryRateLimitConfig {
    /**
     * Enable rate limiting for this registry.
     * If null, uses application-wide default (defaultRateLimitingEnabled).
     */
    private Boolean enabled;
    
    private Integer limitForPeriod;
    private Long limitRefreshPeriod = 60000L;
}
```

### Configuration Resolution

The `ConfigUtils.getWithFallbackToDefault()` utility method handles configuration resolution:

```java
// ConfigUtils.java
@NonNull
public static <T> T getWithFallbackToDefault(
        T registryValue, 
        Supplier<T> defaultSupplier) {
    return registryValue != null ? registryValue : defaultSupplier.get();
}
```

**Usage Example:**

```java
// In Resilience4jComponentFactory.java
RegistryResilienceConfig resilienceConfig = registryConfig.getResilienceConfig();
RegistryCircuitBreakerConfig registryCbConfig = resilienceConfig != null 
    ? resilienceConfig.getCircuitBreaker() 
    : null;

float failureRateThreshold = ConfigUtils.getWithFallbackToDefault(
        registryCbConfig != null ? registryCbConfig.getFailureRateThreshold() : null,
        defaultCbConfig::getFailureRateThreshold
);
```

---

## Circuit Breaker

### Purpose

The circuit breaker prevents cascading failures by temporarily stopping requests to a failing registry. When the failure rate exceeds a threshold, the circuit opens and requests fail fast without attempting to call the registry.

### States

1. **CLOSED**: Normal operation, requests pass through
2. **OPEN**: Circuit is open, requests fail immediately
3. **HALF_OPEN**: Testing if registry has recovered

### Configuration

#### Application-Wide Defaults (`application.yaml`)

```yaml
sdmxproxy:
  registry:
    resilience:
      defaultCircuitBreaker:
        failureRateThreshold: 50.0      # Percentage of failures to trigger open
        minimumNumberOfCalls: 10         # Min calls before evaluating failure rate
        waitDurationInOpenState: 60000   # Milliseconds to wait before half-open
        slidingWindowSize: 10           # Number of calls in sliding window
```

#### Per-Registry Override (`sdmx_registries_config.json`)

```json
{
  "name": "BIS 2.1",
  "resilienceConfig": {
    "circuitBreaker": {
      "failureRateThreshold": 50.0,
      "minimumNumberOfCalls": 10,
      "waitDurationInOpenState": 60000,
      "slidingWindowSize": 10
    }
  }
}
```

### Implementation

```java
// Resilience4jComponentFactory.java
public CircuitBreaker getOrCreateCircuitBreaker(
        RegistryConfiguration registryConfig, 
        String operationName) {
    
    RegistryResilienceConfig resilienceConfig = registryConfig.getResilienceConfig();
    RegistryCircuitBreakerConfig registryCbConfig = resilienceConfig != null 
        ? resilienceConfig.getCircuitBreaker() 
        : null;
    CircuitBreakerProperties defaultCbConfig = defaultConfig.getDefaultCircuitBreaker();

    // Resolve configuration with fallback to defaults
    float failureRateThreshold = ConfigUtils.getWithFallbackToDefault(
            registryCbConfig != null ? registryCbConfig.getFailureRateThreshold() : null,
            defaultCbConfig::getFailureRateThreshold
    );
    
    // ... resolve other properties ...

    CircuitBreakerConfig circuitBreakerConfig = CircuitBreakerConfig.custom()
            .failureRateThreshold(failureRateThreshold)
            .minimumNumberOfCalls(minimumNumberOfCalls)
            .waitDurationInOpenState(Duration.ofMillis(waitDuration))
            .slidingWindowSize(slidingWindowSize)
            .recordException(upstreamFailurePredicate())  // IOException + 5xx FeignException only
            .build();

    String circuitBreakerName = registryConfig.getName() + "-" + operationName;
    return CircuitBreaker.of(circuitBreakerName, circuitBreakerConfig);
}
```

### How It Works

1. **Per-Registry and Per-Operation**: Each registry has separate circuit breakers for different operations:
   - `BIS 2.1-sdmx21dataclient` for data requests
   - `BIS 2.1-sdmx21structureclient` for structure requests
   - `BIS 2.1-sdmx21availabilityclient` for availability requests

2. **Failure Tracking**: Only upstream-health failures are recorded — `IOException` (network/connection errors) and `FeignException` with status `>= 500`. Client-error responses such as `4xx` (e.g. SDMX `404 "No results for query"`) are valid registry answers about a client-input domain and are not counted toward the failure rate. When the failure rate exceeds the threshold, the circuit opens.

3. **Circuit Opening**: When the circuit opens, all requests immediately fail with `RegistryUnavailableException` (returns HTTP 503).

4. **Recovery**: After `waitDurationInOpenState` milliseconds, the circuit transitions to HALF_OPEN state. If the next request succeeds, it closes; otherwise, it reopens.

---

## Retry Mechanism

### Purpose

The retry mechanism automatically retries failed requests for transient failures (timeouts, network errors, 5xx server errors) using exponential backoff to avoid overwhelming the registry.

### Configuration

#### Application-Wide Defaults (`application.yaml`)

```yaml
sdmxproxy:
  registry:
    resilience:
      defaultRetry:
        maxAttempts: 5                    # Total attempts (1 initial + 4 retries)
        initialIntervalMillis: 500        # Initial delay: 500ms
        multiplier: 2.0                   # Each retry doubles the delay
        maxIntervalMillis: 2000           # Maximum delay: 2000ms
```

#### Per-Registry Override (`sdmx_registries_config.json`)

```json
{
  "name": "IMF 2.1",
  "resilienceConfig": {
    "retry": {
      "maxAttempts": 3,
      "initialIntervalMillis": 1000,
      "multiplier": 2.0,
      "maxIntervalMillis": 4000
    }
  }
}
```

### Retry Schedule Example

With default settings (`maxAttempts: 5`, `initialIntervalMillis: 500`, `multiplier: 2.0`, `maxIntervalMillis: 2000`):

1. **Attempt 1**: Immediate (initial request)
2. **Attempt 2**: After 500ms (initial interval)
3. **Attempt 3**: After 1000ms (500ms × 2.0)
4. **Attempt 4**: After 2000ms (capped at maxIntervalMillis)
5. **Attempt 5**: After 2000ms (capped at maxIntervalMillis)

**Total time**: ~5.5 seconds if all attempts fail

### Implementation

```java
// Resilience4jComponentFactory.java
public Retry getOrCreateRetry(RegistryConfiguration registryConfig) {
    RegistryResilienceConfig resilienceConfig = registryConfig.getResilienceConfig();
    RegistryRetryConfig registryRetryConfig = resilienceConfig != null 
        ? resilienceConfig.getRetry() 
        : null;
    RetryProperties defaultRetryConfig = defaultConfig.getDefaultRetry();

    // Resolve configuration with fallback to defaults
    int maxAttempts = ConfigUtils.getWithFallbackToDefault(
            registryRetryConfig != null ? registryRetryConfig.getMaxAttempts() : null,
            defaultRetryConfig::getMaxAttempts
    );
    
    // ... resolve other properties ...

    RetryConfig retryConfig = RetryConfig.custom()
            .maxAttempts(maxAttempts)
            .intervalFunction(
                    IntervalFunction.ofExponentialBackoff(
                            Duration.ofMillis(initialIntervalMillis),
                            multiplier,
                            Duration.ofMillis(maxIntervalMillis)
                    )
            )
            .retryOnException(configureExceptionRetries())
            .build();

    return Retry.of(registryConfig.getName() + "-retry", retryConfig);
}

private Predicate<Throwable> configureExceptionRetries() {
    return throwable -> {
        // Retry on network I/O errors
        if (throwable instanceof java.io.IOException) {
            return true;
        }
        // Retry on 5xx server errors
        if (throwable instanceof feign.FeignException feignEx) {
            return feignEx.status() >= 500;
        }
        return false;
    };
}
```

### Retry Conditions

The retry mechanism only retries on **transient failures**:

- ✅ **Network I/O Errors**: `IOException`, `SocketTimeoutException`, `ConnectException`
- ✅ **Server Errors**: HTTP 5xx status codes (`FeignException` with status >= 500)
- ❌ **Client Errors**: HTTP 4xx status codes (not retried)
- ❌ **Circuit Breaker Open**: `RegistryUnavailableException` (not retried)

---

## Timeout Configuration

### Purpose

Timeout configuration prevents requests from hanging indefinitely by setting maximum wait times for connection establishment and response reading.

### Configuration Levels

The application supports **two levels** of timeout configuration:

1. **OkHttp Client Timeouts** (application-wide): Configure the underlying HTTP client
2. **Feign Request Timeouts** (per-registry): Configure Feign request options

### OkHttp Client Timeouts (`application.yaml`)

```yaml
sdmxproxy:
  feign:
    http:
      client:
        connectTimeout: 10000      # Time to establish connection (10 seconds)
        readTimeout: 30000          # Time to read response (30 seconds)
        writeTimeout: 30000         # Time to write request (30 seconds)
```

These timeouts apply to **all registries** and configure the underlying OkHttp client connection pool.

### Feign Request Timeouts

#### Application-Wide Defaults (`application.yaml`)

```yaml
sdmxproxy:
  registry:
    resilience:
      defaultConnectionTimeout: 30000   # Milliseconds
      defaultReadTimeout: 30000         # Milliseconds
```

#### Per-Registry Override (`sdmx_registries_config.json`)

```json
{
  "name": "IMF 2.1",
  "resilienceConfig": {
    "connectionTimeout": 45000,
    "readTimeout": 60000
  }
}
```

### Implementation

```java
// SdmxApiClientProviderImpl.java
private Request.Options getOptions(RegistryConfiguration registryConfiguration) {
    RegistryResilienceConfig registryResilienceConfig = registryConfiguration.getResilienceConfig();
    
    // Resolve connection timeout with fallback to default
    int connectionTimeout = registryResilienceConfig != null && registryResilienceConfig.getConnectionTimeout() != null
            ? registryResilienceConfig.getConnectionTimeout()
            : resilienceConfig.getDefaultConnectionTimeout();
    
    // Resolve read timeout with fallback to default
    int readTimeout = registryResilienceConfig != null && registryResilienceConfig.getReadTimeout() != null
            ? registryResilienceConfig.getReadTimeout()
            : resilienceConfig.getDefaultReadTimeout();

    return new Request.Options(connectionTimeout, readTimeout);
}
```

### Timeout Behavior

- **Connection Timeout**: If the connection cannot be established within the timeout, a `ConnectException` is thrown → triggers retry mechanism
- **Read Timeout**: If the response is not received within the timeout, a `SocketTimeoutException` is thrown → triggers retry mechanism

---

## Rate Limiting

### Purpose

Rate limiting controls the number of requests per time period to prevent overwhelming registries. Rate limiting is **disabled by default** and can be enabled per-registry.

### Configuration

#### Application-Wide Defaults (`application.yaml`)

```yaml
sdmxproxy:
  registry:
    resilience:
      # Enable rate limiting application-wide (default: false)
      defaultRateLimitingEnabled: false
      
      # Rate limit configuration (used when enabled)
      defaultRateLimit:
        limitForPeriod: 100           # Max requests per period
        limitRefreshPeriod: 60000     # Period duration in milliseconds (60 seconds)
```

#### Per-Registry Override (`sdmx_registries_config.json`)

```json
{
  "name": "BIS 2.1",
  "resilienceConfig": {
    "rateLimit": {
      "enabled": true,                # Enable rate limiting for this registry
      "limitForPeriod": 100,          # 100 requests per period
      "limitRefreshPeriod": 60000     # 60 seconds period
    }
  }
}
```

### How Rate Limiting Works

1. **Per-Method Rate Limiters**: Each Feign client method gets its own rate limiter
2. **Token Bucket Algorithm**: Resilience4j uses a token bucket algorithm
3. **Immediate Failure**: When rate limit is exceeded, `RateLimitExceededException` is thrown immediately (no queuing)

### Implementation

#### Enabling Rate Limiting

```java
// SdmxApiClientProviderImpl.java
private void addRateLimiting(
        RegistryConfiguration registryConfiguration, 
        Feign.Builder builder) {
    
    if (isRateLimitingEnabled(registryConfiguration)) {
        InvocationHandlerFactory rateLimitingFactory = new RateLimitingInvocationHandlerFactory(
                registryConfiguration,
                resilienceConfig
        );
        builder.invocationHandlerFactory(rateLimitingFactory);
    }
}

private boolean isRateLimitingEnabled(RegistryConfiguration registryConfiguration) {
    RegistryResilienceConfig registryResilienceConfig = registryConfiguration.getResilienceConfig();
    
    // Check registry-specific flag first
    if (registryResilienceConfig != null && registryResilienceConfig.getRateLimit() != null) {
        Boolean enabled = registryResilienceConfig.getRateLimit().getEnabled();
        if (enabled != null) {
            return enabled;
        }
    }
    
    // Fall back to application-wide default (always non-null, defaults to false)
    return resilienceConfig.getDefaultRateLimitingEnabled();
}
```

#### Rate Limiter Creation

```java
// RateLimitingInvocationHandlerFactory.java
private RateLimiter createRateLimiter(String rateLimiterName) {
    RegistryResilienceConfig resilienceConfig = registryConfiguration.getResilienceConfig();
    RegistryRateLimitConfig registryRateLimit = resilienceConfig != null 
        ? resilienceConfig.getRateLimit() 
        : null;
    RateLimitProperties defaultRateLimit = resilienceProperties.getDefaultRateLimit();

    // Resolve configuration with fallback to defaults
    Integer limitForPeriod = ConfigUtils.getWithFallbackToDefault(
            registryRateLimit != null ? registryRateLimit.getLimitForPeriod() : null,
            defaultRateLimit::getLimitForPeriod
    );
    
    Long limitRefreshPeriod = ConfigUtils.getWithFallbackToDefault(
            registryRateLimit != null ? registryRateLimit.getLimitRefreshPeriod() : null,
            defaultRateLimit::getLimitRefreshPeriod
    );

    RateLimiterConfig rateLimiterConfig = RateLimiterConfig.custom()
            .limitForPeriod(limitForPeriod)
            .limitRefreshPeriod(Duration.ofMillis(limitRefreshPeriod))
            .timeoutDuration(Duration.ZERO)  // Fail immediately if limit exceeded
            .build();

    return RateLimiter.of(rateLimiterName, rateLimiterConfig);
}
```

### Why `timeoutDuration = Duration.ZERO`?

The `timeoutDuration` is set to `Duration.ZERO` to ensure **immediate failure** when the rate limit is exceeded. This means:

- ❌ **No Queuing**: Requests don't wait for tokens to become available
- ✅ **Fast Failure**: Requests fail immediately with `RateLimitExceededException`
- ✅ **Clear Feedback**: Clients receive HTTP 429 (Too Many Requests) immediately

---

## Exception Handling

### Custom Exceptions

The application defines two custom exceptions for resilience-related failures:

#### `RegistryUnavailableException`

Thrown when the circuit breaker is open and requests cannot be made to the registry.

```java
public class RegistryUnavailableException extends RuntimeException {
    public RegistryUnavailableException(String message) {
        super(message);
    }
    
    public RegistryUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

#### `RateLimitExceededException`

Thrown when the rate limit is exceeded for a registry.

```java
public class RateLimitExceededException extends RuntimeException {
    public RateLimitExceededException(String message) {
        super(message);
    }
    
    public RateLimitExceededException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

### Global Exception Handler

The `GlobalExceptionHandler` maps resilience exceptions to appropriate HTTP status codes:

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(RegistryUnavailableException.class)
    public ResponseEntity<String> handleRegistryUnavailableException(
            RegistryUnavailableException ex) {
        log.warn("Registry unavailable: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ex.getMessage());
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<String> handleRateLimitExceededException(
            RateLimitExceededException ex) {
        log.warn("Rate limit exceeded: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(ex.getMessage());
    }
}
```

### HTTP Status Codes

| Exception | HTTP Status | Description |
|-----------|-------------|-------------|
| `RegistryUnavailableException` | 503 Service Unavailable | Circuit breaker is open |
| `RateLimitExceededException` | 429 Too Many Requests | Rate limit exceeded |
| `IOException` / `SocketTimeoutException` | 500 Internal Server Error | Network/timeout errors (after retries exhausted) |
| `FeignException` (4xx) | 500 Internal Server Error | Client errors (not retried) |
| `FeignException` (5xx) | 500 Internal Server Error | Server errors (after retries exhausted) |

---

## How It Works

### Request Flow with Resilience Features

```
1. Client Request
   ↓
2. SdmxApiClientProvider.getData21Client()
   ↓
3. Feign Client Method Call (e.g., getData())
   ↓
4. Rate Limiter (if enabled)
   ├─ Token available? → Continue
   └─ No token → RateLimitExceededException (HTTP 429)
   ↓
5. Circuit Breaker
   ├─ Circuit CLOSED? → Continue
   └─ Circuit OPEN → RegistryUnavailableException (HTTP 503)
   ↓
6. Retry Mechanism
   ├─ Attempt 1: Call registry
   ├─ Success? → Return response
   └─ Failure? → Wait (exponential backoff) → Retry
   ↓
7. Timeout Check
   ├─ Connection timeout → ConnectException → Retry
   └─ Read timeout → SocketTimeoutException → Retry
   ↓
8. HTTP Request (OkHttp Client)
   ↓
9. Registry Response
   ├─ Success → Return InputStream
   └─ Failure → Record in circuit breaker → Throw exception
```

### Complete Configuration Example

#### `application.yaml`

```yaml
sdmxproxy:
  feign:
    http:
      client:
        connectTimeout: 10000
        readTimeout: 30000
        writeTimeout: 30000
        maxIdleConnections: 50
        keepAliveDuration: 300000
        connectionPoolingEnabled: true
  
  registry:
    resilience:
      # Timeouts
      defaultConnectionTimeout: 30000
      defaultReadTimeout: 30000
      
      # Retry
      defaultRetry:
        maxAttempts: 5
        initialIntervalMillis: 500
        multiplier: 2.0
        maxIntervalMillis: 2000
      
      # Circuit Breaker
      defaultCircuitBreaker:
        failureRateThreshold: 50.0
        minimumNumberOfCalls: 10
        waitDurationInOpenState: 60000
        slidingWindowSize: 10
      
      # Rate Limiting
      defaultRateLimitingEnabled: false
      defaultRateLimit:
        limitForPeriod: 100
        limitRefreshPeriod: 60000
```

#### `sdmx_registries_config.json`

```json
{
  "configs": [
    {
      "name": "BIS 2.1",
      "baseUrl": "https://stats.bis.org/api/v1/",
      "dataUrl": "https://stats.bis.org/api/v1/data/",
      "sdmxVersion": "SDMX_2_1",
      "availabilityEnabled": true,
      
      "resilienceConfig": {
        "connectionTimeout": 30000,
        "readTimeout": 30000,
        
        "circuitBreaker": {
          "failureRateThreshold": 50.0,
          "minimumNumberOfCalls": 10,
          "waitDurationInOpenState": 60000,
          "slidingWindowSize": 10
        },
        
        "retry": {
          "maxAttempts": 5,
          "initialIntervalMillis": 500,
          "multiplier": 2.0,
          "maxIntervalMillis": 2000
        },
        
        "rateLimit": {
          "enabled": true,
          "limitForPeriod": 100,
          "limitRefreshPeriod": 60000
        }
      }
    },
    {
      "name": "IMF 2.1",
      "baseUrl": "https://api.imf.org/external/sdmx/2.1/",
      "sdmxVersion": "SDMX_2_1",
      "availabilityEnabled": true,
      
      "resilienceConfig": {
        "connectionTimeout": 45000,
        "readTimeout": 60000,
        
        "circuitBreaker": {
          "failureRateThreshold": 40.0,
          "minimumNumberOfCalls": 5,
          "waitDurationInOpenState": 120000,
          "slidingWindowSize": 20
        },
        
        "retry": {
          "maxAttempts": 3,
          "initialIntervalMillis": 1000,
          "multiplier": 2.0,
          "maxIntervalMillis": 4000
        }
        
        // Rate limiting not specified - uses defaultRateLimitingEnabled: false
      }
    }
  ]
}
```

---

## Summary

The SDMX Proxy application implements comprehensive resilience features using Resilience4j:

- ✅ **Circuit Breaker**: Prevents cascading failures, per-registry and per-operation
- ✅ **Retry Mechanism**: Automatic retries with exponential backoff for transient failures
- ✅ **Timeout Configuration**: Configurable connection and read timeouts
- ✅ **Rate Limiting**: Optional per-method rate limiting (disabled by default)

All features are:
- **Configurable**: Per-registry with fallback to application-wide defaults
- **Transparent**: Integrated seamlessly with Feign clients
- **Exception-Aware**: Proper exception handling with appropriate HTTP status codes
- **Production-Ready**: Tested and optimized for real-world usage

The resilience configuration is now organized in a dedicated `RegistryResilienceConfig` class, making it easier to manage and extend resilience settings per registry.
