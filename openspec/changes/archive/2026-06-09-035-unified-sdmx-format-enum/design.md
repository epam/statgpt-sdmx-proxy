# Design 035: Unified `SdmxFormat` enum (collapse `ReturnFormat` + `SdmxMediaType`)

**Status:** proposed (2026-06-09). Implemented 2026-06-09.

> **Post-implementation note (rename).** During implementation the negotiation helper was renamed
> from `SdmxMediaType` to **`SdmxMediaTypeResolver`** to remove the one-letter ambiguity with the
> new `SdmxMediaTypes` constants holder. Throughout this document `SdmxMediaType` refers to that
> helper; in the code it is now `SdmxMediaTypeResolver` (same responsibilities and method surface).
> The test class is correspondingly `SdmxMediaTypeResolverTest`.

## Context

Today the proxy describes "an SDMX wire format" in three different places, with three
different naming schemes and no single source of truth:

1. **`ReturnFormat`** -- an enum in the `sdmx-proxy-config` module
   (`sdmx-proxy-config/src/main/java/com/epam/sdmxproxy/configuration/data/ReturnFormat.java`).
   Nine constants, each carrying a single `contentType` string. This is the type that
   appears in registry configuration JSON (`supportedFormats`, `defaultFormat`) and drives
   the conversion `switch` statements.
2. **`SdmxMediaType`** -- a `@UtilityClass` in the main module
   (`sdmx-proxy/src/main/java/com/epam/sdmxproxy/common/data/SdmxMediaType.java`). ~12
   media-type string constants plus three `Set<MediaType>` groupings (`JSON_MEDIA_TYPES`,
   `XML_MEDIA_TYPES`, `CSV_MEDIA_TYPES`) and all of the HTTP content-negotiation logic
   (`mapMediaType`, `parseMediaType`, `extractSdmxVersion`, `isJson`/`isXmlV21`/`isCsvMediaType`,
   CSV parameter validation).
3. **`OutputFormatConstants`** -- a third constants class
   (`sdmx-proxy/src/main/java/com/epam/sdmxproxy/common/data/OutputFormatConstants.java`)
   holding yet another copy of the CSV / draft-JSON media-type strings.

`ReturnFormat` and `SdmxMediaType` describe overlapping concepts -- the same nine wire
formats appear as enum constants in one and as raw strings in the other -- yet they were
grown independently, so:

- the constant **names are inconsistent**: `JSON_1_0_0` (data, but no `DATA` segment) sits
  next to `JSON_DATA_2_0_0`, while `XML_2_1` actually means *structure* XML and lives next
  to `XML_GENERICDATA_2_1` and `XML_STRUCTURE_SPECIFIC_2_1`;
- the **media-type strings are duplicated** across all three classes, with inconsistent
  whitespace (`;version=2.1` vs `; version=2.1`);
- the set of recognised formats is an **implicit allow-list** scattered through `switch`
  defaults, `Set.of(...)` literals, and controller `produces` clauses.

This is technical debt from the original implementation, not a deliberate design. This
change unifies the three into a single canonical enum and a thin negotiation helper.

This is a refactor: SDMX spec compliance is unchanged. The motivation is internal
consistency and a single, complete, well-named format registry. The media-type matrix
itself is grounded in the SDMX REST spec (see SDMX Standard References).

## Problem

Concrete symptoms a maintainer hits today:

- **Naming.** To add or reference a format you must remember that `JSON_1_0_0` is data,
  `XML_2_1` is structure, and `XML_GENERICDATA_2_1` is generic *data*. The scheme is not
  predictable from the constant name.
- **Three places to touch.** Adding a format means editing `ReturnFormat`, possibly
  `SdmxMediaType`'s constant + the relevant `Set<MediaType>`, and possibly
  `OutputFormatConstants`. Miss one and the format is half-wired.
- **No complete picture.** There is no single list of "every SDMX format the proxy knows
  about." The matrix is implicit.
- **Dead code.** `OutputFormatConstants` is referenced by nothing except itself (verified by
  grep) -- it is pure duplication.

## Decisions (agreed before writing)

| # | Decision | Choice |
|---|----------|--------|
| 1 | Architecture, given `ReturnFormat` lives in the Spring-free `sdmx-proxy-config` module while `SdmxMediaType` depends on Spring `MediaType` | **One canonical enum** (`SdmxFormat`, in the config module) **+ a thin negotiation helper** (the slimmed-down `SdmxMediaType`) in the main module. The enum is the single source of truth for format names and content-type strings; the helper maps incoming `Accept` headers, wildcards, and aliases onto the enum. |
| 2 | Completeness of the format matrix | **Enumerate the full SDMX matrix** (all JSON/XML/CSV data/structure/metadata/schema formats from the REST spec) as enum members. **No `supported` flag** -- see "What we accept" below; the gate already exists at the controller level. |
| 3 | Constant naming | **Rename** to one consistent scheme `TYPE_KIND[_SUBKIND]_VERSION` and **migrate the config JSON files in this change** (no `@JsonAlias` back-compat -- all configs live in this repo). Long, explicit names are fine (Java convention). |
| 4 | Category dimension | **No `category` enum field.** Group members visually with comments only. No category-based validation. The enum carries `contentType` (load-bearing) and `sdmxVersion` (intrinsic metadata -- kept although not consumed directly today; it is a stable, parity-tested property of each format, see Step 3). |

## Current architecture

```
HTTP request (Accept header)
        |
        v
Spring content negotiation against the controller @GetMapping(produces = {...}) allow-list
        |
        |-- Accept matches nothing in produces --> HttpMediaTypeNotAcceptableException
        |                                          --> GlobalExceptionHandler --> HTTP 400 "Media type not acceptable"
        v  (Accept is in the allow-list)
Controller body  --(Accept string)-->  SdmxMediaType.parseMediaType(accept)
        |                              |-- mapMediaType()      -> Spring MediaType
        |                              |-- extractSdmxVersion() -> SdmxVersion (also 400s on unsupported SDMX version)
        |                              '-- validateCsvParameters()
        v
QueryTranslatorImpl
   determine{Data,Structure,Availability}ReturnFormat
        |  uses FormatSupportChecker.formatMatches(ReturnFormat, MediaType)
        |  picks a ReturnFormat from EndpointConfiguration.supportedFormats / defaultFormat
        v
Translated{Data,Structure,Availability}Query { ReturnFormat returnFormat; MediaType contentType; }
        v
AdapterRouterImpl  -- bypass? (FormatSupportChecker) : convert
        v
StreamingDataConversionService.convert(... sourceFormat=ReturnFormat, targetMediaType ...)
   1. top-level dispatch on TARGET media type:
        isJsonMediaType / isCsvMediaType / isXmlMediaType  (no match -> throw UnsupportedConversionException, 501)
   2. source reader chosen by switch (ReturnFormat):
        getDataReaderFactory(sourceFormat)  -- default -> sdmxMLDataReaderFactory (no throw)
        getSdmxDataFormat(sourceFormat)     -- default -> throw UnsupportedConversionException (501)
   (Structure/Availability conversion services have their own analogous throws.)
```

