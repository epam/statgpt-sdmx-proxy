# Design: Fix Skip Logic for Dataset-Level `attributes` in CustomSdmxJsonDataReaderEngineV2

## Context

`CustomSdmxJsonDataReaderEngineV2` is the proxy's override of sdmx-core's `SdmxJsonDataReaderEngineV2`. It exists to
support **inline (non-indexed) attribute values** in SDMX-JSON 2.0 data, which the SDMX-JSON 2.0 spec allows when a
component's `values` array is empty but the attribute is still asserted on a dataset / series / observation. The stock
sdmx-core reader assumes every attribute value is an integer index into the structures section and crashes (or misreads)
when it encounters a string, localized-text object, or array of strings inline.

The proxy's override replaces:

- `decode()` / `decodeNested()` -- accept indices **or** inline values
- the dataset-level `attributes` skip in `moveNextDatasetInternal` -- replaces sdmx-core's
  `jReader.readIntegerArray()` (which assumes integers) with a generic array skip
- `preParseForDatasetAttributes` / `analyseDataSets` -- a one-shot first pass that captures the dataset attributes
  before the main reader iterates the document a second time

When the override was introduced, the dataset-level `attributes` skip was written as:

```java
case "attributes":
    if (jReader.moveNext() && jReader.isStartArray()) {
        jReader.moveToEndArray();
    }
    break;
```

This violated the position invariant of `JsonReader.moveNext()` and silently broke the iteration for any series-keyed
SDMX-JSON 2.0 response whose dataset-level `attributes` array contains *any* element.

## Problem

### Failing scenario

The bug surfaces on every series-keyed SDMX-JSON 2.0 response with a non-empty dataset-level `attributes`. It was
reported on:

```
GET https://api.imf.org/external/sdmx/3.0/data/dataflow/IMF.STA/FSIC/13.0.1/*.S14.FSI179_AFSI_PT.*
    ?dimensionAtObservation=TIME_PERIOD&attributes=all&measures=all&limit=1000
    &skipEmptySeries=false&c[TIME_PERIOD]=ge:2007-01-01
Accept: application/vnd.sdmx.data+json;version=2.0.0
```

IMF returns ~250 KB of SDMX-JSON 2.0 with shape:

```json
{
  "data": {
    "dataSets": [
      {
        "structure": 0,
        "action": "Replace",
        "attributes": [null, null, null, 0, 0, ["datahelp@imf.org"], 0, null, 0,
                       ["2026-04-13T06:03:46.698Z"], ["2026-04-13T06:03:46.792Z"],
                       null, 0, null, 0, null, null, null, null],
        "series": {
          "0:0:0:0": {
            "attributes": [0, null, null, null],
            "observations": {
              "0": ["12.247...", null, 0, null, null, null, null, null, null],
              "1": ["11.984...", null, 0, null, null, null, null, null, null],
              ...
            }
          },
          "0:0:0:1": { ... },
          ...
        }
      }
    ],
    "structures": [ ... ]
  }
}
```

Conversion crashes:

```
java.lang.StringIndexOutOfBoundsException: Range [0, -1) out of bounds for length 1
    at CustomSdmxJsonDataReaderEngineV2.moveNextKeyableInternal(CustomSdmxJsonDataReaderEngineV2.java:583)
    at AbstractDataReaderEngine.moveNextKeyable(AbstractDataReaderEngine.java:444)
    at DataTransformationUtil.copyDataSet(DataTransformationUtil.java:102)
    at StreamingDataConversionService.processAsXml(StreamingDataConversionService.java:195)
```

(The reproduction in the unit test exercises the JSON->JSON path through `processAsJson`; the bug is in the *reader*, so
it manifests on every conversion target -- JSON->JSON, JSON->CSV, JSON->XML.)

### Root cause -- JsonReader iteration semantics

`JsonReader.moveNext()` (sdmx-core 2.3.9, `fusion-utils-json/.../JsonReader.java:485-544`) does **not** stop on
`FIELD_NAME` tokens. When it encounters a field name it stores the name and immediately advances to the next token in
the same call:

```java
case FIELD_NAME:
    fieldName = jParser.getCurrentName();
    token = jParser.nextToken();         // <-- consumes the value's start token in the same moveNext()
    switch(token) {
        case START_ARRAY:  ... push stack ...
        case START_OBJECT: ... push stack ...
    }
    break;
```

So when `moveNextDatasetInternal`'s outer `while (jReader.moveNext())` loop returns with
`getCurrentFieldName() == "attributes"`, the reader is **already at `START_ARRAY`** (the value of `attributes`), not at
the field-name token.

The custom code then calls `jReader.moveNext()` *again*, which steps **into** the array body to its first element
(`null`). `isStartArray()` is now false, so `moveToEndArray()` is never invoked. The dataset-level attributes array is
not consumed.

The outer iteration continues, cycling through every element of the dataset attributes (`fieldName` stays
`"attributes"` for the duration -- it's only reset on the next `FIELD_NAME` token, and the array contains values, not
fields). Each iteration re-enters the `case "attributes"` branch and tries the same broken skip. Eventually the loop
walks past the end of the dataset-level `attributes` array, into the `series` object, into the first series entry, into
its nested `attributes` (broken again), and finally reaches the *series-level* `observations` field. The switch matches
`case "observations"` and sets `isFlat = true` -- even though this is a series-keyed response.

