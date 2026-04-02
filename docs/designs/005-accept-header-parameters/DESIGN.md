# Design: Accept Header Media Type Parameter Handling

## Context

The SDMX REST 2.2.0 standard defines media types with optional parameters for data queries:

```
application/vnd.sdmx.data+csv;version=2.0.0;labels=[id|name|both];timeFormat=[original|normalized];keys=[none|obs|series|both]
```

Parameters `labels`, `timeFormat`, and `keys` are defined exclusively for SDMX-CSV. They have no meaning on JSON or XML
media types.

Our consumer (StatGPT Chat App) sends requests like:

```
Accept: application/json; labels=id
```

The proxy returns a **400 error**:

```json
{
  "message": "Unsupported SDMX version in Accept header: application/json; labels=id. Supported versions: 2.1, 3.0.0",
  "status": 400
}
```

This error message is misleading -- the problem is not the SDMX version but the presence of CSV-specific parameters on a
JSON media type. The proxy should:

1. **Fix the crash** -- `extractSdmxVersion()` should not fail on generic media types with extra parameters
2. **Validate parameters** -- CSV-only parameters on non-CSV types should produce a clear, specific 400 error that tells
   the client exactly what is wrong and how to fix it
3. **Support CSV parameters correctly** -- when parameters appear on CSV media types, they should be accepted and used

The consumer must update their request to either drop `labels=id` from JSON requests or switch to a CSV media type.

## Root Cause

`SdmxMediaType.extractSdmxVersion()` uses `MediaType.equals()` to detect generic media types, but Spring's `equals()`
**compares parameters**. Meanwhile, `SdmxMediaType.mapMediaType()` uses the custom `isMatch()` method which only
compares
type and subtype, ignoring parameters. This inconsistency causes the failure.

**File**: `sdmx-proxy/src/main/java/com/epam/sdmxproxy/common/data/SdmxMediaType.java`

When `application/json; labels=id` arrives:

1. `mapMediaType()` (line 58-83) -- uses `isMatch()` (type+subtype only) -- **works fine**, matches
   `MediaType.APPLICATION_JSON` in `JSON_MEDIA_TYPES` set
2. `extractSdmxVersion()` (line 113-194) -- at line 128, uses `MediaType.APPLICATION_JSON.equals(accept)` -- **fails**
   because `accept` has a `labels=id` parameter that `APPLICATION_JSON` doesn't have
3. `hasGenericMediaType` stays `false`, no SDMX-specific type is found either
4. Falls through to line 191 -- throws `UnsupportedSdmxVersionException` with a misleading message about versions

Additionally, the generic media type check at line 128 only recognizes `APPLICATION_JSON` and `APPLICATION_XML`. Generic
CSV types (`text/csv`, `application/csv`) and `text/json` are non-SDMX types that are handled by `mapMediaType()` but
would also fail in `extractSdmxVersion()` if no SDMX-specific type is present in the Accept header.

## Current Code (lines 127-131 of `extractSdmxVersion()`)

```java
if(subtype !=null&&!subtype.

contains("sdmx")){
        if(MediaType.APPLICATION_JSON.

equals(accept) ||MediaType.APPLICATION_XML.

equals(accept)){
hasGenericMediaType =true;
        }
        continue;
        }
```

## Implementation Plan

### Step 1: Fix generic media type detection in `extractSdmxVersion()`

**File**: `sdmx-proxy/src/main/java/com/epam/sdmxproxy/common/data/SdmxMediaType.java`

Replace the `equals()` check with `isMatch()` and reuse the existing media type sets to cover all recognized generic
types:

```java
// Before (lines 127-131):
if(subtype !=null&&!subtype.

contains("sdmx")){
        if(MediaType.APPLICATION_JSON.

equals(accept) ||MediaType.APPLICATION_XML.

equals(accept)){
hasGenericMediaType =true;
        }
        continue;
        }

        // After:
        if(subtype !=null&&!subtype.

contains("sdmx")){
        // Only generic (non-SDMX) types from these sets can match here,
        // since we already checked !subtype.contains("sdmx")
        if(JSON_MEDIA_TYPES.

stream().

anyMatch(j ->

isMatch(j, accept))
        ||XML_MEDIA_TYPES.

stream().

anyMatch(x ->

isMatch(x, accept))
        ||CSV_MEDIA_TYPES.

stream().

anyMatch(c ->

isMatch(c, accept))){
hasGenericMediaType =true;
        }
        continue;
        }
```