Two gates exist today, at different levels:

1. **Controller `produces` list** (fast-fail, framework-level). The `@GetMapping(produces =
   {...})` clause on each API interface is the real allow-list of acceptable `Accept`
   headers. A request whose `Accept` matches nothing there is rejected by Spring with
   `HttpMediaTypeNotAcceptableException` **before the controller body runs** -- it never
   reaches the translator or the conversion switch. `GlobalExceptionHandler`
   (`sdmx-proxy/src/main/java/com/epam/sdmxproxy/web/exception/GlobalExceptionHandler.java:141-146`)
   maps it to **HTTP 400 "Media type not acceptable"** (verified).
2. **Conversion default** (deep, internal). `StreamingDataConversionService.convert` throws
   `UnsupportedConversionException` (501 -- it `extends NotImplementedException`, mapped to
   501 in `GlobalExceptionHandler`) when the target media type matches no branch, and
   `getSdmxDataFormat`'s `switch (ReturnFormat)` `default` throws for an unconvertible source
   format. (`getDataReaderFactory`'s `default` does *not* throw -- it falls back to
   `sdmxMLDataReaderFactory`.) With gate 1 in place, these are belt-and-suspenders paths for
   internal/config errors, not the primary client-facing gate.

## Solution

Introduce a single canonical enum **`SdmxFormat`** in
`com.epam.sdmxproxy.configuration.data` (a rename + expansion of `ReturnFormat`, not a new
sibling). Each member carries:

- `contentType` -- the canonical SDMX media-type string. For the nine existing formats the
  string is preserved **byte-for-byte** from today's `ReturnFormat` (mixed whitespace and
  all), because it is sent verbatim as the outbound `Accept` header to registries -- see Edge
  Cases #6. New matrix members use the spec form.
- `sdmxVersion` -- the `SdmxVersion` this format belongs to. Intrinsic metadata, parity-tested
  against `extractSdmxVersion`; it does **not** rewrite that method (see Step 3). Kept although
  not consumed directly today -- it is a stable, intrinsic property of each format and a likely
  lever for future simplifications.

No `supported` flag. The reasons (discussed and agreed):

- The "can the client request this?" gate is the controller `produces` list (gate 1 above).
  To *not* accept a format, simply leave it out of `produces` -> the client gets a clean
  400 "not acceptable", never a 501.
- The "can we actually convert this?" truth already lives structurally in the conversion
  `switch` statements (a missing arm = `UnsupportedConversionException`). A boolean field
  would just duplicate that truth into a second place that can drift (someone wires a
  reader, forgets to flip the flag -> the enum lies).
- A single `supported` boolean is also ambiguous (read-from-registry vs write-to-client are
  different capabilities, e.g. `XML_DATA_3_0_0` is writable but not readable today).
  Splitting into `readable`/`writable` doubles the drift surface for no enforcement gain.

So the full matrix is enumerated for naming/recognition completeness; *acceptance* is
governed by the controller `produces` lists and *convertibility* by the switch arms.
Members are grouped by kind with comments so a reader can see the catalogue at a glance.

`SdmxMediaType` is slimmed to a **negotiation helper**: it keeps the `Accept`-header logic
(`mapMediaType`, `parseMediaType`, `extractSdmxVersion`, CSV-param validation,
wildcard/alias handling) but derives its format knowledge from `SdmxFormat.values()` instead
of private constant lists. `OutputFormatConstants` is deleted.

Why a helper survives: the config module (`sdmx-proxy-config`) deliberately has **no Spring
dependency**, so it cannot hold Spring `MediaType` parsing or `Accept`-header negotiation.
The enum holds *strings*; the Spring-aware negotiation stays in the main module.

### The annotation-constant constraint (important)

Java annotation values must be compile-time `String` constants. Controller `produces` lists
and `@Content(mediaType = ...)` therefore **cannot** call `SdmxFormat.X.getContentType()` --
they need `String` constants. To keep a single literal per content type rather than a
drift-prone copy:

- Put the canonical media-type literals in a small constants holder
  **`SdmxMediaTypes`** in the config module (`com.epam.sdmxproxy.configuration.data`),
  e.g. `public static final String DATA_JSON_2_0_0 = "application/vnd.sdmx.data+json;version=2.0.0";`.