`break outer` exits the dataset iteration with the wrong mode. Subsequent calls into
`moveNextKeyableInternal` take the `isFlat` branch and apply
`fieldName.substring(0, fieldName.lastIndexOf(":"))` to observation field names like `"0"` (no colon). `lastIndexOf`
returns `-1`, and `substring(0, -1)` throws `StringIndexOutOfBoundsException: Range [0, -1) out of bounds for length 1`.

### Why the original sdmx-core code works

`SdmxJsonDataReaderEngineV2` (`fusion-sdmx-json/.../SdmxJsonDataReaderEngineV2.java:281-284`) uses:

```java
case "attributes":
    // Skip it - the Dataset Attributes have been read earlier
    jReader.readIntegerArray();
    break;
```

`readIntegerArray()` consumes through `END_ARRAY` from the current `START_ARRAY` position, so the skip is correct. The
proxy's override cannot use it because the IMF (and any spec-compliant SDMX-JSON 2.0) dataset attributes contain inline
strings and arrays, not just integers -- which is exactly the spec gap the override was created to handle.

### Why `analyseDataSets` works correctly

`analyseDataSets` in the same file (lines 114-136) handles `attributes` at the same nesting level **without** an extra
`moveNext()`:

```java
case "attributes":
    if (jReader.isStartArray()) {
        dsAttributeValues.add(readMixedAttributeArray());
    }
    break;
```

That is the correct shape -- and confirms the position invariant: when a switch case for a field name is entered, the
reader is already at that field's value token.

## Solution

Drop the spurious `jReader.moveNext()` from the dataset-level `attributes` case in
`CustomSdmxJsonDataReaderEngineV2.moveNextDatasetInternal`:

```java
case "attributes":
    // Dataset attributes were already pre-parsed by preParseForDatasetAttributes;
    // skip the array. JsonReader.moveNext() advances past FIELD_NAME directly to
    // its value token, so we are already positioned at START_ARRAY here -- calling
    // moveNext() again would step into the array body and break moveToEndArray's
    // depth tracking, causing the iteration to fall through into the series and
    // misread an inner `observations` field as the dataset-level one.
    if (jReader.isStartArray()) {
        jReader.moveToEndArray();
    }
    break;
```

`moveToEndArray()` (`JsonReader.java:374-410`) maintains its own depth counter starting at 1 and correctly skips nested
arrays inside the dataset attributes (e.g. the `["datahelp@imf.org"]` and ISO-timestamp arrays in the IMF response), so
the array is fully consumed and the outer iteration sees `series` as the next field name -- setting `isFlat = false`,
which is correct for series-keyed responses.

### Why not revert to `readIntegerArray()`

That would re-introduce the original sdmx-core bug for spec-compliant SDMX-JSON 2.0 responses whose dataset attributes
contain inline strings, localized-text objects, or arrays. The custom override exists specifically to handle those.

### Scope

No other case in `moveNextDatasetInternal` is broken -- `action`, `reportingBegin`, `reportingEnd`, `validFrom`,
`validTo`, `publicationYear`, `publicationPeriod` all call `getValueAsString()` (or
`Integer.parseInt(getValueAsString())`) directly, which is correct because the reader is already at the value token.
The `annotations` case calls `readIntegerArray()` from the `START_ARRAY` position, also correct. Only the dataset
`attributes` case had the wrong shape.

## Test plan

Unit test added to `sdmx-proxy/src/test/java/com/epam/sdmxproxy/services/adapter/StreamingDataConversionServiceTest.java`:

- `shouldConvertData_imf_fsic_seriesKeyed_withInlineDatasetAttributes()`
  - Reads the captured failing IMF FSIC payload from
    `sdmx-proxy/src/test/resources/com/epam/sdmxproxy/services/adapter/data_conversion/imf_fsic_data.json` and the
    matching structures from `imf_fsic_structures.json`.
  - Applies the production `METADATA_ATTRIBUTE_USAGE_TO_ATTRIBUTE` structure fixture (matches the runtime path).
  - Calls `StreamingDataConversionService.convert(..., JSON_DATA_2_0_0, application/vnd.sdmx.data+json;version=2.0.0)`
    and asserts:
    - the conversion does not throw (catches the original
      `StringIndexOutOfBoundsException`),
    - `data.dataSets[0].series` is a non-empty object,
    - the first series `"0:0:0:0"` has non-empty `observations` with a non-null first measure value,
    - total observations across all series > 0.

Pre-fix: the test fails with `StringIndexOutOfBoundsException` at `CustomSdmxJsonDataReaderEngineV2:583`.
Post-fix: the test passes; full `StreamingDataConversionServiceTest` class continues to pass.

## Critical files

| File                                                                                                            | Change   |
|-----------------------------------------------------------------------------------------------------------------|----------|
| `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/sdmxsource/CustomSdmxJsonDataReaderEngineV2.java`         | one-line fix in `moveNextDatasetInternal` `case "attributes"` (line 391) |
| `sdmx-proxy/src/test/java/com/epam/sdmxproxy/services/adapter/StreamingDataConversionServiceTest.java`          | new regression test |
| `sdmx-proxy/src/test/resources/com/epam/sdmxproxy/services/adapter/data_conversion/imf_fsic_data.json`          | new test fixture (captured IMF response) |
| `sdmx-proxy/src/test/resources/com/epam/sdmxproxy/services/adapter/data_conversion/imf_fsic_structures.json`    | new test fixture (captured structure response) |
