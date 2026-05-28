# Design 031: Preserve MSD-derived metadata attribute usages on the data endpoint

**Status:** draft (2026-05-27). Awaiting review.

Follow-up to design 030 (SDMX-JSON 2.0 dimension-group attributes
round-trip) and design 027 (`PRESERVE_METADATA_ATTRIBUTE_USAGES` on the
structure endpoint).

## Scope

SDMX-JSON 2.0 data conversion only. SDMX-JSON 1.0 and XML data paths
keep current behaviour. Structure endpoint is unaffected
(design 027 already handles it).

## Context

Design 030 closed the dimension-group attribute gap for attributes
declared in the DSD's `attributeList.attributes` array (e.g. IMF WEO's
`FUNCTIONAL_CAT`, `UNIT`, `REPORTING_PERIOD_TYPE` — 17 of them, all with
`AttributeRelationship.dimensions=[INDICATOR]`). After 030 these
attributes appear in `attributes.dimensionGroup` and their values appear
in `dimensionGroupAttributes` as the SDMX-JSON 2.0 spec requires.

However the SDMX 3.0 information model lets a DSD also declare
**metadata-attribute usages** in
`dataStructureComponents.attributeList.metadataAttributeUsages`. A usage
is a *reference* to a metadata attribute defined in a MetadataStructure
Definition (MSD), paired with a relationship that places it at one of
the four attachment levels:

| Usage relationship                | Bucket on data message       |
|-----------------------------------|------------------------------|
| `{"none": {}}`                    | `attributes.dataSet`         |
| `{"observation": {}}`             | `attributes.observation`     |
| `{"dimensions": [proper subset]}` | `attributes.dimensionGroup`  |
| `{"dimensions": [full non-time]}` | `attributes.series`          |

IMF.RES:WEO declares **39** such usages spanning all four buckets:

```
$ jq '.data.dataStructures[0].dataStructureComponents.attributeList.metadataAttributeUsages |
       group_by(.attributeRelationship | keys[0]) |
       map({rel: .[0].attributeRelationship | keys[0], n: length})' weo-structures.json
[
  {"rel":"dimensions","n":21},
  {"rel":"none","n":17},
  {"rel":"observation","n":1}
]
```

(`dimensions` further splits into `[INDICATOR]` and `[COUNTRY,
INDICATOR]` subsets, both proper subsets of `[COUNTRY, INDICATOR,
FREQUENCY]` → `dimensionGroup` bucket.)

When a client requests data with `attributes=all`, upstream IMF embeds
*definitions* for these usages alongside the regular DSD attributes in
`data.structures[*].attributes` and *values* in
`data.dataSets[*].attributes` / `dimensionGroupAttributes` exactly as
the SDMX-JSON 2.0 spec describes. The proxy drops them.

## Reproducer

```
GET http://localhost:8050/statgpt/sdmx-proxy/api/v0/sdmx/3.0/data/\
    dataflow/IMF.RES/WEO/9.0.0/USA.LP.*?attributes=all
Accept: application/vnd.sdmx.data+json;version=2.0.0
```

vs upstream IMF directly:

```
GET https://api.imf.org/external/sdmx/3.0/data/dataflow/IMF.RES/WEO/9.0.0/\
    USA.LP.*?attributes=all
```

Observed delta (pre-fix):

| Section                                      | IMF direct | Proxy |
|----------------------------------------------|-----------:|------:|
| `data.structures[0].attributes.dataSet`      | 18         | 0     |
| `data.structures[0].attributes.dimensionGroup` | 37       | 17    |
| `data.structures[0].attributes.series`       | 4          | 10*   |
| `data.structures[0].attributes.observation`  | 3          | 2     |
| `data.dataSets[0].attributes` length         | 18         | 0     |
| `dimensionGroupAttributes['0:0::']` length   | 37         | 0     |
| `dimensionGroupAttributes[':0::']` length    | 37         | 20    |

\* The 6 extra series entries are MSD-derived usages whose values were
observed at runtime via `currentDatasetInfo` and registered to
`additionalSeriesAttributes` — the writer correctly emitted them, just
in the wrong bucket. The 14 MSD-derived usages that had no value in the
sample (only declared on the upstream structure sidecar) disappeared
entirely.

## Root cause

`sdmx-core`'s `DataStructureBean` has no slot for
`metadataAttributeUsages`. The reader silently drops the field
(`SdmxJsonDataStructureReaderEngineV2.getAttributes` only handles
`attributes`), and even if it did read the field there is nowhere to
store it on `AttributeListBean`. Design 027 documents this gap in
detail.