- `SdmxFormat`'s constructor references those constants
  (`JSON_DATA_2_0_0(SdmxMediaTypes.DATA_JSON_2_0_0, SdmxVersion.SDMX_3_0)`). The holder must
  be a **separate class**, not constants inside `SdmxFormat` itself: enum constants are
  initialised before the enum's own static fields, so a constant referencing a `static
  final` field of the same enum is a forward-reference error.
- Controllers' `produces` / `@Content` reference the same `SdmxMediaTypes.*` constants
  (the main module depends on the config module). One literal, used by enum and annotations.

Lower-touch alternative if the holder feels heavy: keep the `*_VALUE` string constants where
they are and add a parity unit test asserting each equals some `SdmxFormat.getContentType()`.
The holder approach is recommended because it removes the duplication entirely rather than
guarding it.

### Naming scheme

`TYPE_KIND[_SUBKIND]_VERSION`, format family first (matches the already-good
`JSON_DATA_2_0_0` / `JSON_STRUCTURE_2_0_0` names):

- `TYPE` in {`JSON`, `XML`, `CSV`}
- `KIND` in {`DATA`, `STRUCTURE`, `METADATA`, `SCHEMA`}
- `SUBKIND` (XML only) e.g. `GENERIC`, `STRUCTURE_SPECIFIC`, `GENERIC_TIME_SERIES`,
  `STRUCTURE_SPECIFIC_TIME_SERIES`
- `VERSION` matches the spec version token (`2_1`, `3_0_0`, `1_0_0`, `2_0_0`)

Long names are accepted (decision 3): `XML_STRUCTURE_SPECIFIC_DATA_2_1` over a terse form.

### Rename map (existing 9 constants)

Rows are in source declaration order (`ReturnFormat.java:8-17`); a rename map is
order-independent, but matching the source avoids reader doubt.

| Old `ReturnFormat`            | New `SdmxFormat`                  |
|-------------------------------|----------------------------------|
| `JSON_1_0_0`                  | `JSON_DATA_1_0_0`                |
| `JSON_2_1_DRAFT`              | `JSON_DATA_DRAFT_2_1`            |
| `JSON_DATA_2_0_0`             | `JSON_DATA_2_0_0` (unchanged)    |
| `JSON_STRUCTURE_2_0_0`        | `JSON_STRUCTURE_2_0_0` (unchanged) |
| `XML_2_1`                     | `XML_STRUCTURE_2_1`              |
| `XML_GENERICDATA_2_1`         | `XML_GENERIC_DATA_2_1`           |
| `XML_STRUCTURE_SPECIFIC_2_1`  | `XML_STRUCTURE_SPECIFIC_DATA_2_1`|
| `CSV_DATA_1_0_0`              | `CSV_DATA_1_0_0` (unchanged)     |
| `CSV_DATA_2_0_0`              | `CSV_DATA_2_0_0` (unchanged)     |

### New members (completeness)

From the SDMX REST spec content-type registry (see SDMX Standard References):

| New `SdmxFormat`                                | Content type                                                       |
|-------------------------------------------------|--------------------------------------------------------------------|
| `XML_DATA_3_0_0`                                | `application/vnd.sdmx.data+xml;version=3.0.0`                       |
| `XML_GENERIC_TIME_SERIES_DATA_2_1`              | `application/vnd.sdmx.generictimeseriesdata+xml;version=2.1`       |
| `XML_STRUCTURE_SPECIFIC_TIME_SERIES_DATA_2_1`   | `application/vnd.sdmx.structurespecifictimeseriesdata+xml;version=2.1` |
| `XML_STRUCTURE_3_0_0`                           | `application/vnd.sdmx.structure+xml;version=3.0.0`                  |
| `JSON_STRUCTURE_1_0_0`                          | `application/vnd.sdmx.structure+json;version=1.0.0`                 |
| `XML_SCHEMA_3_0_0`                              | `application/vnd.sdmx.schema+xml;version=3.0.0`                     |
| `XML_SCHEMA_2_1`                                | `application/vnd.sdmx.schema+xml;version=2.1`                       |
| `JSON_METADATA_2_0_0`                           | `application/vnd.sdmx.metadata+json;version=2.0.0`                  |
| `XML_METADATA_3_0_0`                            | `application/vnd.sdmx.metadata+xml;version=3.0.0`                   |
| `CSV_METADATA_1_0_0`                            | `application/vnd.sdmx.metadata+csv;version=1.0.0`                   |
| `XML_GENERIC_METADATA_2_1`                      | `application/vnd.sdmx.genericmetadata+xml;version=2.1`             |
| `XML_STRUCTURE_SPECIFIC_METADATA_2_1`           | `application/vnd.sdmx.structurespecificmetadata+xml;version=2.1`   |

These members are listed for naming/recognition completeness. With one exception they are
**not** added to any controller `produces` list in this change, so requesting one yields the
existing 400 "not acceptable", and wiring conversion for any of them is separate, future work.

**Exception:** `XML_DATA_3_0_0` (`application/vnd.sdmx.data+xml;version=3.0.0`) is *not* a new
capability -- this content type is already accepted and converted today (it is
`SdmxMediaType.SDMX_XML_3_0_0_VALUE`, in `DataQuery30Api`'s `produces`, and the XML data
output target via `processAsXml` -> `COMPACT_3_0`). The enum member formalises that existing
type; it is not recognition-only. See Edge Cases #4.

## Implementation Plan

### Step 1: Add the `SdmxMediaTypes` constants holder

**File (new):** `sdmx-proxy-config/src/main/java/com/epam/sdmxproxy/configuration/data/SdmxMediaTypes.java`

```java
package com.epam.sdmxproxy.configuration.data;

/**
 * Canonical SDMX media-type strings. Single source for the literals used by both
 * {@link SdmxFormat} and the controllers' {@code produces} / {@code @Content} annotations
 * (which require compile-time String constants and cannot call enum getters).
 */
public final class SdmxMediaTypes {

    // Existing-format literals are copied BYTE-FOR-BYTE from today's ReturnFormat (note the
    // mixed whitespace, e.g. the space in "; version=2.0.0") -- see Edge Cases #6. Do NOT
    // normalise. New matrix members use the spec form.
    public static final String DATA_JSON_1_0_0 = "application/vnd.sdmx.data+json;version=1.0.0";
    public static final String DATA_JSON_2_0_0 = "application/vnd.sdmx.data+json; version=2.0.0";
    public static final String DATA_JSON_DRAFT_2_1 = "application/vnd.sdmx.draft-sdmx-json+json; version=2.1";
    public static final String DATA_CSV_1_0_0 = "application/vnd.sdmx.data+csv;version=1.0.0";
    public static final String DATA_CSV_2_0_0 = "application/vnd.sdmx.data+csv;version=2.0.0";
    public static final String DATA_XML_GENERIC_2_1 = "application/vnd.sdmx.genericdata+xml;version=2.1";
    public static final String DATA_XML_STRUCTURE_SPECIFIC_2_1 = "application/vnd.sdmx.structurespecificdata+xml;version=2.1";
    public static final String STRUCTURE_JSON_2_0_0 = "application/vnd.sdmx.structure+json; version=2.0.0";
    public static final String STRUCTURE_XML_2_1 = "application/vnd.sdmx.structure+xml;version=2.1";
    public static final String DATA_XML_3_0_0 = "application/vnd.sdmx.data+xml;version=3.0.0"; // new
    // ... remaining new structure / schema / metadata literals from the matrix above

