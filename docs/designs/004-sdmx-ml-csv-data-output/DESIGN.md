# Design: SDMX-ML and SDMX-CSV Output for Data Endpoint

## Context

The data endpoint (`GET /api/sdmx/3.0/data/...`) currently only outputs **SDMX-JSON** (v1.0.0 for SDMX 2.1, v2.0.0 for
SDMX 3.0). Clients requesting SDMX-ML (XML) get `UnsupportedOperationException`; SDMX-CSV is not handled at all.

The SDMX 3.0 standard defines three data output formats:

- `application/vnd.sdmx.data+json;version=2.0.0` (already supported)
- `application/vnd.sdmx.data+xml;version=3.0.0` (SDMX-ML 3.0, Structure-Specific/Compact)
- `application/vnd.sdmx.data+csv;version=2.0.0` (SDMX-CSV 2.0.0)

The sdmx-core library (fusion v2.3.9) already has **fully implemented** writers for both formats. This feature is purely
integration work -- wiring existing library writers into the proxy's conversion pipeline.

## Current Architecture (Data Conversion Path)

```
Client Request (Accept header)
  -> DataQuery30Controller
    -> QueryTranslator.translateDataQuery() -- determines returnFormat + contentType
      -> AdapterRouter.getData()
        -> [BYPASS] direct passthrough if format matches registry
        -> [CONVERSION] registry response -> DataReaderEngine -> DataWriterEngine -> client
           (StreamingDataConversionService.convert())
```

`StreamingDataConversionService.convert()` dispatches by target media type:

- JSON -> `processAsJson()` -- uses `JsonDataWriterFactoryProducer` -> `ISeriesObsDataWriterEngine`
- XML -> throws `UnsupportedOperationException`
- CSV -> falls through to generic unsupported error

## sdmx-core Writer Capabilities

| Format | Writer Class                    | Engine Interface             | Factory                                  | Metadata Dependency                                                |
|--------|---------------------------------|------------------------------|------------------------------------------|--------------------------------------------------------------------|
| JSON   | `SdmxJsonDataWriterEngine` / V2 | `ISeriesObsDataWriterEngine` | Custom (`JsonDataWriterFactoryProducer`) | `InMemoryRetrievalManager` (directly)                              |
| XML    | `CompactDataWriterEngine`       | `DataWriterEngine`           | `SdmxMLDataWriterFactory.getInstance()`  | None                                                               |
| CSV    | `SdmxCsvDataWriterEngineV2`     | `IFlatDataWriterEngine`      | Direct construction                      | `SdmxSuperBeanRetrievalManager` (wraps `InMemoryRetrievalManager`) |

Each engine type has a matching `DataTransformationUtil.copyData()` overload:

- `copyData(DataReaderEngine, ISeriesObsDataWriterEngine, DataTransformOptions)` -- JSON
- `copyData(DataReaderEngine, DataWriterEngine, DataTransformOptions)` -- XML (wraps in `RolldownDataWriterEngine`)
- `copyData(DataReaderEngine, IFlatDataWriterEngine, boolean copyHeader, boolean closeWriter, boolean mergeDatasets)` --
  CSV

## Implementation Plan

### Step 1: Add XML output path to `StreamingDataConversionService`

**File**: `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/adapter/conversion/StreamingDataConversionService.java`

Replace the XML `UnsupportedOperationException` block with a `processAsXml()` method:

```java
// targetMediaType included for signature consistency with processAsJson/processAsCsv,
// though XML 3.0 output has no Accept header parameters to parse
private void processAsXml(
        InputStream inputStream,
        OutputStream outputStream,
        SdmxBeans sdmxBeans,
        MediaType targetMediaType,
        ReturnFormat sourceFormat
) {
    DataReaderEngine reader = getDataReader(sdmxBeans, inputStream, sourceFormat);

    // SDMX 3.0 uses Compact (Structure-Specific) format -- no Generic/Compact distinction in 3.0
    DataWriterEngine writer = SdmxMLDataWriterFactory.getInstance()
            .getDataWriterEngine(SDMXMLDataFormat.COMPACT_3_0, outputStream, null);

    DataTransformOptions options = DataTransformOptions.getInstance();
    options.setCopyHeader(true);
    options.setCloseWriter(true);

    DataTransformationUtil.copyData(reader, writer, options);
}
```