This change:

- Uses `isMatch()` instead of `equals()` -- consistent with `mapMediaType()`, ignores parameters
- Reuses the existing `JSON_MEDIA_TYPES`, `XML_MEDIA_TYPES`, `CSV_MEDIA_TYPES` sets -- no new constants
- Covers all previously-unrecognized generic types: `text/csv`, `application/csv`, `text/json`

### Step 2: Remove unreachable `.equals()` checks in `extractSdmxVersion()`

**File**: `sdmx-proxy/src/main/java/com/epam/sdmxproxy/common/data/SdmxMediaType.java`

Lines 142 and 164 use `MediaType.equals()` to match SDMX-specific types:

```java
if(accept.equals(MediaType.valueOf(SDMX_XML_3_0_0_VALUE))){...}   // line 142
        if(accept.

equals(MediaType.valueOf(DRAFT_JSON_2_1_VALUE))){...}     // line 164
```

These are unreachable because the `versionParam` checks on lines 138 and 160 always match first (any media type with
`version=3.0.0` or `version=2.1` is caught before these lines). They also have the same parameter-sensitivity bug as the
generic type check. Remove them to avoid dead code that creates a false sense of coverage.

### Step 3: Handle SDMX CSV without version parameter in `extractSdmxVersion()`

**File**: `sdmx-proxy/src/main/java/com/epam/sdmxproxy/common/data/SdmxMediaType.java`

`application/vnd.sdmx.data+csv; labels=id` (no `version` parameter) enters the SDMX branch (subtype contains "sdmx").
All version checks fail because `versionParam` is null. It's not added to `unsupportedSdmxTypes` (that list only
collects types where version IS present but unrecognized). Falls through to line 191, throws
`UnsupportedSdmxVersionException`. Meanwhile, `mapMediaType()` would upgrade this to `SDMX_CSV_2_0_0_VALUE` (line
75-76).

The same inconsistency applies to any SDMX type without a version param (e.g., `application/vnd.sdmx.data+xml`),
but for CSV this is a realistic scenario since clients may omit the version when specifying only CSV-specific
parameters.

Fix: after all version-specific checks in the SDMX branch, if `versionParam` is null (no version specified), default to
SDMX 3.0 instead of falling through to the error. This is consistent with how `mapMediaType()` handles unversioned
SDMX CSV and how `extractSdmxVersion()` already defaults generic types to SDMX 3.0.

```java
// After all version-specific checks, before the unsupported version collection:
// SDMX type without version parameter -- default to 3.0
// (consistent with mapMediaType() which defaults unversioned SDMX CSV to 2.0.0)
if(versionParam ==null){
        return SdmxVersion.SDMX_3_0;
}

        // If we got here, it's an SDMX type but unsupported version
        unsupportedSdmxTypes.

add(accept.toString());
```

This replaces the existing lines 172-176:

```java
// Before:
// If we got here, it's an SDMX type but unsupported version
// Only add to unsupported list if version parameter is present but not recognized
if(versionParam !=null){
        unsupportedSdmxTypes.

add(accept.toString());
        }
```

The behavior change: instead of silently skipping SDMX types without version (letting them fall through to the final
error), we now explicitly return SDMX_3_0. The unsupported version collection is simplified since we've already handled
the null case.

### Step 4: Validate CSV-specific parameters are only used with CSV media types

**File**: `sdmx-proxy/src/main/java/com/epam/sdmxproxy/common/data/SdmxMediaType.java`

The SDMX standard defines `labels`, `timeFormat`, and `keys` as **CSV-only** parameters. If a client sends these on a
JSON or XML media type (e.g., `application/json; labels=id`), the proxy must reject the request with a clear 400 error.
Silently ignoring these parameters would mislead clients into thinking they are receiving parameterized output similar
to
CSV.

**New file**: `sdmx-proxy/src/main/java/com/epam/sdmxproxy/exception/UnsupportedMediaTypeParameterException.java`

```java
package com.epam.sdmxproxy.exception;

public class UnsupportedMediaTypeParameterException extends IllegalArgumentException {
    public UnsupportedMediaTypeParameterException(String message) {
        super(message);
    }
}
```

Follows the same pattern as `UnsupportedSdmxVersionException` and `UnsupportedContextException` -- extends
`IllegalArgumentException`, so it is caught by the existing `GlobalExceptionHandler.handleIllegalArgumentException()`
and returned as a 400 Bad Request. A specific exception class makes error handling explicit and allows adding a
dedicated
`@ExceptionHandler` later if needed.