The proxy's data writer
(`CustomSdmxJsonSeriesDataWriterV2` after design 030) iterates the DSD
bean: `dsd.getDatasetAttributes()`, `dsd.getSeriesAttributes(dimAtObs)`,
`dsd.getGroupAttributes()`, `dsd.getObservationAttributes(dimAtObs)`.
None of these surface MSD-derived usages, so every usage definition is
lost.

On the value side, `currentDatasetInfo.writeKey(keyable)` registers any
attribute concept observed at runtime into
`additionalSeriesAttributes`. If a series carries a non-null value for
a metadata-attribute usage, that concept ends up in the proxy's
`attributes.series` bucket via the parent's
`writeAdditionalAttributes(...)` path — wrong bucket, but at least the
data isn't lost. Usages that no series carries (all-null in the
captured response) never get registered and are completely invisible to
the writer.

The structure-endpoint analogue (`metadataAttributeUsages` on the DSD
attribute list itself) was solved in design 027 by capturing the field
from upstream wire bytes before sdmx-core parsed it and re-injecting
the array onto the converted output
(`MetadataAttributeUsagePreserver`). This design lifts the same
technique to the data endpoint.

### Why not extend the bean model

The "right" fix is to extend `AttributeListBean` (and its mutable
counterpart) with a `metadataAttributeUsages` collection and teach the
SDMX-JSON 2.0 reader / writer to handle it. That is the upstream
contribution path; it requires a synchronized IM change, reader update,
writer update, and likely a migration for downstream consumers. Too
invasive for a proxy patch, and would fork the bean tree —
incompatible with future sdmx-core releases.

### Why not extend the proxy writer to synthesize stubs

A previous iteration of this fix (reverted) shared the input's declared
concept list from the reader to the writer via a `ThreadLocal`, then
synthesized stub `AttributeBean`-equivalents inside the writer's
structure-sidecar emission. It worked but introduced a static side
channel between two classes that don't reference each other, made the
writer carry data the sdmx-core bean model doesn't carry, and
complicated future sdmx-core upgrades. Rejected in favour of the
capture+inject pattern.

## Solution

A new `DataFixtureType.PRESERVE_METADATA_ATTRIBUTES` toggle, paired
with a new `MetadataAttributesPreserver` service that mirrors
`MetadataAttributeUsagePreserver` (design 027) on the data endpoint:

1. **Capture phase.** Before the data conversion runs, parse the raw
   upstream JSON and deep-copy the entire
   `data.structures[*].attributes` object (all four buckets) and the
   per-dataset `attributes` value array and `dimensionGroupAttributes`
   value object.
2. **Convert phase.** Existing pipeline runs unchanged. The proxy's
   writer continues to emit whatever it can derive from the DSD bean
   model (correct for DSD-declared attributes, incomplete for
   MSD-derived usages).
3. **Inject phase.** After conversion, overwrite the same four sections
   on the converted output with the captured upstream snapshot.

The capture and inject are best-effort: on parse failure the
conversion's own output is returned unmodified. The toggle is per
endpoint config (data-side fixture), so registries that don't need it
(no MSD-derived usages, or non-JSON formats) keep the current streaming
path; only registries that opt in pay the buffer cost.

### Why replace wholesale (not merge)

The captured upstream sections are internally consistent: the indices
in `dimensionGroupAttributes` value arrays reference positions in
`attributes.dimensionGroup`'s own `values` arrays, both come from the
same upstream emission. Wholesale replacement preserves that internal
consistency. A surgical merge ("only add the MSD-derived entries")
would have to reason about how to interleave MSD indices with DSD
indices in the values arrays, and would re-introduce the bean-model
coupling this design avoids.

The trade-off: the proxy's per-component fixups
(`SdmxJsonV2WriterOverrides` — roles plural, non-coded TIME_PERIOD
shape) are lost on the *attribute* definitions inside the replaced
sections. In practice IMF SDMX-3.0 attribute definitions already
satisfy the schema directly (the overrides only fire on dimensions and
on attributes with `conceptRoles`, neither relevant for IMF WEO's
metadata-attribute usages). The fixups still apply to dimensions, which
this preserver doesn't touch.

## Non-goals

- **Reference-metadata values via the data endpoint.** Per SDMX-REST
  2.2 the actual metadata-attribute *values* (DOI, AUTHOR text, etc.)
  live in MetadataSets fetched from the metadata endpoint. This design
  preserves what the upstream chose to embed in the data response —
  which IMF does emit when `attributes=all` is requested — but does
  not implement the separate metadata endpoint.