**Key points**:

- Uses existing `getDataReader()` (same reader pipeline as JSON)
- `SDMXMLDataFormat.COMPACT_3_0` maps to `DATA_TYPE.COMPACT_3_0` with `SDMX_SCHEMA.SDMX_3_0_0`
- `SdmxMLDataWriterFactory` is a singleton from sdmx-core, no Spring wiring needed
- The `copyData(reader, DataWriterEngine, options)` overload wraps writer in `RolldownDataWriterEngine` internally

**New imports**:

- `io.sdmx.api.sdmx.engine.DataWriterEngine`
- `io.sdmx.format.ml.factory.data.SdmxMLDataWriterFactory`

(`SDMXMLDataFormat` is already imported)

### Step 2: Add CSV output path to `StreamingDataConversionService`

**Same file**. Add `processAsCsv()` method and `isCsvMediaType()` helper:

```java
private boolean isCsvMediaType(MediaType mediaType) {
    return mediaType.getSubtype().contains("csv");
}

private void processAsCsv(
        InputStream inputStream,
        OutputStream outputStream,
        SdmxBeans sdmxBeans,
        MediaType targetMediaType,
        ReturnFormat sourceFormat
) {
    DataReaderEngine reader = getDataReader(sdmxBeans, inputStream, sourceFormat);

    SdmxCsvDataFormat csvFormat = buildCsvDataFormat(targetMediaType);
    SdmxSuperBeanRetrievalManager superBeanRetrievalManager =
            new SdmxSuperBeanRetrievalManagerImpl(new InMemoryRetrievalManager(sdmxBeans));

    IFlatDataWriterEngine writer = new SdmxCsvDataWriterEngineV2(csvFormat, superBeanRetrievalManager, outputStream);

    DataTransformationUtil.copyData(reader, writer, true, true, true);
}
```

**CSV Accept header parameter parsing** -- the SDMX-CSV 2.0.0 standard defines optional parameters in the Accept header:

- `labels` = `id` | `name` | `both` (default: `id`)
- `timeFormat` = `original` | `normalized` (default: `original`)
- `keys` = `none` | `obs` | `series` | `both` (default: `none`)

```java
private SdmxCsvDataFormat buildCsvDataFormat(MediaType targetMediaType) {
    String labelsParam = targetMediaType.getParameter("labels");
    String timeFormatParam = targetMediaType.getParameter("timeFormat");
    String keysParam = targetMediaType.getParameter("keys");

    boolean normalizedTime = "normalized".equals(timeFormatParam);
    boolean includeSeriesKey = "series".equals(keysParam) || "both".equals(keysParam);
    boolean includeObsKey = "obs".equals(keysParam) || "both".equals(keysParam);

    // Locale.ENGLISH for deterministic output across container environments
    return new SdmxCsvDataFormat(
            DATA_TYPE.SDMX_CSV_2_0_0, labelsParam, normalizedTime,
            false, Locale.ENGLISH, includeSeriesKey, includeObsKey, null, false
    );
}
```

**New imports**:

- `io.sdmx.core.sdmx.api.engine.data.IFlatDataWriterEngine`
- `io.sdmx.format.csv.engine.v2.SdmxCsvDataWriterEngineV2`
- `io.sdmx.format.csv.format.SdmxCsvDataFormat`
- `io.sdmx.api.sdmx.manager.structure.SdmxSuperBeanRetrievalManager`
- `io.sdmx.core.sdmx.manager.structure.SdmxSuperBeanRetrievalManagerImpl`

### Step 3: Update `convert()` dispatch logic

Update the `convert()` method body to dispatch to all three paths:

```java
public void convert(InputStream inputStream, OutputStream outputStream,
                    SdmxBeans sdmxBeans, ReturnFormat sourceFormat, MediaType targetMediaType) throws IOException {
    if (isJsonMediaType(targetMediaType)) {
        processAsJson(inputStream, outputStream, sdmxBeans, targetMediaType, sourceFormat);
        return;
    }
    if (isCsvMediaType(targetMediaType)) {
        processAsCsv(inputStream, outputStream, sdmxBeans, targetMediaType, sourceFormat);
        return;
    }
    if (isXmlMediaType(targetMediaType)) {
        processAsXml(inputStream, outputStream, sdmxBeans, targetMediaType, sourceFormat);
        return;
    }
    throw new UnsupportedOperationException(
            String.format("Data conversion to %s is not supported", targetMediaType));
}
```

**Dispatch order**: CSV must be checked before the final fallthrough. Generic CSV types like `text/csv` and
`application/csv` don't match `isJsonMediaType()` (no "json" in subtype) or `isXmlMediaType()` (no "xml" in subtype), so
without a dedicated CSV check they would fall through to `UnsupportedOperationException`. The CSV check before XML is
also cleaner for readability.

### Step 4: Consider `ReturnFormat` enum updates (optional, for bypass)

**File**: `sdmx-proxy-config/src/main/java/com/epam/sdmxproxy/configuration/data/ReturnFormat.java`

Currently there is no SDMX-ML 3.0 **data** return format (only 2.1 entries exist). If any registry natively returns
SDMX-ML 3.0 data and we want to support bypass or reading it as a source format, add:

```java
XML_DATA_3_0("application/vnd.sdmx.data+xml;version=3.0.0"),
```

Also add the corresponding case to `getSdmxDataFormat()` in `StreamingDataConversionService`:

```java
case XML_DATA_3_0 ->{
        return SDMXMLDataFormat.COMPACT_3_0;
}
```

**This is optional for the initial implementation** -- no current registry is configured to return XML 3.0 data. Can be
added when needed.

Similarly, the existing `CSV("")` entry could be updated to:

```java
CSV_1_0_0("application/vnd.sdmx.data+csv;version=1.0.0"),

CSV_2_0_0("application/vnd.sdmx.data+csv;version=2.0.0"),
```

But this is only needed for bypass scenarios where registries natively return CSV. Not required for the conversion path.

### Step 5: Unit Tests

**File**: `sdmx-proxy/src/test/java/com/epam/sdmxproxy/services/adapter/StreamingDataConversionServiceTest.java`

Follow the existing test pattern (Spring Boot test, real sdmx-core conversion).

**Test 1: XML output from XML 2.1 source**

```java

@Test
@SneakyThrows
void shouldConvertDataFromXml21ToXml30() {
    InputStream input = getClass().getResourceAsStream("data_conversion/data_conversion_input_data.xml");
    InputStream structures = getClass().getResourceAsStream("data_conversion/data_conversion_input_structures.xml");
    SdmxBeans sdmxBeans = streamingStructureConversionService.parseStructures(structures, ReturnFormat.XML_2_1);
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

    streamingDataConversionService.convert(input, outputStream, sdmxBeans,
            ReturnFormat.XML_STRUCTURE_SPECIFIC_2_1,
            MediaType.valueOf(SdmxMediaType.SDMX_XML_3_0_0_VALUE));

    String xml = outputStream.toString(StandardCharsets.UTF_8);
    assertTrue(xml.contains("DataSet"), "Output should contain DataSet element");
    assertFalse(xml.isEmpty(), "Output should not be empty");
}
```

**Test 2: CSV output from XML 2.1 source**

```java

@Test
@SneakyThrows
void shouldConvertDataFromXml21ToCsv20() {
    InputStream input = getClass().getResourceAsStream("data_conversion/data_conversion_input_data.xml");
    InputStream structures = getClass().getResourceAsStream("data_conversion/data_conversion_input_structures.xml");
    SdmxBeans sdmxBeans = streamingStructureConversionService.parseStructures(structures, ReturnFormat.XML_2_1);
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

    streamingDataConversionService.convert(input, outputStream, sdmxBeans,
            ReturnFormat.XML_STRUCTURE_SPECIFIC_2_1,
            MediaType.valueOf(SdmxMediaType.SDMX_CSV_2_0_0_VALUE));

    String csv = outputStream.toString(StandardCharsets.UTF_8);
    String[] lines = csv.split("\n");
    assertTrue(lines.length > 1, "CSV should have header + data rows");
    assertTrue(lines[0].contains("STRUCTURE"), "CSV header should contain STRUCTURE column");
}
```

