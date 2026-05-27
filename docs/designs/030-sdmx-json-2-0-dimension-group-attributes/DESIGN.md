# Design 030: SDMX-JSON 2.0 dimension-group attributes round-trip

**Status:** draft (2026-05-27). Awaiting review.

Tracks issue [#83](https://github.com/epam/statgpt-sdmx-proxy/issues/83).

Continues design 026 Stage 3 ("Group B"). Supersedes the Stage 3 sketch in
design 026, which addressed only the writer side and did not cover input
parsing. Stage 1 (Group A: `roles` plural / non-coded TIME_PERIOD) and Stage
2 (Group C: metadata-attribute usage over-promotion) are already in.

**Follow-up:** this design covers attributes declared in the DSD's own
`attributeList.attributes` array (e.g. IMF WEO's 17 partial-dim DSD attrs
like `FUNCTIONAL_CAT`, `UNIT`). SDMX 3.0 also lets a DSD declare
`metadataAttributeUsages` that surface in the same four attribute buckets
on the data response — those are dropped by sdmx-core's bean model and
handled separately by [design 031](../031-data-metadata-attributes-preservation/DESIGN.md).

## Scope

SDMX-JSON 2.0 data conversion only. SDMX-JSON 1.0 and XML data paths
keep current behaviour.

## Context

The SDMX-JSON 2.0 data message places attributes attached at the
**dimension-group** level (a subset of the DSD's dimensions) in two
dedicated slots:

- `data.dataSets[*].dimensionGroupAttributes`: a JSON object keyed by a
  *partial dimension value combination* (e.g. `"0::"`, `":0::"`,
  `"0:0::"`) — each colon-separated position is a dimension index or
  empty when that dimension isn't part of the group. Values are
  positional arrays aligned with the structure-side attribute
  definitions (sdmx-json field guide, section
  `dimensionGroupAttributes`).
- `data.structures[*].attributes.dimensionGroup`: array of attribute
  definitions describing each position in the above arrays.

Today, the proxy's SDMX-JSON 2.0 → SDMX-JSON 2.0 data conversion path
silently drops both sections. The upstream registry returns N group
attribute reports, the proxy emits 0. The companion `attributes.series`
section is also wrong: group-attached attribute definitions are folded
into `attributes.series` rather than appearing under
`attributes.dimensionGroup`.

The bean model (`Keyable.isSeries()`, `Keyable.getGroupName()`,
`ATTRIBUTE_ATTACHMENT_LEVEL.GROUP`, `DataStructureBean.getGroupAttributes()`)
is fully wired for group attributes — the XML data writer in
`fusion-sdmx-ml` uses it correctly. The gap is entirely in
`fusion-sdmx-json`.

## Reproducer

Pick any data flow whose DSD declares group-attached attributes (any
registry that emits `<Group>` elements in XML output qualifies). IMF
WEO is convenient:

```
GET https://api.imf.org/external/sdmx/3.0/data/dataflow/IMF.RES/WEO/9.0.0/USA.NGDP_RPCH.*
    ?attributes=all
Accept: application/vnd.sdmx.data+json;version=2.0.0
```

Direct upstream response (verified 2026-05-27):

| Section                                              | Count |
|------------------------------------------------------|------:|
| `data.dataSets[0].dimensionGroupAttributes` keys     | 2 (`"0:0::"`, `":0::"`) |
| Non-null values across both groups                   | 12    |
| `data.structures[0].attributes.dimensionGroup`       | 37 definitions |

Same query via the proxy (`bypassEnabled: false` so it converts):

| Section                                              | Count |
|------------------------------------------------------|------:|
| `data.dataSets[0].dimensionGroupAttributes`          | absent |
| `data.structures[0].attributes.dimensionGroup`       | absent |
| `data.structures[0].attributes.series`               | inflated (group defs folded in) |

Same query in XML 3.0 (`Accept: application/vnd.sdmx.data+xml;version=3.0.0`)
direct vs through the proxy: `<Group>` elements with the inner
`<Metadata>/<Attribute>` blocks round-trip correctly. Confirms the gap
is JSON-only.

## Root cause

### Reader gap — `dimensionGroupAttributes` field never parsed

`CustomSdmxJsonDataReaderEngineV2` (overrides
`SdmxJsonDataReaderEngineV2` from `fusion-sdmx-json`) iterates the
inside of each `dataSets[*]` object in two phases:

1. `preParseForDatasetAttributes` /
   `analyseDataSets` (`CustomSdmxJsonDataReaderEngineV2.java:118-140`).
   Switch handles `structure`, `observations`, `series`, `attributes`.
   `dimensionGroupAttributes` is not a case — falls through to
   `default: break;`. The field is traversed but never captured.
2. `moveNextDatasetInternal` (`CustomSdmxJsonDataReaderEngineV2.java:360-442`).
   Same omission: switch handles `observations`, `series`,
   `annotations`, `attributes`, `action`, `reportingBegin/End`,
   `validFrom/To`, `publicationYear/Period`. No
   `dimensionGroupAttributes` branch.

Consequence: no `Keyable.groupKey(...)` is ever emitted from the
JSON input. The two-pass `GroupDataWriterEngine` wrapper has nothing
to fold; the downstream writer never sees a group keyable.

The existing routing inside `lazyLoadKey`
(`CustomSdmxJsonDataReaderEngineV2.java:838-857`) which splits
attributes between series and group by attachment level only fires
for attributes that appear *inside* `series[*].attributes`. IMF (and
the spec) puts dimension-group attribute values in the dedicated
`dimensionGroupAttributes` block instead.

### Writer value-side gap — no `dimensionGroupAttributes` emission

`SdmxJsonSeriesDataWriterV2` (and its parent
`SeriesDataWriter` in `fusion-sdmx-json`) emits a `series` map per
data set. There is no method that writes
`data.dataSets[*].dimensionGroupAttributes`. The writer's surface is
series-centric (`writeKey`, `writeObservation`,
`writeJsonObs`, `writeAttributes()`).

`GroupDataWriterEngine` (`fusion-core-data`) wraps every JSON writer
emitted by `CustomSdmxJsonDataWriterFactory.java:57`
(`return new GroupDataWriterEngine(dwe)`). Its
`writeKey` buffers each series to a Kryo temporary store; group
keyables go into a `ReportedGroupAttributesValues`. At `close()`,
`flushSeriesAndAddGroups` walks the buffered series, looks up
matching group attribute values per series key, and re-emits each
series with the combined attribute list — folding group attrs into
series attrs.

For XML this is fine: the XML writer also implements
`startGroup`/`writeGroupKeyValue` and emits real `<Group>`
elements before the series. For SDMX-JSON 2.0 the folding is
destructive: there is no symmetric JSON method, so group identity
disappears.

### Writer structure-side gap — `attributes.dimensionGroup` array absent

`SeriesDataWriter.writeAttributes()` (the no-arg version that
populates the structure sidecar) emits `attributes.dataSet`,
`attributes.series`, `attributes.observation`. Group-attached
attributes (`dsd.getGroupAttributes()`) get appended to the
`series` section. The `attributes.dimensionGroup` array required by
the SDMX-JSON 2.0 spec for the value-side
`dimensionGroupAttributes` is never produced.

### Factory wrapper

`CustomSdmxJsonDataWriterFactory.getDataWriterEngine`
(`CustomSdmxJsonDataWriterFactory.java:57`) wraps every JSON writer
in `GroupDataWriterEngine` unconditionally:

```java
return new GroupDataWriterEngine(dwe);
```

For SDMX-JSON 2.0 we want native group-attribute emission instead of
the fold-into-series pass-2.

### Why XML works (confirmed reference)

XML writer base `SdmxDataWriterEngine`
(`fusion-sdmx-ml`) declares
`startGroup(String, AnnotationBean...)` and
`writeGroupKeyValue(String, String)` driven by a `POSITION` state
machine. Group keyables produced by the XML reader are routed to
`startGroup()` instead of being folded. Attribute values written
while `currentPosition == GROUP` land inside the `<Group>` element's
`<Metadata>` block.

The proxy makes no XML-data overrides
(`services/sdmxsource/CustomStax*` are all
*structure*-side, not data). The XML data round-trip is the stock
`fusion-sdmx-ml` behaviour and it works.

This confirms the bean model has full group-attribute support; only
the JSON I/O classes need symmetric implementations.

## Solution

Four changes, scoped to SDMX-JSON 2.0:

1. **Reader**: extend `CustomSdmxJsonDataReaderEngineV2` to parse
   `dimensionGroupAttributes` and emit Group keyables.
2. **Writer (value side)**: extend `CustomSdmxJsonSeriesDataWriterV2`
   to handle Group keyables natively — buffer per group-key
   encoding, emit `dimensionGroupAttributes` at dataset close.
3. **Writer (structure side)**: extend the same custom series writer
   to split `dsd.getGroupAttributes()` out into a separate
   `attributes.dimensionGroup` array; remove from `attributes.series`.
4. **Factory**: stop wrapping the SDMX-JSON 2.0 writer in
   `GroupDataWriterEngine` — the custom writer now handles groups
   directly. JSON 1.0 keeps the wrapper.

### 1. Reader: parse `dimensionGroupAttributes`

Extend the existing pre-parse pass in
`CustomSdmxJsonDataReaderEngineV2.analyseDataSets`
(`CustomSdmxJsonDataReaderEngineV2.java:118`):

```java
case "dimensionGroupAttributes":
    if (jReader.isStartObject()) {
        dsGroupAttributeValues.add(readGroupAttributeMap());
    }
    break;
```

Add the helper:

```java
/**
 * Reads a dimensionGroupAttributes object into a map keyed by the
 * partial-dimension-value-combination string (e.g. "0::", ":0::").
 * Each entry's value is a positional list of AttributeValue.
 */
private Map<String, List<AttributeValue>> readGroupAttributeMap() {
    Map<String, List<AttributeValue>> result = new LinkedHashMap<>();
    while (jReader.moveNext() && !jReader.isEndObject()) {
        String key = jReader.getCurrentFieldName();
        jReader.moveNext();
        if (jReader.isStartArray()) {
            result.put(key, readMixedAttributeArray());
        }
    }
    return result;
}
```

Iteration phase: add the same case in `moveNextDatasetInternal`
(`CustomSdmxJsonDataReaderEngineV2.java:360`) so the field is
skipped over (already captured in pre-parse) without breaking the
state machine — same pattern as the existing `attributes` skip at
line 395-405.

Emit Group keyables in `lazyLoadKey` /
`moveNextKeyableInternal`. The reader's existing
`groupAndSeriesStack` (line 85) is the natural insertion point.
For each entry in the captured group map, before the first matching
series is yielded:

1. Decode the partial-dimension-value-combination key into a list of
   `KeyValue` using `currentDsStructuralMetadata.getSeriesList()`
   (the same mapping the series-key decode uses).
2. Determine the group name. The DSD declares zero or more `<Group>`
   beans; each group has a `dimensionRefs` list. The matching group
   is the one whose `dimensionRefs` equal the set of present
   (non-empty) positions in the combination key. If no exact match
   (e.g. metadata-attribute usages that don't correspond to a formal
   `<Group>`), synthesise a stable name like
   `"DimensionGroup_" + key`. The XML writer does the same — the
   group name doesn't appear in the JSON output anyway.
3. Decode the value array via `decodeMixed` against the new
   `JsonDatasetStructuralMetadata.getDimensionGroupAttributeList()`
   (see Step 1b below).
4. Push `KeyableImpl.groupKey(currentDataflow, currentDsd,
   groupName, dimensionsForGroup, decodedAttrs)` onto
   `groupAndSeriesStack` before the next series keyable.

**Step 1b: structural metadata.** Extend
`CustomSdmxStructureIterator` so the parsed
`structures.attributes.dimensionGroup` list is captured as
`JsonDatasetStructuralMetadata.dimensionGroupAttributeList`
(symmetric to the existing
`datasetAttributeList`/`seriesAttributeList`/`obsAttributeList`).
The mapping data is the same `AttraMapping` record used elsewhere.

### 2. Writer value-side: emit `dimensionGroupAttributes`

Extend `CustomSdmxJsonSeriesDataWriterV2` (overrides
`SdmxJsonSeriesDataWriterV2` from `fusion-sdmx-json`).

Override `writeKey(Keyable)`:

```java
@Override
public void writeKey(Keyable key) {
    if (!key.isSeries()) {
        bufferGroupKey(key);
        return;
    }
    super.writeKey(key);
}
```

`bufferGroupKey` stores the group keyable into a
`Map<String, List<KeyValue>>` keyed by the partial-dimension-value
combination string. The key is built by walking
`key.getKey()` (which lists the group's dimension values) and
mapping each value to its index in the DSD's values list — same
encoding pattern the SDMX-JSON spec mandates. Empty positions for
dimensions not in the group are left blank.

Override `close()` (or hook into the dataset-close path that today
calls `writeStructure(true)`): before the structure sidecar, emit
`dimensionGroupAttributes`:

```java
jsonGenerator.writeObjectFieldStart("dimensionGroupAttributes");
for (var entry : groupBuffer.entrySet()) {
    jsonGenerator.writeFieldName(entry.getKey());
    writePositionalAttributeArray(entry.getValue(),
            dsd.getGroupAttributes());
}
jsonGenerator.writeEndObject();
```

`writePositionalAttributeArray` emits one position per
`dsd.getGroupAttributes()` entry in declared order. For each
position, either the index into the structure-side `values` array
or the inline value depending on whether the attribute is coded.
This mirrors how `writeAttributes(...)` handles dataset-level
attributes (`AbstractJsonDataWriter.writeAttributes(List, List...)`,
line in the decompiled signature).

### 3. Writer structure-side: emit `attributes.dimensionGroup`

Override `writeAttributes()` (no-arg) on the custom series writer.
Split `dsd.getAttributes()` by `getAttachmentLevel()`:

| Bucket                 | Attachment levels                              |
|------------------------|-----------------------------------------------|
| `attributes.dataSet`   | `DATA_SET`                                    |
| `attributes.dimensionGroup` | `GROUP` (dsd.getGroupAttributes())       |
| `attributes.series`    | `DIMENSION_GROUP` (all-dimensions, i.e. series-level) |
| `attributes.observation` | `OBSERVATION`                               |

`SeriesDataWriter.writeAttributes()` in `fusion-sdmx-json` does the
folding via `attr.addAll(dsd.getGroupAttributes())`; the custom
override emits the four sections directly without folding. Note
the SDMX-JSON 2.0 terminology mismatch: `DIMENSION_GROUP`
attachment level in sdmx-core's IM model means *series-level* (all
dimensions narrowed), and the schema's `attributes.series` is the
spec name for that.

The same override applies to
`CustomSdmxJsonFlatDataWriterV2` (the
`dimensionAtObservation=AllDimensions` writer). Flat mode has no
series keyables, but the structure sidecar still needs the correct
`dimensionGroup` array — the per-group values may be empty in flat
output but the schema sidecar must be right.

### 4. Factory: skip wrapper for SDMX-JSON 2.0

Update `CustomSdmxJsonDataWriterFactory.getDataWriterEngine`:

```java
if (format == DATA_TYPE.SDMXJSON_1_0_0) {
    dwe = new SdmxJsonDataWriterEngine(dataFormat, out,
            superBeanRetrievalManager, beanRetrievalManager, forceFlat);
    return new GroupDataWriterEngine(dwe);   // JSON 1.0 unchanged
}
if (format == DATA_TYPE.SDMXJSON_2_0_0) {
    return new CustomSdmxJsonDataWriterEngineV2(dataFormat, out,
            superBeanRetrievalManager, beanRetrievalManager, forceFlat);
    // No GroupDataWriterEngine wrapper — the custom writer handles
    // groups natively.
}
```

## Implementation Plan

### Step 1 — Reader: parse `dimensionGroupAttributes`

**Edit** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/sdmxsource/CustomSdmxJsonDataReaderEngineV2.java`:

- Add field `private List<Map<String, List<AttributeValue>>> dsGroupAttributeValues = new ArrayList<>();`
- Add `case "dimensionGroupAttributes"` in `analyseDataSets`.
- Add helper `readGroupAttributeMap()`.
- Add same case (skip arm) in `moveNextDatasetInternal`.
- Extend `lazyLoadKey` to push Group keyables onto `groupAndSeriesStack` before the first matching series.

**Edit** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/sdmxsource/CustomSdmxStructureIterator.java`:

- Capture `structures.attributes.dimensionGroup` into a new
  `JsonDatasetStructuralMetadata.dimensionGroupAttributeList` field.

### Step 2 — Writer value side

**Edit** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/sdmxsource/CustomSdmxJsonSeriesDataWriterV2.java`:

- Override `writeKey(Keyable)` to buffer Group keyables instead of
  delegating to `super.writeKey`.
- Override the dataset close hook (likely
  `writeStructure(boolean)` or `close()`) to emit
  `dimensionGroupAttributes` from the buffer before the structure
  sidecar.
- Add field `private final Map<String, List<KeyValue>> groupBuffer = new LinkedHashMap<>();`
- Helper `encodeGroupKey(Keyable)` — builds the partial-dimension
  combination string.

### Step 3 — Writer structure side

Same file: override `writeAttributes()` (no-arg). Replicate
`SeriesDataWriter.writeAttributes()` logic but emit four sections
(`dataSet`, `dimensionGroup`, `series`, `observation`) directly
instead of folding group into series.

**Edit** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/sdmxsource/CustomSdmxJsonFlatDataWriterV2.java`:

- Same `writeAttributes()` override for the flat writer's structure
  sidecar.

### Step 4 — Factory wiring

**Edit** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/sdmxsource/CustomSdmxJsonDataWriterFactory.java:50-58`:

- Branch on `DATA_TYPE.SDMXJSON_2_0_0` to return the unwrapped
  custom engine. JSON 1.0 keeps the
  `GroupDataWriterEngine` wrapper.

## Tests

### Unit tests

**New file** `sdmx-proxy/src/test/java/com/epam/sdmxproxy/services/sdmxsource/CustomSdmxJsonDataReaderEngineV2GroupAttrsTest.java`:

| Test | Description |
|------|-------------|
| `readsDimensionGroupAttributes` | Fixture with two group-key entries; assert `KeyableImpl.groupKey` emitted for each, with correct group dimensions and decoded attribute values. |
| `parsesEmptyDimensionGroupAttributes` | Fixture with `dimensionGroupAttributes: {}`; assert no Group keyables emitted, no exception. |
| `decodesIndexedAndDirectValues` | Mixed indexed (`0`) and direct (`"text"`, `{"en":"..."}`) values across positions; assert each decoded correctly via `decodeMixed`. |
| `groupKeyEncodingRoundTrips` | Encode key `"0:0::"` -> dimension values -> re-encode == `"0:0::"`. |

**New file** `sdmx-proxy/src/test/java/com/epam/sdmxproxy/services/sdmxsource/CustomSdmxJsonSeriesDataWriterV2GroupAttrsTest.java`:

| Test | Description |
|------|-------------|
| `emitsDimensionGroupAttributesMap` | Given a stream of one Group keyable + matching series, assert the output has `dimensionGroupAttributes` with the correct key and positional value array. |
| `emitsDimensionGroupAttributeDefinitions` | Assert `data.structures[0].attributes.dimensionGroup` contains the DSD's group-attached attributes (and `attributes.series` does NOT). |
| `multipleDataSets_buffersIndependently` | Two consecutive datasets each with their own group keys; assert each dataset's `dimensionGroupAttributes` is local to that dataset. |
| `noGroupKeys_omitsField` | DSD has group attributes but no group keyables in stream; assert `dimensionGroupAttributes` is omitted (not `{}`). |

**New file** `sdmx-proxy/src/test/java/com/epam/sdmxproxy/services/adapter/data_conversion/DimensionGroupAttributesEndToEndTest.java`:

| Test | Description |
|------|-------------|
| `roundTripsImfWeoFixture` | Feed the trimmed IMF WEO fixture (see "Sample fixtures" below) through the full conversion pipeline; assert the output `dimensionGroupAttributes` matches the input by group-key and per-position values. |
| `attributesSeriesDoesNotContainGroupDefs` | Assert `data.structures[0].attributes.series` count equals the DSD's series-level attribute count (no fold-in). |

### E2E tests

**Edit** `sdmx-proxy-e2e/src/test/resources/.../registry/imf/3_0/imf_test_config.json` (or whichever IMF SDMX-3.0 test config is canonical):

- Add a data-endpoint test case whose expected response asserts
  presence of `data.dataSets[0].dimensionGroupAttributes` and a
  non-empty `data.structures[0].attributes.dimensionGroup`. The
  generic `BaseRegistryTestSuite` currently asserts HTTP 200 + non-
  empty body only; add a targeted assertion path via a new test
  method or expand the existing data tests with an opt-in deeper
  validator.

A registry-agnostic test belongs in `BaseRegistryTestSuite` so any
registry whose DSD declares group attributes is checked:

| Test | Description |
|------|-------------|
| `testDataDimensionGroupAttributesPreserved` | Skip when DSD has no group attributes. Otherwise: query data with `attributes=all`; assert `data.structures[0].attributes.dimensionGroup` is non-empty and matches the DSD's group attributes by id. |

### Sample fixtures

Reuse the existing trimmed sample from design 026:
`sdmx-proxy/src/test/resources/com/epam/sdmxproxy/services/adapter/data_conversion/data_weo_misroute_3countries.json`
(118 KB) — has group keyables. Add a minimal hand-written companion:
`group_attrs_minimal.json` (~30 lines) with exactly two group-key
entries and three positional attributes — enough to exercise the
new code paths without depending on IMF data shape.

## Edge Cases

1. **Group spans all dimensions.** Some DSDs declare a `<Group>` over
   every dimension — the group becomes effectively a series-level
   attribute set, but is still encoded as a group. The proposed code
   handles this naturally: the partial-combination key has every
   position filled.

2. **Multiple groups per dataset.** IMF WEO has
   `GROUP_INDICATOR` and `GROUP_COUNTRY__INDICATOR`. The buffer is a
   `Map<String, List<KeyValue>>`; multiple groups produce multiple
   keys in the output `dimensionGroupAttributes` object. Each group's
   value array is positional against the DSD's full group-attribute
   list, with values absent on positions whose attribute attaches to
   a different group.

3. **`dimensionAtObservation=AllDimensions` (flat).** The flat writer
   has no series keyables, so `GroupDataWriterEngine`'s pass-2 was
   doing nothing anyway. The factory still needs to skip the wrapper
   for the flat path. The flat writer's structure sidecar must
   emit `attributes.dimensionGroup`; the value-side
   `dimensionGroupAttributes` may legitimately be absent if the
   upstream omitted them in flat mode.

4. **Metadata-attribute usages.** A DSD may declare
   `metadataAttributeUsages` with a `dimensionGroup` relationship.
   These show up in the structure-side `attributes.dimensionGroup`
   alongside regular DSD attributes — they share the same JSON
   layout. The custom writer's structure-side path iterates
   `dsd.getGroupAttributes()`, which already includes both. (Design
   027 keeps usages preserved via the
   `PRESERVE_METADATA_ATTRIBUTE_USAGES` structure fixture; that path
   is independent.)

5. **JSON 1.0 output.** Untouched. The factory keeps the
   `GroupDataWriterEngine` wrapper for SDMX-JSON 1.0 — its writer
   was always series-only and the fold-into-series behaviour is the
   spec-correct shape for JSON 1.0 (which has no
   `dimensionGroupAttributes` concept).

6. **Interaction with design 026 Stage 4 (series misrouting).** The
   misroute reproducer (3 of 8200 series displaced) lives somewhere
   in the `GroupDataWriterEngine` two-pass machinery. Removing that
   wrapper for SDMX-JSON 2.0 may fix the misroute as a side effect,
   per the original Stage 4 hypothesis. The reproducer test
   (`shouldNotMisrouteSeriesAcrossIndicatorPositions_issue80`) stays
   in place as the regression guard; if it goes green after this
   design lands, Stage 4 is done.

## Files Affected

| File | Change | Description |
|------|--------|-------------|
| `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/sdmxsource/CustomSdmxJsonDataReaderEngineV2.java` | Modified | Add `dimensionGroupAttributes` parsing in pre-parse + iteration; emit Group keyables in `lazyLoadKey`. |
| `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/sdmxsource/CustomSdmxStructureIterator.java` | Modified | Capture `structures.attributes.dimensionGroup` into `JsonDatasetStructuralMetadata.dimensionGroupAttributeList`. |
| `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/sdmxsource/CustomSdmxJsonSeriesDataWriterV2.java` | Modified | Override `writeKey` to buffer group keyables; emit `dimensionGroupAttributes` at dataset close; split structure-side attributes into four buckets. |
| `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/sdmxsource/CustomSdmxJsonFlatDataWriterV2.java` | Modified | Same structure-side attribute split. |
| `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/sdmxsource/CustomSdmxJsonDataWriterFactory.java` | Modified | Skip `GroupDataWriterEngine` wrapper for `SDMXJSON_2_0_0`. |
| `sdmx-proxy/src/test/java/com/epam/sdmxproxy/services/sdmxsource/CustomSdmxJsonDataReaderEngineV2GroupAttrsTest.java` | New | Reader unit tests. |
| `sdmx-proxy/src/test/java/com/epam/sdmxproxy/services/sdmxsource/CustomSdmxJsonSeriesDataWriterV2GroupAttrsTest.java` | New | Writer unit tests (value side + structure side). |
| `sdmx-proxy/src/test/java/com/epam/sdmxproxy/services/adapter/data_conversion/DimensionGroupAttributesEndToEndTest.java` | New | Full conversion pipeline test with the existing trimmed IMF WEO fixture. |
| `sdmx-proxy/src/test/resources/com/epam/sdmxproxy/services/adapter/data_conversion/group_attrs_minimal.json` | New | ~30-line hand-written fixture exercising two group keys and three positional attributes. |
| `sdmx-proxy-e2e/src/test/java/com/epam/sdmxproxy/e2e/tests/framework/BaseRegistryTestSuite.java` | Modified | New `testDataDimensionGroupAttributesPreserved` parameterised test, skipped when the DSD has no group attributes. |
| `docs/designs/022-sdmxsource-overrides-catalog/DESIGN.md` | Modified | Add catalogue entry for the new overrides once implementation lands. |
| `docs/designs/026-data-conversion-fidelity-sdmx-json-2-0/DESIGN.md` | Modified | Mark Stage 3 superseded by this design. |

## Verification

- All unit tests above pass.
- The trimmed reproducer test from design 026
  (`shouldNotMisrouteSeriesAcrossIndicatorPositions_issue80`) is
  observed: either still passing (no regression) or going from
  failing → passing (design 026 Stage 4 resolved as side effect).
- E2E suite: `:sdmx-proxy-e2e:e2eTest --tests "*.IMF_3_0_RegistryTestSuit"`
  passes; the new
  `testDataDimensionGroupAttributesPreserved` parameterised test
  runs and asserts presence on every IMF data flow whose DSD has
  group attributes.
- Manual: hit
  `/data/dataflow/IMF.RES/WEO/9.0.0/USA.NGDP_RPCH.*?attributes=all`
  through the proxy and diff against the direct IMF response —
  expect `dimensionGroupAttributes` keys/values to match and
  `attributes.dimensionGroup` definition counts to match the DSD.

## Risks

- **Removing the `GroupDataWriterEngine` wrapper for SDMX-JSON 2.0
  is a behavioural change.** Every JSON 2.0 conversion exercises the
  new code path. Mitigation: the existing structure conversion
  reproducer and the design 026 misroute reproducer both run on the
  full IMF WEO sample (18 MB) and exercise the multi-group case.
- **Group-key encoding is positional and DSD-order-sensitive.** A
  silent reorder of `dimensionRefs` between reader and writer would
  produce keys that parse but mean something different downstream.
  Mitigation: encode by `DataStructureBean.getDimensions()` indexed
  iteration on both sides; cover with the
  `groupKeyEncodingRoundTrips` unit test.
- **Custom writer overrides bypass sdmx-core's own test coverage.**
  Every override is a fresh code path. Mitigation: unit tests on
  fixture data per file; no reliance on sdmx-core's internal
  invariants.
- **No symmetric XML path.** This design changes the JSON writer
  only. If a future client requests XML for a JSON-only registry
  (and the proxy uses the XML data writer with stock fusion-sdmx-ml),
  the round-trip on group attrs stays correct because the XML side
  already works — but it goes through a different reader (no JSON
  group keyables come from XML input). Confirm via E2E that
  XML output paths still produce `<Group>` blocks.

## SDMX standard references

- SDMX-JSON 2.0 data message field guide,
  `sdmx-json-2.0.0/data-message/docs/1-sdmx-json-field-guide.md`,
  sections `dimensionGroupAttributes` (line 745) and
  `structure.attributes` (the `dimensionGroup` array under
  `attributes`).
- SDMX-JSON 2.0 data schema,
  `sdmx-json-2.0.0/data-message/tools/schemas/2.0.0/sdmx-json-data-schema.json`.
- SDMX-REST 2.2 query semantics — `attributes=all` parameter,
  `sdmx-rest-2.2.0/doc/data.md`.

## Related designs

- Design 022: sdmxsource overrides catalog — add entry once
  implementation lands.
- Design 026: data conversion fidelity (parent). This design
  supersedes Stage 3.
- Design 027: removal of `MetadataAttributeUsageToAttribute` —
  independent; preserves structure-side metadata-attribute usages
  via a structure-side fixture, unrelated to the data-side gap
  fixed here.