    private SdmxMediaTypes() {
    }
}
```

**Key points:**
- Separate class (not nested in `SdmxFormat`) to avoid the enum static-init forward-reference.
- Plain constants holder, no Spring -- safe for the config module.
- `SdmxMediaTypes` holds **SDMX vendor types only** (the `application/vnd.sdmx.*` strings).
  The **generic** media types (`*/*`, `text/json`, `application/csv`, `text/csv`) are **not**
  enum members and stay as `public static final String` constants on `SdmxMediaType` in the
  main module (`ANY`, `TEXT_JSON_VALUE`, `APPLICATION_CSV_VALUE`, `TEXT_CSV_VALUE`). They are
  **not** moved to Spring's `MediaType`: Spring has `APPLICATION_JSON_VALUE`,
  `APPLICATION_XML_VALUE`, `ALL_VALUE`, but **no** `text/csv` / `application/csv` constant, so
  those cannot "stay on Spring constants". See Step 3 (which constants survive) and Step 6
  (which annotation references change vs stay).

### Step 2: Rename and expand `ReturnFormat` -> `SdmxFormat`

**File:** `sdmx-proxy-config/.../configuration/data/ReturnFormat.java` -> rename to `SdmxFormat.java`

```java
package com.epam.sdmxproxy.configuration.data;

import lombok.Getter;

@Getter
public enum SdmxFormat {

    // ===== DATA =====
    JSON_DATA_1_0_0(SdmxMediaTypes.DATA_JSON_1_0_0, SdmxVersion.SDMX_2_1),
    JSON_DATA_2_0_0(SdmxMediaTypes.DATA_JSON_2_0_0, SdmxVersion.SDMX_3_0),
    JSON_DATA_DRAFT_2_1(SdmxMediaTypes.DATA_JSON_DRAFT_2_1, SdmxVersion.SDMX_2_1),
    CSV_DATA_1_0_0(SdmxMediaTypes.DATA_CSV_1_0_0, SdmxVersion.SDMX_2_1),
    CSV_DATA_2_0_0(SdmxMediaTypes.DATA_CSV_2_0_0, SdmxVersion.SDMX_3_0),
    XML_DATA_3_0_0(SdmxMediaTypes.DATA_XML_3_0_0, SdmxVersion.SDMX_3_0),
    XML_GENERIC_DATA_2_1(SdmxMediaTypes.DATA_XML_GENERIC_2_1, SdmxVersion.SDMX_2_1),
    XML_STRUCTURE_SPECIFIC_DATA_2_1(SdmxMediaTypes.DATA_XML_STRUCTURE_SPECIFIC_2_1, SdmxVersion.SDMX_2_1),
    XML_GENERIC_TIME_SERIES_DATA_2_1(/* ... */ SdmxVersion.SDMX_2_1),
    XML_STRUCTURE_SPECIFIC_TIME_SERIES_DATA_2_1(/* ... */ SdmxVersion.SDMX_2_1),

    // ===== STRUCTURE =====
    JSON_STRUCTURE_2_0_0(/* ... */ SdmxVersion.SDMX_3_0),
    JSON_STRUCTURE_1_0_0(/* ... */ SdmxVersion.SDMX_2_1),
    XML_STRUCTURE_2_1(/* ... */ SdmxVersion.SDMX_2_1),
    XML_STRUCTURE_3_0_0(/* ... */ SdmxVersion.SDMX_3_0),

    // ===== SCHEMA =====
    XML_SCHEMA_2_1(/* ... */ SdmxVersion.SDMX_2_1),
    XML_SCHEMA_3_0_0(/* ... */ SdmxVersion.SDMX_3_0),

    // ===== METADATA =====
    JSON_METADATA_2_0_0(/* ... */ SdmxVersion.SDMX_3_0),
    XML_METADATA_3_0_0(/* ... */ SdmxVersion.SDMX_3_0),
    CSV_METADATA_1_0_0(/* ... */ SdmxVersion.SDMX_2_1),
    XML_GENERIC_METADATA_2_1(/* ... */ SdmxVersion.SDMX_2_1),
    XML_STRUCTURE_SPECIFIC_METADATA_2_1(/* ... */ SdmxVersion.SDMX_2_1);

    private final String contentType;
    private final SdmxVersion sdmxVersion;

    SdmxFormat(String contentType, SdmxVersion sdmxVersion) {
        this.contentType = contentType;
        this.sdmxVersion = sdmxVersion;
    }
}
```

**Key points:**
- IDE rename-refactor of the type (`ReturnFormat` -> `SdmxFormat`) first, so the ~66
  `.java` files that reference `ReturnFormat` across the three modules' `src` update
  mechanically; then apply the per-constant renames.
- `@Getter` generates `getContentType()`, `getSdmxVersion()`.
- Decision 4: no `category` field; the `// ===== KIND =====` comments are the only grouping.
- **Mandatory (CLAUDE.md):** update `sdmx-proxy-config/README.md` in the same change. It
  documents the enum in two places: the `supportedFormats`/`defaultFormat` schema rows
  (`sdmx-proxy-config/README.md:60-61`) and a full `### Enum: ReturnFormat` value table
  (`sdmx-proxy-config/README.md:179-194`) listing all nine old names + media types. Rename the
  heading/type to `SdmxFormat`, apply the rename map, and add the new matrix members.

### Step 3: Slim `SdmxMediaType` to a negotiation helper backed by the enum

**File:** `sdmx-proxy/.../common/data/SdmxMediaType.java`

Replace the private constant lists (`JSON_MEDIA_TYPES`, `XML_MEDIA_TYPES`, `CSV_MEDIA_TYPES`)
with derivations from `SdmxFormat.values()`. Keep the public method surface (`isMatch`,
`isJson`, `isXmlV21`, `mapMediaType`, `parseMediaType`, `validateCsvParameters`,
`extractSdmxVersion`) so callers do not change.

**Which constants survive, which are removed:**

- **Remove** the SDMX *vendor*-type `*_VALUE` constants (`SDMX_JSON_1_0_0_VALUE`,
  `SDMX_JSON_2_0_0_VALUE`, `SDMX_XML_3_0_0_VALUE`, `SDMX_CSV_1_0_0_VALUE`,
  `SDMX_CSV_2_0_0_VALUE`, `DRAFT_JSON_2_1_VALUE`, `STRUCTURE_SDMX_XML_2_1_VALUE`,
  `STRUCTURE_SDMX_JSON_2_0_0_VALUE`). Their literal now lives once in `SdmxMediaTypes` and is
  reachable as `SdmxFormat.X.getContentType()`. Annotation references to them are repointed in
  Step 6.
- **Keep** the **generic** constants `ANY` (`*/*`), `TEXT_JSON_VALUE` (`text/json`),
  `APPLICATION_CSV_VALUE` (`application/csv`), `TEXT_CSV_VALUE` (`text/csv`). These are not
  SDMX vendor types, are not enum members, and are referenced by both negotiation logic and
  annotation `produces`/`@Content` lists. They have no Spring equivalent (Finding 1), so they
  must remain here.