**In `SdmxMediaType.java`**:

```java
private static final Set<String> CSV_ONLY_PARAMETERS = Set.of("labels", "timeformat", "keys");

/**
 * Validates that CSV-specific parameters (labels, timeFormat, keys) are only present
 * on CSV media types. Throws UnsupportedMediaTypeParameterException if these parameters
 * appear on JSON or XML media types.
 */
public static void validateCsvParameters(MediaType mediaType) {
    if (CSV_MEDIA_TYPES.stream().anyMatch(csv -> isMatch(csv, mediaType))) {
        return; // CSV type -- parameters are valid
    }

    List<String> invalidParams = mediaType.getParameters().keySet().stream()
            .filter(key -> CSV_ONLY_PARAMETERS.contains(key.toLowerCase()))
            .toList();

    if (!invalidParams.isEmpty()) {
        throw new UnsupportedMediaTypeParameterException(
                String.format("Parameters %s are only supported for CSV media types. "
                                + "Received media type: %s",
                        invalidParams, mediaType));
    }
}
```

Call site in `parseMediaType()`:

```java
public static MediaTypeParseResult parseMediaType(String acceptHeader) {
    MediaType mediaType = mapMediaType(acceptHeader);
    SdmxVersion sdmxVersion = extractSdmxVersion(acceptHeader);
    validateCsvParameters(mediaType);

    return MediaTypeParseResult.builder()
            .mediaType(mediaType)
            .sdmxVersion(sdmxVersion)
            .build();
}
```

**Why `parseMediaType()` and not `mapMediaType()`**: `mapMediaType()` is a pure format-mapping function. Validation is a
separate concern and belongs at the point where we finalize the parsed result. `parseMediaType()` is the single entry
point called by `QueryTranslatorImpl` and is the right place to compose mapping + version extraction + validation.

**Why a dedicated exception**: Follows existing codebase pattern (`UnsupportedSdmxVersionException`,
`UnsupportedContextException`). Extends `IllegalArgumentException`, so it is caught by the existing
`GlobalExceptionHandler.handleIllegalArgumentException()` and returned as a 400 Bad Request.

**Note on parameter case**: Spring's `MediaType` lowercases parameter names per RFC 2045, so comparing against lowercase
keys in `CSV_ONLY_PARAMETERS` is correct. However, `timeFormat` becomes `timeformat` after parsing. The set uses
lowercase values to match this behavior.

### Step 5: Add unit tests

**File**: `sdmx-proxy/src/test/java/com/epam/sdmxproxy/common/data/SdmxMediaTypeTest.java`

**`extractSdmxVersion` tests (fix for generic types with params):**

| Accept Header                                                                                   | Expected Version | Rationale                                                                                      |
|-------------------------------------------------------------------------------------------------|------------------|------------------------------------------------------------------------------------------------|
| `application/json; labels=id`                                                                   | SDMX_3_0         | Generic JSON with extra param -- version extraction passes (param validation is separate step) |
| `application/xml; labels=id`                                                                    | SDMX_3_0         | Generic XML with extra param                                                                   |
| `application/json; version=2.0.0`                                                               | SDMX_3_0         | Generic type with `version` param -- `version` has no meaning on generic types, ignored        |
| `text/csv`                                                                                      | SDMX_3_0         | Generic CSV type -- was broken before fix                                                      |
| `application/csv`                                                                               | SDMX_3_0         | Generic CSV type -- was broken before fix                                                      |
| `text/csv; labels=both; keys=series`                                                            | SDMX_3_0         | Generic CSV with CSV-specific params                                                           |
| `application/vnd.sdmx.data+csv; version=2.0.0; labels=name; timeFormat=normalized; keys=series` | SDMX_3_0         | Full SDMX CSV with all params -- should already work                                           |

**`validateCsvParameters` tests:**

