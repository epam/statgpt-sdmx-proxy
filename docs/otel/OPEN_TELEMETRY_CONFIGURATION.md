# OpenTelemetry Configuration Documentation

## Overview

This document describes how OpenTelemetry is configured in the `ai-dial-admin-backend` project. OpenTelemetry is
configured but **disabled by default** (`otel.sdk.disabled=true`). To enable it, set `OTEL_SDK_DISABLED=false` and
provide the required environment variables.

## Configuration Status

- **Status**: Configured but disabled by default
- **Enable by**: Setting `OTEL_SDK_DISABLED=false` and providing required environment variables
- **Auto-configuration**: Uses Spring Boot OpenTelemetry starter for automatic setup

## Key Configuration Files

### 1. `application.properties` (lines 96-109)

Main OpenTelemetry configuration properties:

```properties
#Opentelemetry
otel.sdk.disabled=${OTEL_SDK_DISABLED:true}
otel.service.name=dial-admin-backend
otel.exporter.otlp.endpoint=${OTEL_EXPORTER_OTLP_ENDPOINT}
otel.exporter.otlp.protocol=${OTEL_EXPORTER_OTLP_PROTOCOL}
otel.exporter.otlp.traces.endpoint=${OTEL_EXPORTER_OTLP_ENDPOINT}
otel.exporter.otlp.traces.protocol=${OTEL_EXPORTER_OTLP_PROTOCOL}
otel.exporter.otlp.metrics.endpoint=${OTEL_EXPORTER_OTLP_ENDPOINT}
otel.exporter.otlp.metrics.protocol=${OTEL_EXPORTER_OTLP_PROTOCOL}
otel.exporter.otlp.logs.endpoint=${OTEL_EXPORTER_OTLP_ENDPOINT}
otel.exporter.otlp.logs.protocol=${OTEL_EXPORTER_OTLP_PROTOCOL}
otel.logs.exporter=${OTEL_LOGS_EXPORTER:otlp}
otel.traces.exporter=${OTEL_TRACES_EXPORTER:otlp}
otel.metrics.exporter=${OTEL_METRICS_EXPORTER:otlp}
```

### 2. `build.gradle` (lines 125-141)

OpenTelemetry dependencies:

```gradle
implementation platform('io.opentelemetry.instrumentation:opentelemetry-instrumentation-bom:2.12.0')
implementation 'io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter:2.12.0'
implementation 'io.opentelemetry:opentelemetry-sdk'
implementation 'io.opentelemetry:opentelemetry-exporter-otlp'
implementation 'io.opentelemetry.instrumentation:opentelemetry-log4j-appender-2.17:1.33.6-alpha'
```

**Key Dependencies:**

- **BOM**: `opentelemetry-instrumentation-bom:2.12.0` - Manages version consistency
- **Spring Boot Starter**: `opentelemetry-spring-boot-starter:2.12.0` - Provides auto-configuration
- **SDK**: Core OpenTelemetry SDK
- **OTLP Exporter**: For exporting telemetry data via OTLP protocol
- **Log4j2 Appender**: Integrates OpenTelemetry with Log4j2 logging

### 3. `log4j2.xml` (line 13)

Logging integration with OpenTelemetry:

```xml

<OpenTelemetry name="OpenTelemetryAppender"/>
```

The console pattern includes trace context:

```xml
pattern="%style{%d{ISO8601}}{green} %highlight{%-5level} trace_id: %X{trace_id} span_id=%X{span_id} [%style{%t}{bright,blue}] %style{%c{1.}}{bright,yellow}: %msg%n%throwable"
```

## Key Classes Involved

### 1. `CorrelationIdInterceptor`

**Location**: `src/main/java/com/epam/aidial/cfg/configuration/logging/CorrelationIdInterceptor.java`

**Purpose**: Adds `traceparent` header to all HTTP responses for distributed tracing correlation.

**Key Features**:

- Implements `HandlerInterceptor`
- Uses OpenTelemetry trace IDs exclusively via W3C Trace Context standard
- Sets `traceparent` header in `preHandle()` method
- Header format: `00-{trace-id}-{span-id}-{trace-flags}`

**Code Structure**:

```java
public class CorrelationIdInterceptor implements HandlerInterceptor {
    public static final String TRACEPARENT_HEADER_NAME = "traceparent";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String traceParent = TraceContextUtils.formatTraceParent();
        if (traceParent != null) {
            response.setHeader(TRACEPARENT_HEADER_NAME, traceParent);
        }
        return true;
    }
}
```

### 2. `TraceContextUtils`

**Location**: `src/main/java/com/epam/aidial/cfg/utils/TraceContextUtils.java`

**Purpose**: Utility class for extracting trace context information from OpenTelemetry.

**Key Methods**:

- `getTraceId()` - Gets the current trace ID from OpenTelemetry span context
- `getSpanId()` - Gets the current span ID from OpenTelemetry span context
- `formatTraceParent()` - Formats W3C Trace Context `traceparent` header value

**Implementation Details**:

- Uses `Span.current()` to get the current OpenTelemetry span
- Validates span context before returning values
- Formats traceparent according to W3C Trace Context standard: `00-{trace-id}-{span-id}-{trace-flags}`

### 3. `ErrorView`

**Location**: `src/main/java/com/epam/aidial/cfg/web/handler/ErrorView.java`

**Purpose**: Error response view that includes trace information for distributed tracing.

**Key Features**:

- Includes `traceparent` field in error response JSON
- Populates trace information from OpenTelemetry context in constructor
- Enables trace correlation in error responses

**Example Error Response**:

```json
{
  "path": "/api/v1/applications",
  "method": "GET",
  "status": 404,
  "error": "Not Found",
  "message": "Application not found",
  "traceparent": "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
}
```

### 4. `WebMvcConfig`

**Location**: `src/main/java/com/epam/aidial/cfg/configuration/logging/WebMvcConfig.java`

**Purpose**: Spring configuration class that registers the `CorrelationIdInterceptor`.

**Key Features**:

- Creates `CorrelationIdInterceptor` as a Spring bean
- Registers interceptor with highest precedence (`Ordered.HIGHEST_PRECEDENCE`)
- Ensures trace headers are set early in the request processing pipeline

## Configuration Properties Reference

| Property                              | Environment Variable          | Default              | Required When    | Description                                       |
|---------------------------------------|-------------------------------|----------------------|------------------|---------------------------------------------------|
| `otel.sdk.disabled`                   | `OTEL_SDK_DISABLED`           | `true`               | -                | Disable OpenTelemetry SDK                         |
| `otel.service.name`                   | `OTEL_SERVICE_NAME`           | `dial-admin-backend` | -                | Service name for telemetry                        |
| `otel.exporter.otlp.endpoint`         | `OTEL_EXPORTER_OTLP_ENDPOINT` | -                    | When SDK enabled | OpenTelemetry collector endpoint                  |
| `otel.exporter.otlp.protocol`         | `OTEL_EXPORTER_OTLP_PROTOCOL` | -                    | When SDK enabled | Protocol for OpenTelemetry data export            |
| `otel.exporter.otlp.traces.endpoint`  | `OTEL_EXPORTER_OTLP_ENDPOINT` | -                    | When SDK enabled | Traces endpoint (inherits from general endpoint)  |
| `otel.exporter.otlp.traces.protocol`  | `OTEL_EXPORTER_OTLP_PROTOCOL` | -                    | When SDK enabled | Traces protocol (inherits from general protocol)  |
| `otel.exporter.otlp.metrics.endpoint` | `OTEL_EXPORTER_OTLP_ENDPOINT` | -                    | When SDK enabled | Metrics endpoint (inherits from general endpoint) |
| `otel.exporter.otlp.metrics.protocol` | `OTEL_EXPORTER_OTLP_PROTOCOL` | -                    | When SDK enabled | Metrics protocol (inherits from general protocol) |
| `otel.exporter.otlp.logs.endpoint`    | `OTEL_EXPORTER_OTLP_ENDPOINT` | -                    | When SDK enabled | Logs endpoint (inherits from general endpoint)    |
| `otel.exporter.otlp.logs.protocol`    | `OTEL_EXPORTER_OTLP_PROTOCOL` | -                    | When SDK enabled | Logs protocol (inherits from general protocol)    |
| `otel.logs.exporter`                  | `OTEL_LOGS_EXPORTER`          | `otlp`               | -                | Exporter for application logs                     |
| `otel.traces.exporter`                | `OTEL_TRACES_EXPORTER`        | `otlp`               | -                | Exporter for distributed traces                   |
| `otel.metrics.exporter`               | `OTEL_METRICS_EXPORTER`       | `otlp`               | -                | Exporter for application metrics                  |