**Test 3: CSV output from JSON 2.0.0 source** (uses existing IMF WEO test data with fixture setup)

**Test 4: CSV with Accept header parameters** (`labels=both`, `keys=series`)

**Test 5: Generic CSV media types** (`text/csv`, `application/csv` -- should default to CSV 2.0.0)

### Step 6: Remove dead code in `StreamingDataConversionService`

**File**: `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/adapter/conversion/StreamingDataConversionService.java`

Remove the unused `DsdAndDataflow` inner class (lines 172-182). It is dead code -- not referenced anywhere in the
codebase.

### No Changes Required

These files/areas need **no modification**:

- **`SdmxMediaType.java`** -- already defines all XML and CSV media types and correctly maps them
- **`SdmxMediaType.extractSdmxVersion()`** -- already handles CSV and XML version extraction for registry selection
- **`DataQuery30Controller.java`** -- generic; passes Accept header through, sets Content-Type from parsed media type
- **`AdapterRouterImpl.java`** -- generic; calls `streamingDataConversionService.convert()` with whatever media type
- **`FormatSupportChecker.java`** -- bypass logic already handles CSV special case (but see edge case #7 below about
  bypass+source gap)
- **`QueryTranslatorImpl.java`** -- format determination already works for XML/CSV Accept headers

## Edge Cases

1. **`text/csv` / `application/csv`** -- no version parameter. `buildCsvDataFormat()` defaults to
   `DATA_TYPE.SDMX_CSV_2_0_0`. `SdmxMediaType.mapMediaType()` returns them as-is (doesn't normalize to versioned form),
   so parameters will be null -- all defaults apply.

2. **`application/vnd.sdmx.data+csv`** (no version) -- `SdmxMediaType.mapMediaType()` normalizes this to
   `application/vnd.sdmx.data+csv; version=2.0.0` (line 76-77 of SdmxMediaType.java).

3. **`application/xml`** (generic XML) -- routed to `processAsXml()`, outputs SDMX-ML 3.0 Compact. This is correct per
   SDMX 3.0 standard.

4. **CSV 1.0.0 requests** -- `SdmxCsvDataWriterEngineV2` constructor wraps `SdmxCsvDataFormat` which validates
   DATA_TYPE. CSV 1.0 output would need `SdmxCsvDataWriterEngineV1` instead. **Recommendation**: For MVP, always use
   V2 (CSV 2.0.0). CSV 1.0.0 is SDMX 2.1 era and unlikely to be requested through our 3.0 API.

5. **Large datasets** -- all paths are streaming. `DataTransformationUtil.copyData()` processes incrementally. No memory
   concerns.

6. **Missing structure beans** -- same as JSON path. If DSD/Dataflow is missing, sdmx-core will throw. Error propagates
   through `AdapterRouterImpl.getData()` catch block.

7. **Bypass/source path gap for CSV and XML 3.0** -- `ReturnFormat.CSV` has empty content type `""`, and no
   `ReturnFormat` entry exists for XML 3.0 data. Currently no registry is configured with these as `returnFormat`, so
   this is safe. However, if a registry is ever configured to natively return CSV or XML 3.0 data: (a) bypass may work
   via `FormatSupportChecker`'s CSV special case, but (b) the conversion path would fail because `getSdmxDataFormat()`
   has no `case CSV` or `case XML_DATA_3_0`. When adding a registry that returns these formats, the `ReturnFormat` enum
   and `getSdmxDataFormat()` must be updated (Step 4).

8. **`labels=name` parameter** -- when a client requests `labels=name` (names only, no IDs), sdmx-core's `CSV_LABEL`
   enum handles it. However, name resolution depends on `SdmxSuperBeanRetrievalManager` having localized concept names
   in the beans. If names aren't available (e.g., registry didn't return them in the DSD), columns may contain empty
   labels. This is acceptable behavior -- the standard says names are best-effort.