| Accept Header                                                                                   | Expected                                        | Rationale                                                          |
|-------------------------------------------------------------------------------------------------|-------------------------------------------------|--------------------------------------------------------------------|
| `application/json; labels=id`                                                                   | Throws `UnsupportedMediaTypeParameterException` | CSV param on JSON type                                             |
| `application/xml; timeformat=normalized`                                                        | Throws `UnsupportedMediaTypeParameterException` | CSV param on XML type                                              |
| `application/vnd.sdmx.data+json; version=2.0.0; keys=series`                                    | Throws `UnsupportedMediaTypeParameterException` | CSV param on SDMX JSON type                                        |
| `application/vnd.sdmx.data+xml; version=3.0.0; labels=both`                                     | Throws `UnsupportedMediaTypeParameterException` | CSV param on SDMX XML type                                         |
| `application/json`                                                                              | No exception                                    | No CSV params, plain generic JSON                                  |
| `application/json; version=2.0.0`                                                               | No exception                                    | Non-CSV param on generic type -- `version` is not a CSV-only param |
| `application/vnd.sdmx.data+json; version=2.0.0; foo=bar`                                        | No exception                                    | Unknown param -- not a CSV-only param, tolerated                   |
| `application/vnd.sdmx.data+csv; version=2.0.0; labels=name; timeFormat=normalized; keys=series` | No exception                                    | CSV type with all valid params                                     |
| `text/csv; labels=both`                                                                         | No exception                                    | Generic CSV type with valid param                                  |
| `application/csv; keys=obs`                                                                     | No exception                                    | Generic CSV type with valid param                                  |

**`parseMediaType` integration tests:**

| Accept Header                                               | Expected                                        | Rationale                                                          |
|-------------------------------------------------------------|-------------------------------------------------|--------------------------------------------------------------------|
| `application/json; labels=id`                               | Throws `UnsupportedMediaTypeParameterException` | End-to-end: version extraction passes but param validation rejects |
| `application/vnd.sdmx.data+csv; version=2.0.0; labels=name` | Returns SDMX_3_0 + MediaType with params        | End-to-end: valid CSV with params works                            |
| `application/json`                                          | Returns SDMX_3_0 + MediaType                    | End-to-end: plain JSON works                                       |

**`mapMediaType` tests:**

| Accept Header                                                                      | Expected Behavior                           | Rationale                                       |
|------------------------------------------------------------------------------------|---------------------------------------------|-------------------------------------------------|
| `application/vnd.sdmx.data+csv; version=2.0.0; labels=name; timeFormat=normalized` | Returns MediaType with all params preserved | CSV params available for `buildCsvDataFormat()` |

**`extractSdmxVersion` tests (SDMX types without version param -- Step 3 fix):**

| Accept Header                              | Expected Version | Rationale                                                                                     |
|--------------------------------------------|------------------|-----------------------------------------------------------------------------------------------|
| `application/vnd.sdmx.data+csv; labels=id` | SDMX_3_0         | SDMX CSV without version param -- defaults to 3.0                                             |
| `application/vnd.sdmx.data+csv`            | SDMX_3_0         | Unversioned SDMX CSV -- defaults to 3.0 (consistent with `mapMediaType()` upgrading to 2.0.0) |
| `application/vnd.sdmx.data+xml`            | SDMX_3_0         | Unversioned SDMX XML -- defaults to 3.0                                                       |
| `application/vnd.sdmx.data+json`           | SDMX_3_0         | Unversioned SDMX JSON -- defaults to 3.0                                                      |

## What Does NOT Need to Change

- **`mapMediaType()`** -- already uses `isMatch()`, correctly handles parameters
- **`StreamingDataConversionService.buildCsvDataFormat()`** -- already extracts `labels`/`timeFormat`/`keys` from
  MediaType parameters (lines 199-208)
- **`DataQuery30Controller`** -- generic; passes Accept header through
- **`QueryTranslatorImpl`** -- calls `parseMediaType()` which calls both `mapMediaType()` and `extractSdmxVersion()`;
  fix in `extractSdmxVersion()` + new validation in `parseMediaType()` is sufficient
- **`AdapterRouterImpl`** -- no involvement in Accept header parsing
- **`GlobalExceptionHandler`** -- already handles `IllegalArgumentException` (and subclasses) as 400 Bad Request

## Edge Cases

1. **Unknown parameters on non-CSV types** (e.g., `application/vnd.sdmx.data+json; version=2.0.0; foo=bar`) -- `foo` is
   not in `CSV_ONLY_PARAMETERS`, so validation passes. The parameter survives in the MediaType object but is ignored
   downstream. This is intentionally tolerant -- we only reject parameters that would mislead the client about output
   format behavior.

