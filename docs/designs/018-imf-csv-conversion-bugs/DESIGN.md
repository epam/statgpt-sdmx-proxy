# Design 016: IMF CSV data conversion — empty OBS_VALUE in BOP, 400 "Failed to convert data" on DIP/CPI

**Status:** investigation; no code changes yet.

**Affected endpoint:** `/sdmx/3.0/data` for the `IMF` registry, response media type
`application/vnd.sdmx.data+csv;version=2.0.0`.

**Tracks:** issues [#49](https://github.com/epam/statgpt-sdmx-proxy/issues/49) and
[#39](https://github.com/epam/statgpt-sdmx-proxy/issues/39).

## Context

Two open bug reports describe failures on the IMF CSV data path:

- **#49** -- "Empty `OBS_VALUE` in BOP 21.0.0 CSV (DEU, 2017)". Reporter suspected an
  upstream regression because the same query against `api.imf.org` directly also
  appeared empty.
- **#39** -- "Data endpoint returns 500 for `attributes=all` and for IMF.RES/WEO".
  Reporter found three failing requests (`DIP`, `CPI`, `WEO`).

Both reports were filed against proxy version `0.1.3`. Their original URLs use a
pair of repeated `c[TIME_PERIOD]` parameters; that form is now rejected at the
proxy boundary by the validator added in fix [#44](https://github.com/epam/statgpt-sdmx-proxy/pull/44)
(commit `c9638f6`, design 012). Re-running with the spec-compliant combined form
`c[TIME_PERIOD]=ge:A+le:B` lets the requests reach the data path and surfaces the
real bugs underneath.

This document records what was observed when those requests do reach the data
path, what we believe the root causes are, and which questions remain open before
a fix is designed.

## Investigation

### Method

Investigation was driven by ad-hoc Python probes (stdlib `urllib` + `csv`) and
the captured stdout of a local `./gradlew :sdmx-proxy:bootRun`. Two probes
covered the reported failures end-to-end:

- Issue #49 -- BOP query (rewritten to the combined `c[TIME_PERIOD]` form) sent
  to both the local proxy and IMF directly; both CSV responses parsed and rows
  counted by empty vs. populated `OBS_VALUE`.
- Issue #39 -- the three reported queries (`DIP`, `CPI`, `WEO`) swept across
  `attributes=all|none`, `Accept: csv|json`, and `target=proxy|IMF`, capturing
  status code, content-type, body size, and a body preview for every non-2xx
  cell. WEO was probed in both its original 5-dim shape and a corrected 3-dim
  shape.

Two narrower helpers were used during root-cause analysis: a CSV shape
inspector (per-row column-count distribution) and a quoted-newline detector
(physical-line count vs. parsed CSV row count).

### Issue #39 -- 400/403 grid

The original 500 status codes are gone in current code. The new shape:

| query              | attrs | accept | proxy | IMF |
|--------------------|-------|--------|------:|----:|
| Q1_DIP             | all   | csv    | **400 "Failed to convert data"** | 200 |
| Q1_DIP             | all   | json   | 200 | 200 |
| Q1_DIP             | none  | csv    | 200 | 200 |
| Q1_DIP             | none  | json   | 200 | 200 |
| Q2_CPI             | all   | csv    | **400 "Failed to convert data"** | 200 |
| Q2_CPI             | all   | json   | 200 | 200 |
| Q2_CPI             | none  | csv    | 200 | 200 |
| Q2_CPI             | none  | json   | 200 | 200 |
| Q3_WEO_5dim (orig) | any   | any    | 400 / 403 | 400 / 403 |
| Q3_WEO_3dim (fixed)| any   | any    | 200 | 200 |

Three sub-findings:

1. **Proxy CSV conversion fails on `attributes=all` for DIP and CPI.** JSON works,
   CSV with `attributes=none` works. Body is the generic
   `{"message":"Failed to convert data","status":400}`; the matching stack trace
   was captured from the bootRun stdout (see below).
2. **WEO with the issue's 5-dim key (`USA.*.*.*.*`) is malformed at the registry
   level.** IMF rejects directly with `key USA.*.*.*.* has more than expected 3
   dimension(s)`. The current `IMF.RES:WEO(9.0.0)` DSD has three dimensions, not
   five. This is not a proxy bug; the issue's URL is wrong. Proxy mirrors IMF's
   400 (and IMF's 403 on the CSV path).
3. **WEO with a corrected 3-dim key (`USA.*.*`) succeeds on every combo for both
   sides.** So the `attributes=all + csv` failure does not reproduce on every
   dataflow -- only on DIP and CPI so far.

### Issue #39 -- root cause for the 400 "Failed to convert data"

bootRun stdout (excerpt; trace IDs trimmed):

```
INFO  c.e.s.s.s.CustomSdmxCsvDataReaderFactoryV2 Using Custom SDMX-CSV v2 Data Reader
ERROR i.s.a.e.SdmxException Line 2 has less elements than expected.
                            Expected 49 elements but line contained 41 elements
        at io.sdmx.format.csv.engine.AbstractSdmxCsvDataReaderEngine$RowDetails
                .validateRowSize(AbstractSdmxCsvDataReaderEngine.java:717)
        at io.sdmx.format.csv.engine.AbstractSdmxCsvDataReaderEngine$RowDetails
                .moveNextRow(AbstractSdmxCsvDataReaderEngine.java:614)
        at io.sdmx.format.csv.engine.AbstractSdmxCsvDataReaderEngine
                .moveNextDatasetInternal(AbstractSdmxCsvDataReaderEngine.java:190)
        at io.sdmx.core.data.util.DataTransformationUtil
                .copyData(DataTransformationUtil.java:162)
        at com.epam.sdmxproxy.services.adapter.conversion
                .StreamingDataConversionService.processAsCsv(.java:212)
        at com.epam.sdmxproxy.services.adapter.conversion
                .StreamingDataConversionService.convert(.java:81)
        at com.epam.sdmxproxy.services.adapter.AdapterRouterImpl
                .lambda$getData$1(AdapterRouterImpl.java:204)
WARN  c.e.s.w.e.GlobalExceptionHandler Invalid argument: Failed to convert data
java.lang.IllegalArgumentException: Failed to convert data
        at com.epam.sdmxproxy.services.adapter.AdapterRouterImpl
                .lambda$getData$1(AdapterRouterImpl.java:216)
```

The call path is straightforward: client requests CSV, proxy fetches CSV from
IMF, proxy round-trips through the SDMX-CSV reader/writer
(`StreamingDataConversionService.processAsCsv`), the reader rejects the input.
`AdapterRouterImpl:216` wraps the underlying `SdmxException` in
`IllegalArgumentException`, which `GlobalExceptionHandler` maps to HTTP 400 with
the body `{"message":"Failed to convert data","status":400}` -- so the *cause* is
swallowed before reaching the client. (Looks like a separate small fix opportunity:
either return the underlying message in dev environments, or log the request URL
on this branch.)

The reader is the sdmx-format library's line-based engine
(`io.sdmx.format.csv.engine.AbstractSdmxCsvDataReaderEngine`). The engine reads
one physical line at a time and validates that it has exactly the same number of
cells as the header. The proxy's only override is
`CustomSdmxCsvDataReaderEngineV2.getHeaderColumnComponent`, which strips the
`":Name"` suffix used in `labels=both` mode -- it does not change row parsing.

The IMF CSV for DIP looks well-formed at first glance, but it embeds newlines
inside quoted attribute fields (`FULL_DESCRIPTION`, `METHODOLOGY_NOTES`,
`SHORT_SOURCE_CITATION`, `SUGGESTED_CITATION`, ...). The quoted-newline probe
prints exactly this:

```
=== DIP (10042 physical lines) ===
  csv.reader rows (incl header): 2009
  ratio phys/csv: 5.00
  csv-row 1: cols=49 embedded_newlines_in_fields=4
  csv-row 2: cols=49 embedded_newlines_in_fields=4
  ...
```

So a single logical row is spread across ~5 physical lines (1 + 4 embedded
newlines). Python's `csv` module (RFC 4180-compliant) sees the file as 2009
rows of 49 cells each; the sdmx-format engine sees the first physical line --
which contains only the cells before the first quoted newline -- and counts 41
cells instead of 49.

`attributes=none` works because IMF strips the descriptive attribute columns,
which removes both the source of the embedded newlines and the row-width
mismatch. The JSON path works because it does not go through the line-based
CSV engine.

This matches the empirical grid: failures are exactly at the intersection of
`attributes=all + Accept: csv 2.0.0`. CPI shows the same pattern with `Expected
47 elements but line contained 39`.

### Issue #49 -- counts

| Side  | HTTP | Rows | `OBS_VALUE` populated | `OBS_VALUE` empty |
|-------|------|-----:|----------------------:|------------------:|
| Proxy | 200  | 114  | **0**                 | 114               |
| IMF   | 200  | 114  | 90                    | 24                |

The reporter's hypothesis ("regression also visible at IMF, may be upstream") no
longer holds. Most rows at IMF are populated; the proxy strips every value. This
is a proxy bug, not an IMF regression.

The proxy's CSV output also differs from IMF's in two more visible ways:

- **Column count drops from 55 to 54.** The two headers are not a permutation of
  each other; one column is missing in the proxy output (we have not yet
  identified which one).
- **Array-marker suffixes are stripped.** IMF emits headers like
  `STRUCTURE[;]`, `PUBLISHER[]`, `METHODOLOGY[]`, `KEYWORDS[]`. The proxy emits
  `STRUCTURE`, `PUBLISHER`, `METHODOLOGY`, `KEYWORDS`. The SDMX-CSV 2.0 spec
  uses `[]` to mark array columns; dropping the marker is at minimum a
  conformance issue, but does not by itself explain the empty `OBS_VALUE`
  column.

### Issue #49 -- what we know about the empty `OBS_VALUE`

Unlike issue #39, the BOP request does **not** raise an exception during
conversion. The relevant bootRun window (request at 12:35:57.283, body fetched
at 12:35:58.194) shows `Using Custom SDMX-CSV v2 Data Reader` once and then no
errors before the response is flushed. `validateRowSize` does not fire because
BOP rows are short and contain no embedded newlines:

```
=== BOP (116 physical lines) ===
  csv.reader rows (incl header): 115
  ratio phys/csv: 1.01
  csv-row N: cols=55 embedded_newlines_in_fields=0
```

So whatever drops `OBS_VALUE` happens silently inside the read or write path.

A representative IMF row with a value:

```
COUNTRY              = 'DEU'
BOP_ACCOUNTING_ENTRY = 'CD_T'        # dimension
INDICATOR            = 'G1'          # dimension
UNIT                 = 'USD'
FREQUENCY            = 'A'
TIME_PERIOD          = '2017'
OBS_VALUE            = '1230793709303.444'
SCALE                = '6'
```

The corresponding proxy output rows have `TIME_PERIOD` and `OBS_VALUE` blank but
the dimension cells (`COUNTRY`, `BOP_ACCOUNTING_ENTRY`, `INDICATOR`, ...) and
`SCALE` filled in correctly. So columns are aligned by name; the bug is specific
to the measure column.

### Issue #49 -- root cause (sdmx-core bug)

The bug is in `sdmx-core` (vendored as `io.sdmx:fusion-core-data`), specifically
`io.sdmx.core.data.util.DataTransformationUtil.copyData(reader, writer:IFlatDataWriterEngine, ...)`
at line 192:

```java
for(KeyValue measure : obs.getMeasures()){
    if (measure.hasMultipleValues()) {
        multivalueMeasures.put(measure.getCode(), new HashSet<>(measure.getValues()));
    } else {
        rowData.put(measure.getCode(), measure.getConcept());   // <-- arguments swapped
    }
}
```

In SDMX `KeyValue` semantics (see `io.sdmx.utils.core.collection.KeyValueImpl`):

- `getConcept()` returns the **component id** (e.g. `"OBS_VALUE"`).
- `getCode()` returns the **cell value** (e.g. `"1230793709303.444"`).

The companion helper `CollectionUtil.keyValuesToFlatMap` (used a few lines
above for series keys and attributes) maps them the right way around:

```java
returnMap.put(kv.getConcept(), kv.getCode());
```

But the measure loop reverses them: it puts the *value* as the map key and
the *id* as the map value. So `rowData` ends up containing
`{ "1230793709303.444": "OBS_VALUE", ... }` instead of
`{ "OBS_VALUE": "1230793709303.444", ... }`.

The writer (`SdmxCsvDataWriterEngineV2.writeRow`) then iterates over component
ids and asks for each cell:

```java
String value = row.getRowData().get(compId);   // get("OBS_VALUE") -> null
```

For `compId == "OBS_VALUE"` the lookup misses -- the actual value is hiding
behind a different key -- so the writer falls through to the multi-value /
multilingual branches, finds nothing there either, and clears the cell. Hence
every `OBS_VALUE` in the output CSV is empty.

The corrected line should be:

```java
rowData.put(measure.getConcept(), measure.getCode());
```

(Likely the multi-value branch is wrong in the same direction:
`measure.getCode()` is the cell value, not the id, so the map key should be
`measure.getConcept()` there too. This matters for multi-measure DSDs.)

#### Why JSON output works

JSON output uses a different `copyData` overload
(`copyData(reader, writer:ISeriesObsDataWriterEngine, ...)` on line 100),
which calls `writer.writeObservation(obs)` and lets the JSON writer pull the
measure values directly off the `Observation` object. That path never builds
the `rowData` map and therefore never triggers the swap.

The bug is specific to the **flat-writer** path -- everything that targets
`IFlatDataWriterEngine` (currently just CSV). It is **not** specific to BOP:
any DSD whose measures carry observation values through this path will lose
them. Existing tests (`shouldConvertDataFromJson20ToCsv20`,
`shouldConvertDataFromXml21ToCsv20`) only assert that the header contains
`STRUCTURE`, so they pass even when every `OBS_VALUE` cell is empty.

This was confirmed with `shouldPreserveObsValueWhenConvertingWeoJsonToCsv`,
which runs the same JSON-in -> CSV-out conversion that
`shouldConvertDataFromJson20ToCsv20` runs and asserts on `OBS_VALUE`
preservation: the result is `filled=0 empty=51`. So WEO has been silently
affected the whole time -- the existing test just didn't notice.

#### How we got here (the experiment chain)

Documented for future debuggers. Each step ran as a unit test with saved IMF
fixtures, no bootRun.

1. **Reproduce in a unit test.** `shouldPreserveObsValueWhenRoundTrippingImfBopCsv`
   feeds the saved BOP CSV plus saved BOP DSD through
   `StreamingDataConversionService.convert(...)` with both formats set to
   `CSV_DATA_2_0_0`. Result: `filled=0 empty=114`, identical to the live
   proxy.
2. **Isolate reader vs. writer.** `shouldPreserveObsValueWhenConvertingImfBopCsvToJson`
   uses the same CSV input but JSON output. Result: 90 observations, all 90
   with non-null `OBS_VALUE` -> reader is fine.
3. **Re-pin the bug to the writer.** `shouldPreserveObsValueWhenConvertingBopJsonToCsv`
   feeds the JSON from step 2 back through JSON-in -> CSV-out. Result:
   `filled=0 empty=90` -> the writer drops `OBS_VALUE` regardless of input
   format.
4. **Inspect the DSD shape.** `inspectBopDsdComponentTypes` confirms BOP has a
   normal single-measure DSD: 5 dimensions, 1 measure (`OBS_VALUE`,
   `getStructureType() == MEASURE_DIMENSION`). No multi-measure quirk.
5. **Intercept the writer.** `inspectBopRowDataAtWriter` runs the production
   conversion path with a custom `IFlatDataWriterEngine` that prints the
   `rowData` map for each row. Smoking gun in row 3:

   ```
   [row 3] obs(single)=null  obs(multi)=null  rowData.size=13  rowData keys=
   [UNIT, INDICATOR, BOP_ACCOUNTING_ENTRY, PRECISION, METHODOLOGY, COUNTRY,
    1230793709303.444,                          <-- value used as key!
    FREQUENCY, ACCESS_SHARING_LEVEL, SECURITY_CLASSIFICATION, SCALE,
    TIME_PERIOD, DERIVATION_TYPE]
   ```

   The numeric `OBS_VALUE` shows up *as a map key*, which is only possible if
   `rowData.put` got `(value, id)` instead of `(id, value)`. From there
   reading the upstream source pinned it to line 192 of
   `DataTransformationUtil`.

#### Proposed fix paths

1. **Upstream fix in sdmx-core.** The cleanest path: file the bug against
   `fusion-core-data`. Trivial diff, swaps two arguments. Until that lands we
   are blocked on a release.
2. **Local override.** Subclass `SdmxCsvDataWriterEngineV2` (or wrap the
   writer with a thin adapter) that pre-translates `IDataRow.getRowData()` to
   undo the swap before the writer reads it. Brittle (we'd be reverse-mapping
   based on whether a key is also present in `componentIds`), but contained.
3. **Local override of the copyData step.** Replace the `DataTransformationUtil.copyData`
   call in `StreamingDataConversionService.processAsCsv` with our own copy
   that does the right `put(concept, code)` for measures. Smallest blast
   radius, doesn't touch sdmx-core. Probably the right interim fix.

We should pair option 3 with option 1 -- ship the workaround now, drop it
when the upstream version comes back.

## Cross-cutting observations

- **Both #39 and #49 are CSV-only.** JSON output works for both queries (when
  the request is otherwise valid). The proxy's JSON-out path is healthier than
  CSV-out today.
- **The proxy reads CSV, writes CSV.** Looking at `AdapterRouterImpl`, the
  conversion path is `IMF CSV -> sdmx-format reader -> sdmx-format writer ->
  client CSV`. We pay the cost of the line-based reader even when the requested
  output is the same format the registry already returned. A bypass for the
  same-in/same-out case would have masked both #39 (parse error never fires)
  and a substantial part of #49 (no round-trip means no opportunity to drop
  `OBS_VALUE`). That bypass exists for some endpoints (`FormatSupportChecker
  .canBypassDataFormat`), but the IMF data endpoint config has
  `bypassEnabled: false` (`sdmx_registries_config.json:117`). Whether we should
  flip that is a separate decision -- it would hide bugs rather than fix them
  and would lose the SDMX-CSV format normalization the proxy currently does.
- **The 400 body hides the real cause.** `IllegalArgumentException("Failed to
  convert data")` at `AdapterRouterImpl:216` discards the underlying
  `SdmxException` message. The cause is logged but not returned. This is fine
  for prod, but during triage of issue #39 it cost time -- the body alone gave
  no signal that the problem was specifically about row-width validation.

## Open questions / next steps

1. **Issue #39 (DIP/CPI):** can the sdmx-format CSV reader be configured or
   subclassed to recognize quoted newlines? `validateRowSize` is in
   `AbstractSdmxCsvDataReaderEngine` -- check whether `RowDetails.moveNextRow`
   uses a per-line tokenizer or a quote-aware one, and whether there is an
   existing toggle. The custom subclass in
   `CustomSdmxCsvDataReaderEngineV2.java` already overrides one method; a
   second override on the row-reader is the most localized fix if the parent
   permits it. If not, we may need to pre-canonicalize the IMF stream
   (collapse quoted newlines into spaces) before handing it to the reader.
2. **Issue #49 (BOP) — root cause is known.** Pick between the fix paths
   listed above (upstream PR vs. local copyData override). The local override
   is the smaller change and unblocks IMF CSV today; the upstream fix is the
   right long-term home.
3. **Verify other DSDs are silently affected.** Before/after the fix, add an
   `OBS_VALUE`-asserting variant of the existing
   `shouldConvertDataFromJson20ToCsv20` test (WEO) and
   `shouldConvertDataFromXml21ToCsv20` test. Both currently only check that
   the header contains `STRUCTURE`, so they tolerate the same bug for any
   DSD. After expanding the assertions, both should fail today and pass after
   the fix.
4. **Body of the 400:** consider including the underlying `SdmxException`
   message (or at least its class name) in the proxy's response body when the
   conversion fails. This is a one-line change in `AdapterRouterImpl:216` and
   would have made #39 self-diagnosing.

## Reproduction artefacts

The end-to-end probes and bootRun captures used during investigation were
ad-hoc and have not been kept in the tree. The reproduction now lives entirely
in unit tests.

Unit tests (no proxy needed; under
`sdmx-proxy/src/test/java/.../adapter/StreamingDataConversionServiceTest.java`):

- `shouldPreserveObsValueWhenRoundTrippingImfBopCsv` -- reproduces #49
  end-to-end via the production conversion service.
- `shouldPreserveObsValueWhenConvertingImfBopCsvToJson` -- pinned the bug to
  the writer.
- `shouldPreserveObsValueWhenConvertingBopJsonToCsv` -- confirmed the bug is
  input-format-independent.
- `inspectBopDsdComponentTypes` -- ruled out a multi-measure DSD.
- `inspectBopRowDataAtWriter` -- intercepted the `IDataRow.rowData` map and
  surfaced the swapped key/value (the smoking gun).

Test fixtures live next to the existing ones:
`sdmx-proxy/src/test/resources/com/epam/sdmxproxy/services/adapter/data_conversion/imf_bop_data.csv`
and `imf_bop_structures.json`.