- **Keep** `SDMX_CSV_VALUE` (`application/vnd.sdmx.data+csv`, version-less) -- it is used only
  inside `mapMediaType` as the bare-CSV alias rule and is not a canonical enum member.

The recognised-set derivation can use `SdmxFormat.values()` (membership tests go through
`isMatch`, which compares type+subtype only -- the same comparison the current
`Set<MediaType>` literals get):

```java
private static final Set<MediaType> SDMX_MEDIA_TYPES = Arrays.stream(SdmxFormat.values())
        .map(f -> MediaType.valueOf(f.getContentType()))
        .collect(Collectors.toUnmodifiableSet());
```

**Do NOT rewrite `extractSdmxVersion` to do an enum lookup.** The existing method is
*version-aware* -- it inspects the `version` parameter to disambiguate
`data+json;version=1.0.0` (SDMX 2.1) from `data+json;version=2.0.0` (SDMX 3.0). The only
matcher in the codebase, `SdmxMediaType.isMatch` (`SdmxMediaType.java:56-59`), compares
**type+subtype only and ignores `version`**, so an enum lookup backed by it would collide
`JSON_DATA_1_0_0` with `JSON_DATA_2_0_0` (and the CSV/XML families) and `findFirst()` would
return the wrong version -- breaking `SdmxMediaTypeTest`, the named regression guard. There is
no `isMatchWithVersion` helper today (it was a doc invention). Keep `extractSdmxVersion`'s
branch logic **verbatim**: it already works, and leaving it untouched is the zero-risk choice
for a refactor.

`extractSdmxVersion` keeps its handling of generic types (`application/json`,
`application/xml`, `application/csv`, `text/csv`, `*/*`), the per-Accept-entry iteration
order, the unversioned-SDMX default, and the unsupported-version throw branch -- all
unchanged.

The `sdmxVersion` enum field is therefore **intrinsic metadata** (a data+json;version=2.0.0
format *is* SDMX 3.0 -- this never drifts), not a driver of `extractSdmxVersion`. It is kept
truthful by a parity test (see Verification) and is available for future simplifications. See
the author callout after this step.

**Key points:**
- Behaviour for generic/wildcard branches and CSV-parameter validation is unchanged. This is
  a mechanical re-backing of the recognised-set only, guarded by the existing
  `SdmxMediaTypeTest`.
- `mapMediaType` branches on JSON vs XML vs CSV (for the CSV-alias rule), so it needs the
  three sub-groupings, not one flat set. Derive them from `SdmxFormat.values()` partitioned by
  subtype (contains `json` / `xml` / `csv`) plus the kept generic constants -- do **not**
  collapse to a single set. The CSV alias rule (bare `application/vnd.sdmx.data+csv` ->
  `CSV_DATA_2_0_0`; `text/csv`/`application/csv` accepted) must be preserved verbatim.
- Adding the new schema/metadata/structure-1.0/time-series members to the recognised set does
  not change `mapMediaType`'s observable output: those types are blocked by the `produces`
  gate before `mapMediaType` runs, so they are never presented to it on a live request.

> **Note on `sdmxVersion`.** The field does not "collapse `extractSdmxVersion`" (that rewrite
> is unsafe -- see above). It is kept as intrinsic, parity-tested metadata: not consumed
> directly today, but a stable property of each format and a likely lever for future
> simplifications. (Decided to keep; unlike the dropped `supported` flag, an intrinsic version
> cannot drift.)

### Step 4: Update `FormatSupportChecker`

**File:** `sdmx-proxy/.../common/utils/FormatSupportChecker.java`

`formatMatches(ReturnFormat, MediaType)` -> `formatMatches(SdmxFormat, MediaType)`. CSV
special case stays; `List<ReturnFormat>` params become `List<SdmxFormat>`.

```java
public static boolean formatMatches(SdmxFormat registryFormat, MediaType requestedMediaType) {
    if (registryFormat == SdmxFormat.CSV_DATA_1_0_0 || registryFormat == SdmxFormat.CSV_DATA_2_0_0) {
        return requestedMediaType.getSubtype().contains("csv");
    }
    MediaType registryMediaType = MediaType.valueOf(registryFormat.getContentType());
    return SdmxMediaType.isMatch(registryMediaType, requestedMediaType);
}
```

### Step 5: Update config classes, Translated*Query DTOs, conversion switches, truncators, fixtures

Pure type/constant substitutions, mostly handled by the Step 2 IDE rename:

- `sdmx-proxy-config/.../configuration/data/EndpointConfiguration.java` --
  `List<ReturnFormat> supportedFormats`, `ReturnFormat defaultFormat` -> `SdmxFormat`.
- `sdmx-proxy/.../common/data/Translated{Data,Structure,Availability}Query.java` -- field type.
- `Streaming{Data,Structure,Availability}ConversionService.java` -- `switch` arm names
  (`JSON_1_0_0` -> `JSON_DATA_1_0_0`, `XML_GENERICDATA_2_1` -> `XML_GENERIC_DATA_2_1`,
  `XML_STRUCTURE_SPECIFIC_2_1` -> `XML_STRUCTURE_SPECIFIC_DATA_2_1`). Example
  (`StreamingDataConversionService.getDataReaderFactory`):

  ```java
  private DataReaderFactory getDataReaderFactory(SdmxFormat sourceFormat) {
      return switch (sourceFormat) {
          case JSON_DATA_1_0_0, JSON_DATA_2_0_0 -> sdmxJsonDataReaderFactory;
          case CSV_DATA_1_0_0 -> sdmxCsvDataReaderFactoryV1;
          case CSV_DATA_2_0_0 -> sdmxCsvDataReaderFactoryV2;
          default -> sdmxMLDataReaderFactory;
      };
  }
  ```

- `services/limit/truncate/*` (`JsonDataV10/V20SeriesLimitTruncator`, `Csv`, `Xml`,
  `SeriesLimitTruncatorProvider`, `SeriesLimitTruncator`), `services/limit/*` parsers,
  `LimitEmulationServiceImpl` -- constant renames. Note `SeriesLimitTruncatorProvider.java:24`
  uses the enum as a type token: `new EnumMap<>(ReturnFormat.class)` -> `SdmxFormat.class`
  (the IDE type-rename covers it, but it is not a constant rename -- listed so it is not
  missed).
- `services/fixture/**` files keying on the enum -- constant renames.
- `services/adapter/{AdapterRouterImpl,GenericRegistryAdapterImpl}.java`,
  `services/translator/QueryTranslatorImpl.java` -- type + constant renames.

