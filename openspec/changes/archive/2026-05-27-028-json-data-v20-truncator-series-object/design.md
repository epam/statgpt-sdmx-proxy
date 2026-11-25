# Design 028: JSON SDMX-JSON 2.0 series-limit truncator handles `series` as JSON object

**Status:** draft (2026-05-26). Awaiting review.

**Revision (2026-05-26):** First implementation (`series` object branch only) shipped and
unblocked the 500/passthrough behaviour but uncovered a deeper path-collision bug --
`series` also appears nested under `data.structures[*].attributes.series` (the attribute
definitions array, not the data series). The path-agnostic `lastField == "series"` check
was clobbering that array too once the data series counter had saturated. The full fix
adds path-awareness; see "Path-aware match" below.

## Context

`testLimitEmulationStrict(registryReturnFormat=JSON_DATA_2_0_0)` in
`BaseRegistryTestSuite` (IMF suite) fails: with `limit=10` the response carries
**12 series**. The other emulation formats (JSON 1.0, CSV 2.0, XML 2.1) pass.

Stack at the failing assertion:

```
AssertionError: Limit emulation MUST cap series count at limit=10; got 12
  at BaseRegistryTestSuite.testLimitEmulationStrict:496
```

The proxy did execute the emulation path -- shrunk query was issued, the
registry response came back, the truncator was invoked. But the truncator
returned a body still containing 12 series rather than the requested 10.

## Problem

`JsonDataV20SeriesLimitTruncator.transform()` only handles `series` when the
parser encounters a `START_ARRAY` token:

```java
case START_ARRAY -> {
    gen.writeStartArray();
    if ("series".equals(lastField)) {
        copySeriesArray(parser, gen, n, seriesCount);
        gen.writeEndArray();
    }
    lastField = null;
}
```

There is **no symmetric branch for `START_OBJECT`**. The `case START_OBJECT`
just writes the start brace and clears `lastField`.

But SDMX-JSON 2.0.0 emits `series` as a **JSON object keyed by series id**
(`"0:0:0"`, `"0:1:0"`, ...), not as an array. Direct verification against the
running proxy (BIS native-limit response with `limit=10`):

```
data.dataSets[0].series: OBJECT with 10 keys
    first 3 keys: ['0:0:0', '0:1:0', '0:2:0']
```

The proxy's own writer (`JsonV20DataWriterEngine`) produces object form. So
when the truncator sees `"series": {...}`, the `START_OBJECT` branch fires,
the entire object passes through unchanged, and `copySeriesArray` is never
called. The truncator is effectively a no-op on every real response.

The reason the BIS suite passes is its data tests don't hit this code path:
BIS has `supportsLimit: true` (native limit), so `testLimitNativelyHonored`
runs and `testLimitEmulationStrict` is the only emulation-format probe; the
test happens to overshoot only when the shrunk query yields > 10 series.

The sibling `JsonDataV10SeriesLimitTruncator` is correctly object-shaped --
SDMX-JSON 1.0 also uses object form, and v1.0's `copySeriesMap` is the
implementation we need.

## Solution

Add a `copySeriesObject` method to `JsonDataV20SeriesLimitTruncator`
symmetric to `copySeriesArray`, and route the `START_OBJECT` case to it when
`lastField == "series"`. This makes the v2.0 truncator handle both shapes:

- **Object form** (standard SDMX-JSON 2.0 with any single dimension at
  observation -- the default): copy the first `n` field/value pairs, skip
  the rest.
- **Array form** (kept as-is): copy the first `n` array elements, skip the
  rest. Some non-conformant registries or `dimensionAtObservation=AllDimensions`
  responses may emit array form -- preserving that path costs nothing.

The implementation mirrors v1.0's `copySeriesMap` exactly. Cumulative
`seriesCount` semantics (cap across multiple data sets) carry over from the
array branch and stay the contract.

### Path-aware match (2026-05-26 revision)

The SDMX-JSON 2.0 data message also carries DSD-derived attribute metadata under
`data.structures[*].attributes.series` -- a JSON array describing series-level attribute
definitions, **not** the data series. Any token-level match on `lastField == "series"`
will hit that array too. The first revision did just that and corrupted the writer's
attribute table (`ArrayIndexOutOfBoundsException` in `GroupAttributeValues.getAttributes`).

Fix: track the container path with a `Deque<String> containerStack` whose entries are the
field names that introduced each enclosing object/array (or `null` for array elements).
At `FIELD_NAME "series"`, only treat it as the data series when the immediate parent is
an anonymous array element of a container called `dataSets` -- i.e. stack top is `null`
and the entry below is `"dataSets"`. Any other location (notably `attributes.series` where
the immediate parent is the object introduced by field `"attributes"`) flows through
untouched.

This is the cheapest precise check: no full path matching, no Jackson context spelunking,
just two stack entries.