- **Other registries.** Only IMF SDMX 3.0 is wired today. Any other
  registry whose data endpoint embeds MSD-derived attribute usages can
  opt in by adding the fixture to its `dataEndpointConfig.fixtures`.
- **CSV / XML data paths.** The fixture is JSON-only. CSV and XML
  output formats do not carry these sections in the same shape;
  separate work if needed.
- **Limit-emulation interactions.** The preserver captures from the
  upstream registry response. When limit emulation runs upstream of
  capture (truncating the response before the proxy emits it), the
  captured sections reflect the upstream's larger response. The
  injected sections may then include `values` array entries for codes
  that don't appear in the truncated data. This is schema-valid
  (unreferenced `values` entries are allowed) but produces slightly
  bloated output. Documented as a known limitation; revisit if it
  matters in practice.

## Implementation

### Step 1: New fixture type

**File:** `sdmx-proxy-config/src/main/java/com/epam/sdmxproxy/configuration/data/fixture/DataFixtureType.java`

Add `PRESERVE_METADATA_ATTRIBUTES` to the enum.

### Step 2: Preserver service

**File:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/fixture/data/MetadataAttributesPreserver.java`

Spring `@Service`. Three operations matching the structure-side
preserver's API:

- `isEnabled(List<FixtureConfiguration<DataFixtureType>>) → boolean`
- `capture(byte[] rawJson) → Snapshot`
- `inject(byte[] convertedJson, Snapshot) → byte[]`

`Snapshot` carries two parallel lists indexed by upstream order:

- `structureAttributes: List<JsonNode>` — the whole `attributes` object
  per structure.
- `datasetValues: List<DatasetValues>` — per-dataset
  `attributes` array and `dimensionGroupAttributes` object.

Both capture and inject are best-effort — `IOException` logs a warning
and returns the input unchanged.

### Step 3: Wire into `AdapterRouterImpl.getData`

**File:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/adapter/AdapterRouterImpl.java`

Gate on `isEnabled(...)` AND
`returnFormat == ReturnFormat.JSON_DATA_2_0_0` AND requested media
type is SDMX-JSON 2.0. When all hold:

1. Read post-fixture stream into a byte array.
2. `Snapshot captured = preserver.capture(bytes)`.
3. Run `streamingDataConversionService.convert(bytes → buffer)`.
4. `bytes = preserver.inject(buffer.toByteArray(), captured)`.
5. Write to the response.

Otherwise stay on the streaming path. This keeps every registry that
doesn't opt in unbuffered.

### Step 4: Restore writer to DSD-only knowledge

**File:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/sdmxsource/CustomSdmxJsonSeriesDataWriterV2.java`

Revert the previous (rejected) ThreadLocal-based MSD handling. The
writer's structure-side emission and value-side group-attribute
emission stay within `DataStructureBean` and `dsd.getGroupAttributes()`
/ `dsd.getSeriesAttributes(...)`. MSD-derived usages are restored
exclusively by the preserver.

The four-bucket split (`dataset`, `dimensionGroup`, `series`,
`observation`) and the partial-dimension-group detection
(`isPartialDimensionGroupAttribute`) introduced by design 030 stay.
They correctly handle DSD-declared attributes and remain the source of
truth when the preserver is disabled.

**File:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/sdmxsource/CustomSdmxJsonFlatDataWriterV2.java`

Same revert.