## How It Works

### 1. Auto-Configuration

- Spring Boot OpenTelemetry starter automatically configures OpenTelemetry based on `application.properties`
- No manual bean configuration required (beyond interceptor registration)

### 2. Trace Propagation

- `CorrelationIdInterceptor` intercepts all HTTP requests
- Extracts current OpenTelemetry span context using `TraceContextUtils`
- Adds `traceparent` header to all HTTP responses
- Header follows W3C Trace Context standard format

### 3. Log Correlation

- Log4j2 OpenTelemetry appender automatically correlates logs with OpenTelemetry traces
- Logs include `trace_id` and `span_id` in MDC variables
- Console pattern displays trace context in log output

### 4. Error Response Integration

- `ErrorView` includes trace information in error response JSON bodies
- Enables trace correlation even in error scenarios
- Uses same `TraceContextUtils.formatTraceParent()` method

## Trace Context Format

The `traceparent` header follows the W3C Trace Context standard:

**Format**: `00-{trace-id}-{span-id}-{trace-flags}`

**Example**: `00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01`

**Components**:

- `00` - Version (always "00" for current version)
- `{trace-id}` - 32 hex characters (128-bit trace ID)
- `{span-id}` - 16 hex characters (64-bit span ID)
- `{trace-flags}` - 2 hex characters (01 = sampled, 00 = not sampled)

## Important Notes

1. **Default State**: OpenTelemetry SDK is **disabled by default** (`otel.sdk.disabled=true`)
2. **W3C Standard**: Uses W3C Trace Context standard for interoperability
3. **No Custom Correlation IDs**: Relies solely on OpenTelemetry for trace ID generation
4. **Spring Boot Auto-Configuration**: Uses OpenTelemetry Spring Boot starter - no manual SDK setup required
5. **Log Integration**: Log4j2 appender automatically correlates logs with traces
6. **Graceful Degradation**: If OpenTelemetry is disabled or trace context is unavailable, headers may not be set (no
   errors thrown)

## Enabling OpenTelemetry

To enable OpenTelemetry, set the following environment variables:

```bash
OTEL_SDK_DISABLED=false
OTEL_EXPORTER_OTLP_ENDPOINT=http://your-otel-collector:4318
OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf  # or grpc
```

Or configure in `application.properties`:

```properties
otel.sdk.disabled=false
otel.exporter.otlp.endpoint=http://your-otel-collector:4318
otel.exporter.otlp.protocol=http/protobuf
```

## Testing

The project includes tests for OpenTelemetry integration:

**Test File**: `src/test/java/com/epam/aidial/cfg/configuration/logging/CorrelationIdInterceptorTest.java`

**Test Coverage**:

- Verifies `traceparent` header is set when OpenTelemetry context exists
- Verifies header format matches W3C Trace Context standard
- Verifies graceful handling when no OpenTelemetry context is available

## Related Documentation

- Main configuration documentation: `docs/configuration.md` (lines 193-260)
- OpenTelemetry official documentation: https://opentelemetry.io/
- W3C Trace Context specification: https://www.w3.org/TR/trace-context/