### Step 6: Repoint vendor-type annotation strings to `SdmxMediaTypes`

**Files:**
- API interfaces (annotation `produces` / `@Content`):
  `api/DataQuery30Api.java`, `api/SdmxStructure30Api.java`, `api/AvailabilityQuery30Api.java`,
  `api/AgencySchemeApi.java`.
- Controllers (the `ACCEPT_HEADER_FALLBACK` constant + any in-body `SdmxMediaType.*_VALUE`
  references): `controller/DataQuery30Controller.java:36`,
  `controller/SdmxStructure30Controller.java:37`,
  `controller/AvailabilityQuery30Controller.java:38`,
  `controller/AgencySchemeController.java:22`.

Replace references to the **removed vendor-type** constants (`SdmxMediaType.SDMX_JSON_2_0_0_VALUE`,
`SDMX_XML_3_0_0_VALUE`, `STRUCTURE_*`, etc.) in `produces`, `@Content(mediaType=...)`, and
`ACCEPT_HEADER_FALLBACK` with the corresponding `SdmxMediaTypes.*` constants.

**Leave untouched** annotation references to the **kept generic** constants
(`SdmxMediaType.ANY`, `APPLICATION_CSV_VALUE`, `TEXT_CSV_VALUE`) -- e.g.
`DataQuery30Api.java:111-112`, `SdmxStructure30Api.java:80`,
`AvailabilityQuery30Api.java:81,209`, `AgencySchemeApi.java:40`, and the `SdmxMediaType.ANY`
uses in the controller bodies (`DataQuery30Controller.java:67`,
`AvailabilityQuery30Controller.java:63,105`, `SdmxStructure30Controller.java:57`,
`AgencySchemeController.java:30`). These constants survive Step 3.

The `produces` allow-lists keep the same set of accepted media types -- this step only
changes where the *vendor-type* literal comes from. Do **not** add the new matrix members to
any `produces` list.

### Step 7: Delete `OutputFormatConstants`

**File:** `sdmx-proxy/.../common/data/OutputFormatConstants.java` -- delete (verified unused;
confirm zero references again before deleting). Its contents are all unused, including one
string the matrix does not carry --
`CONTENT_TYPE_TEXT_CSV_WITH_LABELS_1_0_0_VALUE = "...+csv;version=1.0.0;labels=both"` (`:11`)
-- and several Spring `MediaType` constants (`:12-15`). Nothing migrates; the deletion drops
only dead code.

### Step 8: Migrate configuration JSON (decision 3 -- no aliases)

Apply the rename map to every old enum name in:

- `sdmx-proxy-config/src/main/resources/sdmx_registries_config.json`
- `sdmx-proxy-e2e/src/test/resources/com/epam/sdmxproxy/e2e/tests/config/sdmx_registries_config.json`
- `sdmx-proxy-e2e/.../registry/bis/3_0/bis_3_0_registry_config.json` and
  `bis_3_0_test_config.json`
- `sdmx-proxy-e2e/.../registry/imf/3_0/imf_3_0_registry_config.json` and
  `imf_3_0_test_config.json` (the `registryReturnFormats` arrays)

`JSON_1_0_0` -> `JSON_DATA_1_0_0`, `XML_2_1` -> `XML_STRUCTURE_2_1`, etc. Unchanged names
(`JSON_DATA_2_0_0`, `JSON_STRUCTURE_2_0_0`, `CSV_DATA_1_0_0`, `CSV_DATA_2_0_0`) need no edit.

> Jackson binds enums by `name()`, so any missed occurrence fails fast at config-load time
> with an explicit "no enum constant" error -- no silent mismatch.

### Step 9: Update E2E framework references

**Files:** `sdmx-proxy-e2e/.../framework/BaseRegistryTestSuite.java`
(`TRUNCATOR_SUPPORTED_FORMATS` set at `:75-80` + `ReturnFormat` method params) and the four
config classes that reference `ReturnFormat` (out of 11 `*TestSuitConfiguration.java` in
`framework/config/`): `DataTestSuitConfiguration`, `AvailabilityTestSuitConfiguration`,
`StructuresTestSuitConfiguration`, `LimitTestSuitConfiguration` (each has
`List<ReturnFormat> registryReturnFormats`). The other 7 config classes do **not** reference
the enum. Type + constant renames via Step 2's rename.

## No Changes Required

- **`SdmxVersion.java`** -- unchanged; `SdmxFormat` references it.
- **`MediaTypeParseResult.java`** -- unchanged.
- **Controller request-handling logic** -- the `produces` allow-lists keep the same set of
  accepted media types and `SdmxMediaType.parseMediaType(...)` keeps its signature, so no
  request-handling *behaviour* changes. Note this is not "no edits": the four controllers
  **do** change in Step 6 (the `ACCEPT_HEADER_FALLBACK` constant is re-sourced to
  `SdmxMediaTypes.*`), but the change is literal sourcing only -- no logic.
- **`GlobalExceptionHandler`** -- unchanged. The 400-vs-406 question for
  `HttpMediaTypeNotAcceptableException` is pre-existing (design 020 Q2) and out of scope here.

## Files Affected