## Verification

1. **Unit tests**: Run `./gradlew :sdmx-proxy:test` -- new tests in `StreamingDataConversionServiceTest`
2. **Manual test with running proxy** (`./gradlew :sdmx-proxy:bootRun`):
    -
    `curl -H "Accept: application/vnd.sdmx.data+xml;version=3.0.0" http://localhost:8050/api/sdmx/3.0/data/dataflow/BIS/WS_CBPOL_D/1.0/*`
    -
    `curl -H "Accept: application/vnd.sdmx.data+csv;version=2.0.0" http://localhost:8050/api/sdmx/3.0/data/dataflow/BIS/WS_CBPOL_D/1.0/*`
    - Verify response Content-Type matches requested format
    - Verify response body is valid XML / valid CSV
3. **E2E tests**: Extend existing registry test suites to include XML and CSV media types in test configurations. The
   E2E pairwise test matrix (`BaseRegistryTestSuite`) must cover the new output formats to catch real conversion
   failures against live registries. Specifically:
    - Add `application/vnd.sdmx.data+xml;version=3.0.0` and `application/vnd.sdmx.data+csv;version=2.0.0` to the
      `acceptMediaTypes` list in each registry's `*_test_config.json`
    - This will automatically generate pairwise combinations of the new formats against existing dataflow keys, filter
      parameters, and return formats
    - Unit tests alone are insufficient -- they use canned responses and cannot catch registry-specific serialization
      issues (e.g., a registry returning dimensions that the CSV writer cannot flatten, or XML namespace mismatches
      between 2.1 source and 3.0 output)
    - CSV-specific Accept header parameters (`labels`, `timeFormat`, `keys`) should also be added as pairwise dimensions
      in the test config to verify the full parameter matrix

## Files Modified (Summary)

| File                                      | Change                                                                                                          |
|-------------------------------------------|-----------------------------------------------------------------------------------------------------------------|
| `StreamingDataConversionService.java`     | Add `processAsXml()`, `processAsCsv()`, `buildCsvDataFormat()`, `isCsvMediaType()`, update `convert()` dispatch |
| `StreamingDataConversionServiceTest.java` | Add 4-5 new test methods for XML and CSV output                                                                 |
| `ReturnFormat.java`                       | (Optional) Add `XML_DATA_3_0`, `CSV_2_0_0` entries for future bypass support                                    |
| `*_test_config.json` (per registry)       | Add XML 3.0 and CSV 2.0.0 media types + CSV Accept header parameters to pairwise test dimensions                |

## SDMX Standard References

- SDMX REST API data spec: `sdmx-rest-2.2.0/doc/data.md`
- SDMX REST content negotiation: `sdmx-rest-2.2.0/doc/content_negotiation.md`
- SDMX-CSV 2.0 field
  guide: https://github.com/sdmx-twg/sdmx-csv/blob/sdmx3.0.0/data-message/docs/sdmx-csv-field-guide.md

## sdmx-core Library References

- XML writer:
  `sdmx-core-2.3.9/fusion-sdmx-ml/src/main/java/io/sdmx/format/ml/engine/data/writer/CompactDataWriterEngine.java`
- XML format constants: `sdmx-core-2.3.9/fusion-sdmx-ml/src/main/java/io/sdmx/format/ml/model/SDMXMLDataFormat.java`
- XML writer factory:
  `sdmx-core-2.3.9/fusion-sdmx-ml/src/main/java/io/sdmx/format/ml/factory/data/SdmxMLDataWriterFactory.java`
- CSV writer:
  `sdmx-core-2.3.9/fusion-sdmx-csv/src/main/java/io/sdmx/format/csv/engine/v2/SdmxCsvDataWriterEngineV2.java`