2. **CSV params on CSV types** (e.g., `text/csv; labels=both; keys=series`) -- validation passes because the media type
   matches `CSV_MEDIA_TYPES`. Parameters are extracted by `buildCsvDataFormat()` at conversion time.

3. **Invalid CSV param values** (e.g., `labels=invalid`) -- not validated here. `buildCsvDataFormat()` in
   `StreamingDataConversionService` passes the raw string to `SdmxCsvDataFormat` constructor. Invalid values result in
   default behavior (sdmx-core's `CSV_LABEL.fromParam()` falls back gracefully). This is acceptable -- the SDMX standard
   doesn't mandate strict validation of parameter values.

4. **Unknown generic types** (e.g., `application/unknown; labels=id`) -- subtype doesn't contain "sdmx" and doesn't
   match any set. `hasGenericMediaType` stays false. Falls through to the final exception in `extractSdmxVersion()`.
   Validation in step 4 is never reached. This is correct -- we reject the type before validating params.

5. **`*/*` Accept header** -- handled upstream in `DataQuery30Controller` (line 65-67), replaced with
   `SDMX_JSON_2_0_0_VALUE` before reaching `SdmxMediaType`. Not affected by this change.

6. **Multiple media types in Accept** (e.g.,
   `application/json; labels=id, application/vnd.sdmx.data+csv; version=2.0.0`) --
   `mapMediaType()` returns the first match. If the first match is JSON with `labels`, validation will reject it. The
   client should not mix CSV params with non-CSV types in a multi-type Accept header.

7. **SDMX types without version param** (`application/vnd.sdmx.data+csv; labels=id`,
   `application/vnd.sdmx.data+xml`, etc.) -- previously an existing bug where `extractSdmxVersion()` would throw while
   `mapMediaType()` handled it fine. Fixed in Step 3: unversioned SDMX types now default to SDMX 3.0, consistent with
   how `mapMediaType()` defaults unversioned SDMX CSV to 2.0.0 and how generic types already default to SDMX 3.0.

## Verification

1. **Unit tests**: `./gradlew :sdmx-proxy:test` -- new + existing tests in `SdmxMediaTypeTest`
2. **Manual test** with the exact failing request:
   ```
   curl -H "Accept: application/json; labels=id" \
     http://localhost:8050/api/sdmx/3.0/data/dataflow/IMF.RES/WEO/9.0.0/USA.NGDP_D.*
   ```
   Should return **400** with message:
   `Parameters [labels] are only supported for CSV media types. Received media type: application/json;labels=id`
3. **Manual test** with valid CSV params:
   ```
   curl -H "Accept: application/vnd.sdmx.data+csv;version=2.0.0;labels=name;timeFormat=normalized" \
     http://localhost:8050/api/sdmx/3.0/data/dataflow/IMF.RES/WEO/9.0.0/USA.NGDP_D.*
   ```
   Should return **200** with CSV data using name labels and normalized time format
4. **Manual test** with plain JSON (no params):
   ```
   curl -H "Accept: application/json" \
     http://localhost:8050/api/sdmx/3.0/data/dataflow/IMF.RES/WEO/9.0.0/USA.NGDP_D.*
   ```
   Should return **200** with JSON data (regression test -- must still work)
5. **E2E tests**: `./gradlew :sdmx-proxy-e2e:test` -- verify no regressions

## Files Modified (Summary)

| File                                          | Change                                                                                                                                                                                                                |
|-----------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `UnsupportedMediaTypeParameterException.java` | New exception class in `exception/` package, extends `IllegalArgumentException`                                                                                                                                       |
| `SdmxMediaType.java`                          | Replace `equals()` with `isMatch()` in `extractSdmxVersion()`, remove dead `.equals()` checks on lines 142/164, default unversioned SDMX types to 3.0, add `validateCsvParameters()`, call it from `parseMediaType()` |
| `SdmxMediaTypeTest.java`                      | Add ~24 test cases for parameterized Accept headers, unversioned SDMX types, and CSV param validation                                                                                                                 |

## SDMX Standard References

- SDMX REST 2.2.0 data media types: `sdmx-rest-2.2.0/doc/data.md` (lines 74-84)
- SDMX REST content negotiation: `sdmx-rest-2.2.0/doc/content_negotiation.md`
- Note: the standard's prose on `data.md` line 84 mentions "two parameters" (`label` and `timeFormat`) but the media
  type definition on line 80 also includes `keys`. The prose is outdated relative to its own media type definition. This
  design covers all three parameters.