### Why not just match v1.0 verbatim and drop the array branch

The array branch is dead code for the SDMX 2.0 writer, but the truncator
interface accepts any registry's raw response. A future registry that emits
SDMX-JSON 2.0 with `dimensionAtObservation=AllDimensions` would use array
form (per the spec). Keeping both branches future-proofs against that without
adding new test surface beyond the one we're already adding.

## Implementation Plan

### Step 1: Add the missing branch

**File:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/limit/truncate/JsonDataV20SeriesLimitTruncator.java`

In `transform`, change the `START_OBJECT` case to route to `copySeriesObject`
when the parent field is `series`:

```java
case START_OBJECT -> {
    gen.writeStartObject();
    if ("series".equals(lastField)) {
        copySeriesObject(parser, gen, n, seriesCount);
        gen.writeEndObject();
    }
    lastField = null;
}
```

Add the helper, identical in shape to v1.0's `copySeriesMap`:

```java
/**
 * Assumes {@code parser} is positioned at START_OBJECT of a {@code series} map
 * (the standard SDMX-JSON 2.0 form, keyed by series id like "0:0:0"). Consumes
 * through (but does not re-emit) the matching END_OBJECT. Caller writes the
 * closing brace on {@code gen}.
 */
@SneakyThrows
private static void copySeriesObject(JsonParser parser, JsonGenerator gen, int n, int[] seriesCount) {
    while (parser.nextToken() != JsonToken.END_OBJECT) {
        String key = parser.currentName();
        parser.nextToken();
        if (seriesCount[0] < n) {
            gen.writeFieldName(key);
            gen.copyCurrentStructure(parser);
            seriesCount[0]++;
        } else {
            parser.skipChildren();
        }
    }
}
```

### Step 2: Test coverage

**File:** `sdmx-proxy/src/test/java/com/epam/sdmxproxy/services/limit/truncate/JsonDataV20SeriesLimitTruncatorTest.java` (new)

There is currently no unit test for the v2.0 truncator -- v1.0 has one,
v2.0 was shipped without coverage and that is precisely why this bug slipped
through. Add the minimum that would have caught it:

| Test | Description |
|------|-------------|
| `truncate_seriesAsObject_capsAtN` | Input with 12 series in object form; assert output has exactly 10 series. |
| `truncate_seriesAsArray_capsAtN` | Input with 12 series in array form; assert output has exactly 10 series (regression guard for the existing branch). |
| `truncate_acrossMultipleDataSets_cumulative` | Two data sets, 7 + 7 = 14 series; assert total across both is 10 (matches the existing cumulative-count contract). |
| `truncate_seriesEmpty_passesThrough` | Empty `series: {}`; assert output structure preserved. |
| `truncate_seriesUnderN_unchanged` | 5 series, n=10; assert all 5 pass through. |

Fixtures: tiny hand-written SDMX-JSON 2.0 payloads, ~20 lines each. No real
registry data needed.

## Edge Cases

1. **Series object whose first field is itself called `series`.** Cannot
   happen -- series IDs are dimension-positional strings like `"0:1:0"`.
2. **`series` field outside `dataSets`.** The truncator matches on
   `lastField == "series"` regardless of nesting, but no SDMX-JSON 2.0
   schema places a `series` field elsewhere, so this is theoretical.
3. **Mixed shapes across data sets** (one data set with object, another
   with array). The cumulative `seriesCount` already handles this -- the
   counter is shared across both branches.
4. **Trailing fields after `series` inside the same data set object**
   (e.g. `attributes`, `observations` at data-set level): the parser
   continues normally after `copySeriesObject` writes the END_OBJECT and
   returns; remaining tokens flow through the outer switch.

## Files Affected

| File | Change Type | Description |
|------|-------------|-------------|
| `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/limit/truncate/JsonDataV20SeriesLimitTruncator.java` | Modified | Add `copySeriesObject` helper; wire `START_OBJECT` case to it for the `series` field |
| `sdmx-proxy/src/test/java/com/epam/sdmxproxy/services/limit/truncate/JsonDataV20SeriesLimitTruncatorTest.java` | New | Five tests covering object form, array form, cumulative count, empty, under-N |

## Verification

- Unit tests above (see Step 2).
- E2E: rerun `:sdmx-proxy-e2e:e2eTest` with the proxy restarted.
  `testLimitEmulationStrict(registryReturnFormat=JSON_DATA_2_0_0)` for IMF
  should pass (10 series exactly, not 12).

## SDMX Standard References

SDMX-JSON 2.0 data message field guide
(`sdmx-json-2.0.0/data-message/docs/1-sdmx-json-field-guide.md`): the
`series` field is an object whose property names are series keys, except
when `dimensionAtObservation=AllDimensions` in which case `series` is
absent (everything is under `observations` at the data-set level).