- CSV format: `sdmx-core-2.3.9/fusion-sdmx-csv/src/main/java/io/sdmx/format/csv/format/SdmxCsvDataFormat.java`
- DataTransformationUtil:
  `sdmx-core-2.3.9/fusion-core-data/src/main/java/io/sdmx/core/data/util/DataTransformationUtil.java`

---

## Review

Reviewed against the actual codebase state as of 2026-03-30 (branch `development`, commit `ffe938d`).

### Verdict

Solid design. The scope is well-contained, the "No Changes Required" analysis is accurate, and the implementation
leverages existing sdmx-core writers correctly. The issues below are all fixable without changing the overall approach.

### Issues

#### 1. Incorrect import path for `IFlatDataWriterEngine` (Bug)

The design lists:

```
io.sdmx.api.sdmx.engine.data.IFlatDataWriterEngine
```

The actual package in sdmx-core is:

```
io.sdmx.core.sdmx.api.engine.data.IFlatDataWriterEngine
```

This will cause a compilation error. The design already flagged this with "(verify exact package)" -- good instinct,
wrong guess.

#### 2. Capabilities table: "Needs SuperBeanRetrievalManager" column is misleading (Inaccuracy)

The table says JSON "Yes" for SuperBeanRetrievalManager. The actual JSON path (`processAsJson`) uses
`InMemoryRetrievalManager`, not `SdmxSuperBeanRetrievalManager`. The JSON writer factory (
`JsonDataWriterFactoryProducer`) takes `InMemoryRetrievalManager` directly.

Only the CSV writer genuinely requires `SdmxSuperBeanRetrievalManager` (which wraps `InMemoryRetrievalManager`). The
column should be renamed or the JSON cell corrected to "No (uses InMemoryRetrievalManager directly)".

This matters because someone implementing from this doc might think the JSON path needs refactoring to match the CSV
pattern.

#### 3. `SdmxCsvDataFormat` constructor: `Locale` parameter as `null` (Minor)

The proposed call:

```java
new SdmxCsvDataFormat(DATA_TYPE.SDMX_CSV_2_0_0, labelsParam, normalizedTime,
        false,null,includeSeriesKey, includeObsKey, null,false)
```