| File | Change | Description |
|------|--------|-------------|
| `sdmx-proxy-config/.../configuration/data/SdmxMediaTypes.java` | New | Canonical SDMX vendor-type string literals (one per content type), referenced by `SdmxFormat` + controller annotations (Step 1) |
| `sdmx-proxy-config/.../configuration/data/ReturnFormat.java` | Renamed -> `SdmxFormat.java` | Add `sdmxVersion` field, rename constants, add full matrix; literals from `SdmxMediaTypes`, existing strings byte-for-byte (Step 2) |
| `sdmx-proxy-config/README.md` | Modified (mandatory) | Rename `### Enum: ReturnFormat` -> `SdmxFormat`, apply rename map, add new members, update `supportedFormats`/`defaultFormat` rows (Step 2) |
| `sdmx-proxy/.../common/data/SdmxMediaType.java` | Modified | Sets derived from `SdmxFormat.values()`; remove vendor `*_VALUE` constants; keep generic constants (`ANY`, `TEXT_JSON_VALUE`, `APPLICATION_CSV_VALUE`, `TEXT_CSV_VALUE`, `SDMX_CSV_VALUE`) (Step 3) |
| `sdmx-proxy/.../common/data/OutputFormatConstants.java` | Deleted | Dead code (Step 7) |
| `sdmx-proxy/.../common/utils/FormatSupportChecker.java` | Modified | `ReturnFormat` -> `SdmxFormat` (Step 4) |
| `sdmx-proxy-config/.../configuration/data/EndpointConfiguration.java` | Modified | Field types -> `SdmxFormat` (Step 5) |
| `sdmx-proxy/.../common/data/Translated{Data,Structure,Availability}Query.java` | Modified | Field type -> `SdmxFormat` (Step 5) |
| `sdmx-proxy/.../services/adapter/conversion/Streaming{Data,Structure,Availability}ConversionService.java` | Modified | `switch` arm renames (Step 5) |
| `sdmx-proxy/.../services/adapter/{AdapterRouterImpl,GenericRegistryAdapterImpl}.java`, `services/translator/QueryTranslatorImpl.java` | Modified | Type + constant renames (Step 5) |
| `sdmx-proxy/.../services/limit/**`, `services/fixture/**` | Modified | Constant renames (Step 5) |
| `sdmx-proxy/.../api/{DataQuery30Api,SdmxStructure30Api,AvailabilityQuery30Api,AgencySchemeApi}.java` | Modified | Repoint vendor-type annotation literals to `SdmxMediaTypes.*`; keep generic refs (Step 6) |
| `sdmx-proxy/.../controller/{DataQuery30,SdmxStructure30,AvailabilityQuery30,AgencyScheme}Controller.java` | Modified | `ACCEPT_HEADER_FALLBACK` re-sourced to `SdmxMediaTypes.*` (Step 6) |
| `**/sdmx_registries_config.json`, `*_registry_config.json`, `*_test_config.json` (6 files) | Modified | Enum-name migration (Step 8) |
| `sdmx-proxy-e2e/.../framework/BaseRegistryTestSuite.java` + 4 `*TestSuitConfiguration.java` | Modified | Type + constant renames (Step 9) |
| `sdmx-proxy/.../common/data/SdmxMediaTypeTest.java` | Modified | Add `sdmxVersionMatchesLegacyExtractMapping` parity guard (Verification) |
| `sdmx-proxy-config/.../configuration/data/SdmxFormatTest.java` | New | Parse/uniqueness checks (Verification) |
| `sdmx-proxy/src/test/**`, `sdmx-proxy-e2e/src/test/**` (unit/E2E tests referencing the enum constants, ~20 files) | Modified | Constant renames via the IDE **symbol** rename (not text find-replace) -- beware false positives like `DATA_TYPE.SDMXJSON_*` which are unrelated sdmx-core constants |

(~66 `.java` files reference the type; most are one-line type/constant renames the IDE rename
handles. The table lists the files needing manual attention beyond the mechanical rename.)

## Edge Cases

1. **`extractSdmxVersion` parity.** The current method maps `version=1.0.0`+json ->
   `SDMX_2_1`, `version=2.0.0`+json -> `SDMX_3_0`, `version=1.0.0`+csv -> `SDMX_2_1`,
   `version=2.0.0`+csv -> `SDMX_3_0`, `version=3.0.0` -> `SDMX_3_0`, `version=2.1` ->
   `SDMX_2_1`. The `sdmxVersion` assigned to each member must reproduce this exactly.
   `extractSdmxVersion` itself is **not** modified (Step 3); the
   `sdmxVersionMatchesLegacyExtractMapping` test in `SdmxMediaTypeTest` (main module) guards
   that the field agrees with the unchanged method -- see Verification.
2. **Generic / wildcard media types.** `application/json`, `application/xml`,
   `application/csv`, `text/csv`, `text/json`, `*/*` are **not** `SdmxFormat` members -- they
   are negotiation-layer concepts. The helper keeps handling them (default to `SDMX_3_0` /
   `JSON_DATA_2_0_0`) without expecting an enum match.
3. **`JSON_DATA_DRAFT_2_1`.** The old `JSON_2_1_DRAFT` is declared in `ReturnFormat` but is
   not wired into `getDataReaderFactory` (falls to the XML default) or `getSdmxDataFormat`
   (hits the throwing `default`), and it is not in any controller `produces` list. It stays
   in the enum for completeness; it remains non-acceptable at the controller (400) and
   non-convertible at the switch (501). Grep confirms no repo config references it.
4. **New matrix members: enum membership does not change controller acceptance.** Adding a
   name to the enum does not touch any `produces` list, so client-facing acceptance is exactly
   as today. Most new members' media types are not in any `produces` list and so still get 400
   "not acceptable" (e.g. `application/vnd.sdmx.schema+xml;version=3.0.0`). **Exception:**
   `XML_DATA_3_0_0` (`application/vnd.sdmx.data+xml;version=3.0.0`) is **already accepted** --
   it is in `DataQuery30Api`'s `produces` (the `SDMX_XML_3_0_0_VALUE` entry,
   `DataQuery30Api.java:109`) and is the live XML data output target (`processAsXml` writes
   `SDMXMLDataFormat.COMPACT_3_0`, `StreamingDataConversionService.java:199`). Its enum name
   simply names an existing capability. (As a *registry source* format it is still not wired --
   `getSdmxDataFormat` has no `XML_DATA_3_0_0` arm and would 501 -- but that is unchanged from
   today and not reachable via config since no registry is configured with it.) Net: no
   acceptance change either way.
5. **CSV content-type matching.** `FormatSupportChecker` and `mapMediaType` treat all CSV
   media types interchangeably (subtype contains `csv`). The new CSV members must not break
   this; the special-case branch is preserved verbatim (Step 4).