**File:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/sdmxsource/CustomSdmxJsonDataReaderEngineV2.java`

Drop the `publishDeclaredDimensionGroupConcepts` step. The reader's
existing pre-parse / lazyLoadKey path for the value side is unchanged —
it still emits Group keyables for DSD-declared `dimensionGroupAttributes`
the writer can encode positionally. The preserver handles everything
else.

### Step 5: Registry config

**File:** `sdmx-proxy-config/src/main/resources/sdmx_registries_config.json`

Add to IMF SDMX 3.0 `dataEndpointConfig.fixtures`:

```json
{"type": "PRESERVE_METADATA_ATTRIBUTES", "config": {}}
```

**File:** `sdmx-proxy-e2e/src/test/resources/com/epam/sdmxproxy/e2e/tests/registry/imf/3_0/imf_3_0_registry_config.json`

Same addition.

### Step 6: Documentation

**File:** `sdmx-proxy-config/README.md`

Add a row to the `DataFixtureType` schema table describing the new
toggle.

**File:** `docs/designs/022-sdmxsource-overrides-catalog/DESIGN.md`

Add a per-file entry (`§31`) for `MetadataAttributesPreserver` under
the "Related overrides outside `services/sdmxsource/`" group, modelled
on the §28 entry for the structure-side preserver. Cross-reference
this design.

**File:** `docs/designs/030-sdmx-json-2-0-dimension-group-attributes/DESIGN.md`

Append a "Follow-up" note: MSD-derived dimension-group entries are
handled separately by design 031.

## Tests

### Unit tests

- `MetadataAttributesPreserverTest` — mirrors
  `MetadataAttributeUsagePreserverTest`:
  - `capture` extracts all four buckets and the dataset/group value
    sections.
  - `capture` returns an empty snapshot on malformed JSON.
  - `inject` overwrites the matching sections on the converted output.
  - `inject` skips sections absent from the capture.
  - `inject` returns the input unchanged when the snapshot is empty.

### End-to-end

The existing `DimensionGroupAttributesEndToEndTest` (design 030)
exercises the DSD-only path and stays passing. The MSD case requires
the preserver to run, which means going through `AdapterRouter` — best
covered by an E2E test against the live proxy.

**File:** `sdmx-proxy-e2e/src/test/java/com/epam/sdmxproxy/e2e/tests/framework/BaseRegistryTestSuite.java`

New generic test `testDataMetadataAttributesPreservation`, gated by
`metadataPreservationTestSuitConfiguration` in the registry test
config. Hits the configured dataflow twice (default attributes, then
`attributes=all`) and pins:

- `attributes=all` attribute IDs are a superset of the default-response
  IDs across all four buckets (no metadata-attribute IDs leak when the
  client did not opt in);
- the `attributes=all` response carries at least one attribute ID not
  in the default response (proves the fixture is surfacing something,
  not no-op);
- at least one of those metadata-only attributes carries a non-null
  value somewhere in `dataSets[0]` (dataset-level `attributes`,
  `dimensionGroupAttributes`, or series-level `attributes`), exercising
  the fixture's value-injection path, not just the structure-side
  definitions.

The matching IMF registry test config (`imf_3_0_test_config.json`)
points the block at `IMF.RES:WEO(9.0.0)` / `USA.LP.*` to cover the
reproducer. Registries without MSD usages (e.g. BIS) omit the config
block and the test softly aborts via `Assumptions.assumeTrue`.

## Risks

- **Wholesale replacement loses proxy-applied fixes inside the
  replaced sections.** Documented above; in practice the affected
  fixes (roles plural, non-coded TIME_PERIOD) target dimensions and
  conceptRole-bearing attributes, neither material for IMF's
  metadata-attribute usages.
- **Buffering cost when the fixture is enabled.** Full data-response
  body in memory. WEO USA.LP.* is ~12 KB; larger requests scale
  linearly. Streaming is intact for any registry that does not opt
  in, matching the design-027 trade-off for structure responses.
- **Schema validity under limit emulation.** Captured `values` arrays
  may include codes that no longer appear in the truncated data. The
  SDMX-JSON 2.0 schema allows this (unused `values` entries don't
  break validation), but indices in the injected
  `dimensionGroupAttributes` value arrays still resolve correctly
  against the injected `attributes.dimensionGroup` `values` arrays
  because both come from the same upstream snapshot.
- **Future divergence between upstream and the proxy's bean-model
  output.** If a new SDMX-JSON 2.0 schema field appears alongside
  `attributes` / `dimensionGroupAttributes`, the preserver's path-
  specific copy must learn about it explicitly or the new field is
  silently captured but never re-emitted (matches §28's known
  limitation in design 022, item 5 of cross-cutting analysis).

## SDMX standard references

- SDMX 3.0 IM, `MetadataAttributeUsage`:
  `sdmx-rest-2.2.0/doc/structure.md` and the schema's
  `MetadataAttributeUsageType` at
  `sdmx-json-2.0.0/structure-message/tools/schemas/2.0.0/sdmx-json-structure-schema.json:2154`.
- SDMX-JSON 2.0 data message field guide,
  `sdmx-json-2.0.0/data-message/docs/1-sdmx-json-field-guide.md`,
  sections `structure.attributes`,
  `data.dataSets[*].attributes`, and
  `data.dataSets[*].dimensionGroupAttributes`.

## Related designs

- Design 022: `sdmx-core` overrides catalog. Add §31 entry.
- Design 027: structure-side analogue (`MetadataAttributeUsagePreserver`).
- Design 030: DSD-declared dimension-group attributes (the
  predecessor). MSD-derived usages are the residue not solved there.
