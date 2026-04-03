# Design: CSV Return Format with Parameters

## Context

The proxy converts data between formats (JSON, XML, CSV) as requested by clients via the Accept header. Previously, CSV
output was always produced by converting from a JSON source fetched from the registry. This worked but meant we never
requested CSV directly from registries, even when they support it natively.

This design adds native CSV return format support: when a client requests CSV (with or without parameters like `labels`,
`timeFormat`, `keys`), the proxy requests CSV directly from the registry if supported, then reads and re-writes the CSV
through sdmx-core for standard compliance and parameter application.

## Problem

1. `ReturnFormat.CSV` had an empty content type (`""`), making it unusable as an Accept header to upstream registries
2. `determineDataReturnFormat` ignored `containsCsvParameters` and always fell back to JSON default format
3. No registry configs declared CSV support
4. sdmx-core's CSV v2 reader (`SdmxCsvDataReaderEngineV2`) cannot parse `labels=both` column headers

## SDMX CSV Versions

SDMX defines two CSV data format versions:

| Version   | Media Type                                    | Standard               | Spec                                                            |
|-----------|-----------------------------------------------|------------------------|-----------------------------------------------------------------|
| CSV 1.0.0 | `application/vnd.sdmx.data+csv;version=1.0.0` | SDMX REST 2.1 (v1.4.0) | [sdmx-csv v1.0](https://github.com/sdmx-twg/sdmx-csv/tree/v1.0) |
| CSV 2.0.0 | `application/vnd.sdmx.data+csv;version=2.0.0` | SDMX REST 3.0 (v2.2.0) | [sdmx-csv v2.0](https://github.com/sdmx-twg/sdmx-csv/tree/v2.0) |

Key differences:

- CSV 1.0 uses a `DATAFLOW` column; CSV 2.0 uses `STRUCTURE` + `STRUCTURE_ID` columns
- CSV 2.0 supports `labels=both` parameter (column headers as `ID:Name`)
- CSV 2.0 supports multivalue columns (`DIM[]`) and multilingual columns (`DIM[en;fr]`)

CSV parameters (`labels`, `timeFormat`, `keys`) are supported in both versions, defined in the Accept header:

```
Accept: application/vnd.sdmx.data+csv;version=2.0.0;labels=both;timeFormat=normalized;keys=series
```

## BIS Registry Quirk

BIS (`stats.bis.org`) is an SDMX 3.0 registry running FusionEdgeServer. Their API documentation
(https://stats.bis.org/api-doc/v2/) advertises CSV 1.0.0 support for data queries. However, the actual responses use
CSV 2.0.0 format:

- Response contains `STRUCTURE,STRUCTURE_ID` columns (CSV 2.0 format), not `DATAFLOW` (CSV 1.0 format)
- `STRUCTURE_ID` values use the `AGENCY:ID(VERSION): NAME` format when `labels=both` is requested
- The registry accepts and correctly applies all CSV 2.0 parameters (`labels`, `timeFormat`, `keys`)

Example BIS response with `labels=both`:

```csv
STRUCTURE,STRUCTURE_ID,ACTION,FREQ:Frequency,CATEGORY:CATEGORY,TIME_PERIOD:Time period or range,OBS_VALUE:Observation Value
dataflow,BIS:BIS_REL_CAL(1.0): BIS_RELEASE_CALENDAR,I,M: Monthly,CBPOL: Central bank policy rates,2024-03,20240418
```

Because of this mismatch between documentation and actual behavior, BIS is configured with `CSV_DATA_2_0_0` in
`sdmx_registries_config.json`, not `CSV_DATA_1_0_0`.

## sdmx-core Bug: CSV v2 Reader Fails with labels=both

### Root Cause

`SdmxCsvDataReaderEngineV2.getHeaderColumnComponent()` does not handle the `ID:Name` column header format produced by
`labels=both`. When the CSV header contains `FREQ:Frequency`, the reader passes the full string `"FREQ:Frequency"` to
`DSD.getComponent()`, which only knows the ID `"FREQ"`. The lookup returns null.

In `AbstractSdmxCsvDataReaderEngine`, null components are silently skipped during column position mapping. This causes
all dimension, measure, and attribute columns to be unrecognized. The reader then produces zero observations because no
columns are mapped, so `hasObsValues` is always false.

### Code Path

```
getHeaderColumnComponent("FREQ:Frequency")
  -> headerId = "FREQ:Frequency"  (no stripping)
  -> super.getHeaderColumnComponent("FREQ:Frequency")
    -> currentDsd.getComponent("FREQ:Frequency")
      -> returns null (DSD only has "FREQ")
        -> column silently skipped
```

### Fix

Created `CustomSdmxCsvDataReaderEngineV2` (in `services/sdmxsource/`) that overrides `getHeaderColumnComponent()` to
strip the `:Name` suffix before DSD lookup:

```java

@Override
protected ComponentBean getHeaderColumnComponent(String columnId) {
    int colonIndex = columnId.indexOf(":");
    if (colonIndex > 0) {
        return super.getHeaderColumnComponent(columnId.substring(0, colonIndex));
    }
    return super.getHeaderColumnComponent(columnId);
}
```

A corresponding `CustomSdmxCsvDataReaderFactoryV2` creates the custom engine instances.

## Changes

### ReturnFormat enum

Replaced the unusable `CSV("")` with two versioned formats:

- `CSV_DATA_1_0_0("application/vnd.sdmx.data+csv;version=1.0.0")`
- `CSV_DATA_2_0_0("application/vnd.sdmx.data+csv;version=2.0.0")`

### CSV Hard Override in determineDataReturnFormat

When the client requests CSV, the proxy checks if the registry has a CSV format in `supportedFormats`. If found, it
returns that CSV format as a hard override -- before the bypass check, independent of `bypassEnabled`. If the registry
does not support CSV, the default format (JSON) is used and the conversion path handles JSON-to-CSV as before.

```java
if(requestedMediaType.getSubtype().

contains("csv")){
ReturnFormat csvFormat = findMatchingFormat(dataConfig.getSupportedFormats(), requestedMediaType);
    if(csvFormat !=null){
        return csvFormat;
    }
            }
```

### Accept Header Construction

When the return format is CSV, the proxy builds the Accept header from the registry's base CSV content type with the
client's CSV parameters appended. This ensures the registry receives its expected versioned media type rather than a
generic `text/csv` or `application/csv`:

```
Client requests:  application/csv;labels=both
Registry config:  CSV_DATA_2_0_0
Accept sent:      application/vnd.sdmx.data+csv;version=2.0.0;labels=both
```

### FormatSupportChecker

Both `CSV_DATA_1_0_0` and `CSV_DATA_2_0_0` match any CSV media type requested by the client (`text/csv`,
`application/csv`, `application/vnd.sdmx.data+csv`). This allows the registry's supported CSV version to be selected
regardless of how the client expresses the CSV request.

### StreamingDataConversionService

Added CSV as a source format alongside JSON and XML:

- `getDataReaderFactory()` returns the appropriate CSV reader factory based on version
- `getSdmxDataFormat()` creates version-aware `SdmxCsvDataFormat` with the client's parameters
- The CSV-to-CSV conversion path reads registry CSV, parses it through sdmx-core, and writes it back with the client's
  requested parameters -- validating and normalizing the response against the standard

### Registry Configs

| Registry | CSV Format       | Reason                                                       |
|----------|------------------|--------------------------------------------------------------|
| BIS      | `CSV_DATA_2_0_0` | 3.0 registry, returns CSV 2.0 format despite documenting 1.0 |
| IMF      | `CSV_DATA_2_0_0` | 3.0 registry, native CSV 2.0 support                         |

### New Files

| File                                                        | Purpose                              |
|-------------------------------------------------------------|--------------------------------------|
| `services/sdmxsource/CustomSdmxCsvDataReaderEngineV2.java`  | Fixes `labels=both` header parsing   |
| `services/sdmxsource/CustomSdmxCsvDataReaderFactoryV2.java` | Factory for the custom reader engine |

### Modified Files

| File                                                              | Change                                                    |
|-------------------------------------------------------------------|-----------------------------------------------------------|
| `configuration/data/ReturnFormat.java`                            | Added `CSV_DATA_1_0_0`, `CSV_DATA_2_0_0`; removed `CSV`   |
| `services/translator/QueryTranslatorImpl.java`                    | CSV hard override in `determineDataReturnFormat`          |
| `services/adapter/GenericRegistryAdapterImpl.java`                | `resolveDataAcceptHeader` + `buildCsvAcceptHeader`        |
| `common/utils/FormatSupportChecker.java`                          | CSV format matching for both versions                     |
| `services/adapter/conversion/StreamingDataConversionService.java` | CSV source format support, version-aware reader selection |
| `services/adapter/config/SdmxSourceConfig.java`                   | CSV reader factory beans (v1 + custom v2)                 |
| `sdmx_registries_config.json`                                     | CSV formats in BIS and IMF data endpoint configs          |