6. **Outbound `Accept` header -- preserve exact strings.** `getContentType()` is **not** used
   only for matching. It is sent **verbatim as the outbound `Accept` header to upstream
   registries** (`services/adapter/GenericRegistryAdapterImpl.java:65,79` structure;
   `:150,154` data via `resolveDataAcceptHeader`/`buildCsvAcceptHeader`; `:197,277`
   availability). Today's nine constants have **mixed** whitespace (`JSON_1_0_0` =
   `;version=1.0.0` but `JSON_DATA_2_0_0` = `; version=2.0.0`,
   `ReturnFormat.java:8,10`). Normalising would change the bytes of the outbound header for
   several formats. Although HTTP treats OWS around `;` as insignificant (RFC 7231), this is a
   refactor that claims no behaviour change, so the holder **must keep each existing string
   byte-for-byte**. `FormatSupportChecker` matching ignores parameters, and the
   response `Content-Type` is set from the negotiated `MediaType` (not the enum) and goes
   through `MediaType.parseMediaType` at `LimitEmulationServiceImpl.java:638,671`, so neither
   of those is affected either way -- the outbound `Accept` header is the only sensitive
   consumer, and it is kept identical.

   **Caveat on `produces` literals.** The `produces`/`@Content`
   annotations today reference `SdmxMediaType.*_VALUE`, whose strings use the **space** form
   (`SDMX_JSON_1_0_0_VALUE = "...; version=1.0.0"`, `SdmxMediaType.java:19`) while
   `ReturnFormat` uses the **no-space** form for the same type (`ReturnFormat.java:8`). The
   holder is pinned to the `ReturnFormat` (outbound) byte form, so repointing an annotation to
   `SdmxMediaTypes.*` changes that annotation's literal whitespace for a few entries. This is
   **immaterial**: Spring parses `produces` strings into `MediaType` objects for negotiation
   (whitespace-insensitive), and the response `Content-Type` comes from the negotiated
   `MediaType`, not the annotation literal. So "the accepted set is unchanged" and "the holder
   is byte-for-byte the outbound form" are both true and not in conflict -- only the outbound
   `Accept` header is byte-sensitive, and it is preserved.

## Verification

### Unit tests

**File:** `sdmx-proxy/src/test/java/.../common/data/SdmxMediaTypeTest.java` (existing, **main
module**). Its existing methods must pass **unchanged** after Step 3 (the regression guard
that enum-backed negotiation behaves identically), and **add** the parity guard here:

| Test method | Description |
|-------------|-------------|
| `sdmxVersionMatchesLegacyExtractMapping()` (new) | For each `SdmxFormat`, `getSdmxVersion()` equals `SdmxMediaType.extractSdmxVersion(contentType)` (parity guard, Edge Case #1) |

The parity test **cannot** live in `SdmxFormatTest` (config module): `extractSdmxVersion` is
in the main module's `SdmxMediaType`, and the Spring-free config module does not depend on the
main module, so the call would not compile there. The main module sees both classes.

**File (new):** `sdmx-proxy-config/src/test/java/.../configuration/data/SdmxFormatTest.java`

| Test method | Description |
|-------------|-------------|
| `everyContentTypeIsParseableAndUnique()` | Each member's `contentType` parses as a valid media type; no two members share the same type+subtype+version |

**File:** `FormatSupportCheckerTest.java` (existing, if present) -- update constant names;
assert CSV interchange and type+subtype matching unchanged.

**Negotiation parity.** Because Step 6 changes the literal whitespace of a few `produces` entries, add an assertion that the affected media types
still negotiate identically: for `application/vnd.sdmx.data+json;version=1.0.0`,
`...;version=2.0.0`, `...+csv;version=2.0.0`, `...data+xml;version=3.0.0`, and
`...structure+xml;version=2.1`, `MediaType.parseMediaType(oldLiteral).equals(MediaType.parseMediaType(newLiteral))`
holds (or assert each negotiates to a 200 via the manual/E2E curls). This closes the only
behavioural assumption the `produces` repointing relies on.

### Manual verification

```bash
./gradlew clean build            # compiles with renamed type across all modules
```

With the proxy running (per project E2E workflow):

```bash
# accepted formats -> unchanged responses
curl -s -H "Accept: application/vnd.sdmx.data+json;version=2.0.0" \
  "http://localhost:8050/sdmx/3.0/data/dataflow/<agency>/<id>/<ver>/all" | head
# a matrix member NOT in produces -> 400 "Media type not acceptable" (unchanged)
curl -s -o /dev/null -w "%{http_code}\n" -H "Accept: application/vnd.sdmx.schema+xml;version=3.0.0" \
  "http://localhost:8050/sdmx/3.0/data/dataflow/<agency>/<id>/<ver>/all"
```

Expected: identical status + `Content-Type` to pre-refactor behaviour.

### E2E tests

After migrating config + test JSON (Step 8) and framework references (Step 9), the full E2E
suite must pass with no assertion changes -- this is a no-behaviour-change refactor:

```bash
./gradlew :sdmx-proxy-e2e:e2eTest --tests "*.BIS_3_0_RegistryTestSuit"
./gradlew :sdmx-proxy-e2e:e2eTest --tests "*.IMF_3_0_RegistryTestSuit"
./gradlew :sdmx-proxy-e2e:e2eTest --tests "*.IMF_2_1_RegistryTestSuit"   # 2.1 source-format paths
```

The 3.0 suites above exercise the JSON/CSV `supportedFormats` and conversion paths; the 2.1
suite exercises the XML `XML_GENERIC_DATA_2_1` / `XML_STRUCTURE_SPECIFIC_DATA_2_1` source
formats and the structure `XML_STRUCTURE_2_1` rename. Run the **full** suite
(`./gradlew :sdmx-proxy-e2e:e2eTest`) before merge so the fan-out (`agencyID=*`) config paths
that also carry `supportedFormats`/`defaultFormat` are covered, not just the two single-registry
suites.

## SDMX Standard References

- Content-type matrix (authoritative): `sdmx-rest-2.2.0/api/sdmx-rest.yaml`, response blocks
  `"200"` (data, lines ~871-897), `"200-schemas"` (~901-918), `"200-struct"` (~922-933),
  `"200-meta"` (~937-951).
- sdmx-core data formats referenced by the conversion switch:
  `io.sdmx.api.sdmx.constants.DATA_TYPE`, `io.sdmx.format.ml.model.SDMXMLDataFormat`
  (`GENERIC_2_1`, `COMPACT_2_1`, `COMPACT_3_0`) in `sdmx-core-2.3.9/`.

## Open Questions

None outstanding. All decisions are settled:

- **Generic media types** (`*/*`, `text/json`, `application/csv`, `text/csv`) stay as
  `String` constants on `SdmxMediaType` in the main module (`ANY`, `TEXT_JSON_VALUE`,
  `APPLICATION_CSV_VALUE`, `TEXT_CSV_VALUE`) -- Spring has no `text/csv`/`application/csv`
  constant, so they cannot move there. `application/json`/`application/xml`/`*/*` annotation
  uses keep Spring's `MediaType.*_VALUE`. `SdmxMediaTypes` holds SDMX vendor types only.
- **Holder approach** (one literal): `SdmxMediaTypes` constants are referenced from both
  `SdmxFormat` and the controller annotations. The parity-test alternative is dropped, so
  the `contentTypeMatchesHolderConstant` test in the Verification section is not needed.
- **Naming**: `XML_STRUCTURE_SPECIFIC_DATA_2_1` (verbose) accepted per decision 3.