The 5th parameter is `Locale locale`. Passing `null` works (the constructor doesn't NPE on it), but if the CSV writer
later tries to localize names and `locale` is null, it may fall back to JVM default locale, which is platform-dependent.
Worth a `// locale=null -> JVM default` comment or explicitly passing `Locale.ENGLISH` for deterministic output. Not a
blocker, but a subtle portability concern for containerized deployments with varying base images.

#### 4. `labels` parameter mapping is lossy (Design gap)

`SdmxCsvDataFormat` internally maps the `labels` param through `analyseLabelsParam()` which sets
`includeNames = labelsParam.equals("both")` and separately stores a `CSV_LABEL` enum via `CSV_LABEL.fromParam()`. The
three valid values are `id`, `name`, `both`.

The design correctly passes the raw `labelsParam` string through to the constructor, which is fine. But the edge case
section doesn't mention what happens when a client sends `labels=name` (names only, no IDs). The sdmx-core `CSV_LABEL`
enum handles it, but the proxy should document whether it supports all three values or only `id`/`both`. If `name`
requires localized concept names that aren't in the beans, responses may contain empty label columns.

#### 5. Missing `DELIMITER` import (Omission)

If `SdmxCsvDataFormat.BASIC_V2` or the explicit delimiter constructor overload is ever used, `io.sdmx.api.csv.DELIMITER`
would need importing. The 9-arg constructor used in the design delegates to the 10-arg constructor with
`DELIMITER.COMMA` default, so this isn't needed now, but worth noting for awareness.

#### 6. `processAsXml` signature inconsistency (Nit)

`processAsJson` and `processAsCsv` both take `targetMediaType` as a parameter. `processAsXml` does not. This is
functionally correct (XML output has no Accept header parameters to parse), but the inconsistency may confuse future
maintainers. Consider adding `targetMediaType` to `processAsXml` for uniform method signatures, even if unused, or add a
comment explaining why it's omitted.

#### 7. No mention of `DsdAndDataflow` inner class (Omission)

`StreamingDataConversionService` already contains an unused `DsdAndDataflow` inner class (lines 172-182). The design
doesn't mention it. If it's dead code, it should be removed as part of this change. If it's intended for future use, it
could be useful for the CSV path (to extract DSD/Dataflow for `SdmxSuperBeanRetrievalManager`). Either way, the design
should acknowledge it.

#### 8. Dispatch order rationale is backwards (Nit)

The design says "CSV check before XML to keep dispatch clean" and "CSV media types don't contain 'xml' so order is
technically safe either way, but CSV-first is cleaner."

The actual risk is the reverse: `application/vnd.sdmx.data+csv` does NOT contain "xml", but could a future XML-like CSV
variant contain "xml" in the subtype? No. The real reason CSV-first matters is that generic `text/csv` has subtype `csv`
which doesn't match `contains("json")` or `contains("xml")`, so it would fall through to the final
`UnsupportedOperationException` if CSV isn't checked. The design reaches the right conclusion but the reasoning should
emphasize that `text/csv` and `application/csv` don't match `isXmlMediaType()` or `isJsonMediaType()`, so they MUST be
caught by a dedicated CSV check or they'll throw.

#### 9. Bypass path for CSV data has a gap (Design gap)

The design says `FormatSupportChecker.java` "bypass logic already handles CSV special case" under "No Changes Required."
This is true -- `formatMatches()` handles CSV. However, the current `ReturnFormat.CSV` has an empty content type string
`""`, which means `FormatSupportChecker.formatMatches()` takes the special CSV branch (checking subtype). But no
registry is currently configured with `returnFormat: CSV` for data, so bypass will never actually trigger for CSV.

The design marks `ReturnFormat` CSV updates as "optional, for bypass" but doesn't flag that **without** those updates, a
registry configured to return CSV data natively would hit the `default` branch in `getSdmxDataFormat()` and throw
`IllegalArgumentException`. If a registry is ever configured with `returnFormat: CSV`, the `getSdmxDataFormat()` method
also needs a case for it -- this is not covered under "No Changes Required" or the optional Step 4.

#### 10. Test coverage gaps (Suggestion)

The design proposes 5 tests, which is a good start. Missing scenarios:

- **XML-to-XML conversion** (XML 2.1 source -> XML 3.0 output): Both reader and writer are XML, different schema
  versions. This exercises the Generic 2.1 -> Compact 3.0 path.
- **JSON-to-XML conversion**: JSON 2.0.0 source -> XML 3.0 output.
- **JSON-to-CSV conversion** from JSON 1.0.0 source (SDMX 2.1 era JSON): Verifies the reader handles older JSON format
  before writing CSV.
- **Error case**: Passing `sourceFormat` that has no matching reader (e.g., if `ReturnFormat.CSV` is passed as source
  without a CSV reader factory). The current `getDataReader()` only switches between JSON and XML reader factories -- a
  CSV source would use the XML factory, which would fail confusingly.
- **Empty dataset**: Source with header but zero observations. Some writers handle this differently (CSV should still
  output header row).

### Confirmed Correct

The following claims were verified against the codebase and are accurate:

- `SdmxMLDataWriterFactory.getInstance()` exists and has `getDataWriterEngine()` with the expected signature
- `SDMXMLDataFormat.COMPACT_3_0` exists
- `SdmxCsvDataWriterEngineV2` implements `IFlatDataWriterEngine` and has the 3-arg constructor
  `(SdmxCsvDataFormat, SdmxSuperBeanRetrievalManager, OutputStream)`
- All three `DataTransformationUtil.copyData()` overloads exist with matching signatures
- `SdmxMediaType` already defines all CSV constants and normalizes unversioned CSV to 2.0.0
- `DataQuery30Controller`, `AdapterRouterImpl`, `QueryTranslatorImpl` are all genuinely generic and need no changes
- `FormatSupportChecker` handles CSV in bypass logic
- The streaming architecture is preserved (no buffering concerns)
- The "No Changes Required" section is accurate for the conversion path (with the caveat in issue #9 about the
  bypass/source path)
