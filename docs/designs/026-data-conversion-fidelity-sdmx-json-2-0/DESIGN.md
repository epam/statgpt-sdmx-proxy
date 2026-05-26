# Design 026: Restore data conversion fidelity for SDMX-JSON 2.0 data responses

**Status:** Stage 1 implemented (2026-05-22) — `roles` plural array and
non-coded TIME_PERIOD value shape via `CustomSdmxJsonDataWriterEngineV2`
and the `SdmxJsonV2WriterOverrides` helper, wired in
`CustomSdmxJsonDataWriterFactory`. Stages 3–4 proposed below.
Stage 2 (Group C: metadata-attribute usage over-promotion) is
**resolved by [design 027](../027-remove-metadata-attribute-usage-to-attribute/DESIGN.md)**:
the workaround that promoted usages into synthetic attributes during the
DSD pre-fetch was removed, so the data response no longer carries phantom
entries in `attributes.dataset/series/observation`. The Stage 2
"rescue-and-strip" plan below is kept for historical context.
The misroute reproducer test
`shouldNotMisrouteSeriesAcrossIndicatorPositions_issue80` is in the
test suite as a deliberate failing regression guard. Tracks
issue [#80](https://github.com/epam/statgpt-sdmx-proxy/issues/80).

## Context

Issue #80 reports five distinct losses when the proxy converts an
SDMX-JSON 2.0 data response from an upstream registry (reproduced
against IMF SDMX Central `IMF.RES:DSD_WEO(9.0.0)` on 2026-05-22;
full samples in `imf_data_weo.json` (18 MB) and `proxy_data_weo.json`
(22 MB) at the repo root):

| # | Symptom                                                                                          | Severity                  |
|---|--------------------------------------------------------------------------------------------------|---------------------------|
| 1 | Three series silently relocated under a different INDICATOR code (8200 series in → 8197 out)     | **silent data corruption** |
| 2 | `data.dataSets[0].dimensionGroupAttributes` dropped entirely (145 reports lost) and the matching `structures.attributes.dimensionGroup` section absent | data loss                 |
| 3 | `roles` (plural array) emitted as `role` (singular string or null) on every component            | schema violation          |
| 4 | Metadata-attribute usages over-attached to `dataset`/`series` (18 + 41 + 3) vs upstream (0 + 4 + 2 + 17 dimensionGroup) | semantic loss             |
| 5 | TIME_PERIOD value shape rewritten from non-coded `{"value":"1999"}` to coded `{"id","name","start","end"}` with fabricated date bounds | schema violation          |

Issue #79 (design 025) covered analogous losses on the *structure*
endpoint and is implemented. This design covers the *data* endpoint.
The two designs share root causes only for Group A fixes (writer/mapper
sees fewer fields than the spec defines); the data endpoint adds three
new categories: silent series misrouting, dimension-group attribute
support, and the inline-structure side effects of the design 025 Stage
2 fixture.

## Reproduction

A focused unit test
`StreamingDataConversionServiceTest.shouldNotMisrouteSeriesAcrossIndicatorPositions_issue80`
trims the 18 MB IMF response to the three countries that trigger the
misroute (AGO, MAC, VEN; 111 series, 145 INDICATOR values). The test
asserts 111 output series and the three specific labels. Pre-fix it
fails with `expected: <111> but was: <108>`. The trimmed sample is
checked in at
`sdmx-proxy/src/test/resources/com/epam/sdmxproxy/services/adapter/data_conversion/data_weo_misroute_3countries.json`
(118 KB).

## Problem

### Group A — sdmx-core writer ignores fields the SDMX-JSON 2.0 spec defines

**Issue 3 (`role` vs `roles`).**
`AbstractJsonDataWriter.writeComponent`
(`fusion-sdmx-json/.../AbstractJsonDataWriter.java:607-612`) hardcodes:

```java
if (isTime) {
    jsonGenerator.writeStringField("role", "time");
} else {
    jsonGenerator.writeNullField("role");
}
```

Singular field name, string-or-null value, no read of the component's
`conceptRoles` list at all. The SDMX-JSON 2.0 data schema
(`sdmx-json-2.0.0/.../sdmx-json-data-schema.json:456,489,524`) defines
the field on dimension, measure, and attribute as `roles` (plural
array of strings matching `^[A-Za-z][A-Za-z0-9_-]*$`). Counts in the
issue samples: upstream emits `roles` 27 times, proxy emits `role`
67 times; the role *values* themselves are also dropped to `null`
(latent same-shape bug fixed on the structure-endpoint mapper in
design 025 Stage 1, but the data-writer code path is independent).

**Issue 5 (TIME_PERIOD value shape).**
`AbstractJsonDataWriter.writeCode`
(`fusion-sdmx-json/.../AbstractJsonDataWriter.java:628-661`) hardcodes
the coded shape for any component whose id equals `TIME_PERIOD`:

```java
if (isTime) {
    String start = DateUtil.formatDate(DateUtil.formatDate(code, true), TIME_FORMAT.DATE_TIME);
    String end = DateUtil.formatDate(DateUtil.formatDate(code, false), TIME_FORMAT.DATE_TIME);
    jsonGenerator.writeStringField("start", start);
    jsonGenerator.writeStringField("end", end);
}
jsonGenerator.writeStringField("id", id);
jsonGenerator.writeStringField("name", name);
```

The SDMX-JSON 2.0 schema
(`sdmx-json-2.0.0/.../sdmx-json-data-schema.json:651`) allows two
distinct shapes for a TIME_PERIOD value:

- Coded: `{"id":"2024-Q1","name":"...","start":"...","end":"..."}` when
  the dimension has an enumerated representation.
- Non-coded `ObservationalTimePeriod`: `{"value":"1999"}` for
  observation periods declared with a text representation.

sdmx-core has no way to tell which shape the upstream used (the reader
doesn't carry that distinction through). The writer always emits the
coded shape with fabricated `start`/`end` bounds derived from the date
string, which is incorrect for the IMF WEO DSD (TIME_PERIOD is
non-coded there).

### Group B — `dimensionGroupAttributes` unsupported end-to-end

**Issue 2.** Two-sided gap in sdmx-core:

- The writer's structure-side `SeriesDataWriter.writeAttributes`
  (`fusion-sdmx-json/.../SeriesDataWriter.java:277-313`) emits only
  `dataset`, `series`, and `observation` sections; group attributes
  are appended to `series` via `attr.addAll(dsd.getGroupAttributes())`
  with no `dimensionGroup` section emitted.
- The writer's value-side has no code path to emit
  `data.dataSets[].dimensionGroupAttributes[]` at all. The
  `GroupDataWriterEngine` two-pass mechanism rewrites group attrs as
  series-level attrs (combining via
  `FlusingProxyDataWriterEngine.writeKey`), so by the time the JSON
  writer sees the data, group identity is gone.

The IMF WEO response emits 145 dimension-group attribute reports keyed
by `:idx::` (i.e. one COUNTRY position, empty INDICATOR/FREQUENCY) and
declares 17 dimension-group-attached attributes. The proxy emits zero
of either.

### Group C — Metadata-attribute usages over-attached as a side effect of design 025 Stage 1

> **Resolved by [design 027](../027-remove-metadata-attribute-usage-to-attribute/DESIGN.md)
> (2026-05-25).** The `MetadataAttributeUsageToAttributeJsonFixture` was
> removed. `PRESERVE_METADATA_ATTRIBUTE_USAGES` continues to keep the
> structure response correct on its own. The historical analysis below is
> retained for context.

**Issue 4.** The `MetadataAttributeUsageToAttributeJsonFixture`
(`MetadataAttributeUsageToAttributeJsonFixture.java:191-205`) runs on
the *structure* endpoint and is wired into the IMF SDMX 3.0 config
(`sdmx_registries_config.json:107`). It rewrites the upstream DSD's
`metadataAttributeUsages` into regular `attributes` whose
`attributeRelationship` is copied verbatim from the usage:

```java
attr.set("attributeRelationship", usage.path("attributeRelationship").deepCopy());
```

For IMF, the usage relationship is `{"none":{}}` in most cases, which
sdmx-core's DSD reader maps to `ATTRIBUTE_ATTACHMENT_LEVEL.DATA_SET`.
The data endpoint pre-fetches the DSD (via the structure endpoint) and
reuses the resulting SdmxBeans. The data writer then iterates
`dsd.getDatasetAttributes()` and emits 18 dataset-level entries — none
of which exist in the upstream's data response. The issue's count
breakdown (`attributes.dataset: 0 → 18`, `series: 4 → 41`,
`dimensionGroup: 17 → 0`) is the combined effect of this promotion and
the dimensionGroup-section loss from issue 2.

The fixture loses the underlying metadata attribute's original
attachment because the *usage* relationship and the *attribute*
relationship in the MSD are different facets in SDMX-JSON 2.0. The
usage says "where in the data does this metadata attach" (which is
what we want); the attribute itself doesn't have a direct attachment
level. So `{"none":{}}` on the usage means "metadata applies at
dataset level", which sdmx-core interprets correctly. The damage is
that the proxy is *creating* dataset-level attributes that never
existed in upstream's `attributes` list — they only existed as
metadata-attribute usages with an attachment hint.

### Group D — Silent series misrouting

**Issue 1.** Three of 8200 series in the upstream IMF WEO response are
silently relocated under a different INDICATOR code in the proxy
output:

| Upstream `(COUNTRY, INDICATOR, FREQUENCY)` | obs | Proxy key the data ends up under |
|---|---|---|
| `(MAC, GGX_NGDP, A)`       | 31 | `(MAC, GGXWDG_NGDP, A)` |
| `(VEN, NGDPRPPPPC, A)`     | 48 | `(VEN, NGDPRPC, A)`     |
| `(VEN, NGDP_RPCH, A)`      | 48 | `(VEN, NGDP_R, A)`      |

The pattern is consistent:

- Both INDICATOR codes in each pair exist in upstream and in proxy
  (same 145-code set, different order — upstream is codelist-defined
  order, proxy is first-seen-during-iteration order which happens to
  equal alphabetical because the upstream emits series alphabetically).
- The "lost" indicator's proxy position is *exactly one* greater
  than the "gained" indicator's proxy position
  (GGX_NGDP=11/GGXWDG_NGDP=10, NGDPRPPPPC=19/NGDPRPC=18,
  NGDP_RPCH=23/NGDP_R=22).
- The affected country has the lost indicator but **not** the gained
  (alphabetically prior) indicator in upstream. Other countries have
  both.

The IMF WEO DSD has two groups (`GROUP_INDICATOR` and
`GROUP_COUNTRY__INDICATOR`), which activates the
`GroupDataWriterEngine` two-pass mechanism:

1. Pass 1: the reader yields each series with an optional preceding
   Group key. Series are streamed to a Kryo-backed temporary store
   (`KryoTemporaryDataManager`). Group keys are captured into
   `ReportedGroupAttributesValues`.
2. Pass 2 (`flushSeriesAndAddGroups`): re-reads the temp store in
   FIFO order, calls
   `groupAttributes.getAttributesForSeries(key)` for each series,
   wraps the key with combined attributes, and writes via
   `FlusingProxyDataWriterEngine` to the real
   `SdmxJsonSeriesDataWriterV2`. The real writer's
   `DatasetInfoDataWriterEngine` builds its values list and
   position-index map (`indexOfCode`, an
   `Object2IntAVLTreeMap<String>`) by first-seen order.

Static reading of sdmx-core does not pinpoint where the off-by-one
encoding lands. Three candidate sites remain:

- `DatasetInfoDataWriterEngine.addKeyValue` (line 139-145): a
  membership check in `componentCodes: Set<String>` keyed by
  `concept:code` short codes. A bug here would skip insertion and
  leave the lookup map without the new code, falling back to the AVL
  tree's default-int return (0), not idx-10. So this is unlikely to
  be the cause on its own.
- `SdmxJsonStructureIterator.ComponentIterator.next` (reader side):
  a state machine with a `pos == 1` rewind heuristic that flips
  between "coded" and "uncoded" mode based on whether `id` is seen
  first. The IMF JSON value entries are all `{"id":"..."}` only, so
  the rewind shouldn't fire — but this code is fragile and is the
  most likely site of a corner-case bug.
- `GroupDataWriterEngine` two-pass interaction: in pass 2, the Kryo
  reader emits series in the order they were written. Each series's
  KeyValues come from KryoDeserialized objects which should match the
  pass-1 KeyValues. If serialization conflates instances by
  short-code (the KeyValueImpl cache uses `id+":"+value[0]` keys and
  SoftReferences), a previously-cached GGXWDG_NGDP KeyValue could in
  principle be returned for a GGX_NGDP lookup — but that would
  require the cache key generation to be wrong, which it is not.

Resolving issue 1 requires either reproducing under the debugger to
identify the exact sdmx-core site, or sidestepping the two-pass
mechanism entirely.

## Non-goals

- **Modifying sdmx-core source.** Same precedent as design 025:
  sdmx-core is consumed as a binary, behaviour gaps go through
  override classes under `services/sdmxsource/` or fixtures.
- **Generalising to non-IMF registries.** Group A fixes apply
  universally on the writer side; Groups B–D either apply only when
  the upstream emits the relevant section (B), or are scoped to
  registries that go through the design 025 Stage 1 fixture (C). No
  per-registry audit in this design.
- **Bypass mode for IMF data.** Was considered as a mitigation for D
  (the misroute) but rejected because (a) the response then loses
  every other fix the proxy applies, including future ones, and (b)
  bypass requires the upstream and client formats to be the same
  string — fragile when clients ask for SDMX-JSON 2.0 with parameters
  the upstream doesn't emit.
- **Availability endpoint.** Out of scope here; the misroute and
  shape bugs likely apply but require independent verification.

## Solution

Four staged fixes, ordered from lowest to highest risk. Each stage is
shippable on its own.

### Stage 1 — Group A: writer overrides for `roles` and TIME_PERIOD shape

Two custom writer engines extending sdmx-core's writers, registered
through the existing `CustomSdmxJsonDataWriterFactory` so the
overrides activate for SDMX-JSON 2.0 only.

**1a. `roles` field name and value.**

A `CustomSdmxJsonSeriesDataWriterV2` (and a sibling
`CustomSdmxJsonFlatDataWriterV2`) overrides `writeComponent` to:

- Always emit `roles` as a JSON array.
- For time dimensions, include `"time"` plus any
  `conceptRoles` exposed on the component's bean.
- For all other components, look up `conceptRoles` from the
  underlying `ComponentBean` (sdmx-core's
  `DimensionBean.getConceptRole()`/`AttributeBean.getConceptRoles()`)
  and serialise each as the local id portion of the URN. When the
  list is empty, emit `"roles": []` (the schema accepts an empty
  array per `minItems: 0`).

To override `writeComponent` we need access to the underlying
`ComponentSuperBean.getBuiltFrom()` (already used elsewhere in the
writer) and the JSON generator. Both are protected members of
`AbstractJsonDataWriter`. Subclassing
`SdmxJsonSeriesDataWriterV2`/`SdmxJsonFlatDataWriterV2` and overriding
the protected `writeComponent` method is sufficient.

Wiring: `CustomSdmxJsonDataWriterFactory.getDataWriterEngine` swaps
`SdmxJsonDataWriterEngineV2` for a thin subclass whose
`startDataset` instantiates `CustomSdmxJsonSeriesDataWriterV2` /
`CustomSdmxJsonFlatDataWriterV2` instead of the stock versions.

**1b. TIME_PERIOD non-coded value shape.**

Same custom writer overrides `writeCode` (or splits writeComponent's
TIME_PERIOD branch) to detect whether the time dimension's component
has an enumerated representation:

```java
boolean coded = component.getCodelist(true) != null;
```

If coded: keep the existing coded shape.
If non-coded: emit `{"value":"1999"}` per the schema's
`ObservationalTimePeriod` branch.

Detection lives on the component's super-bean, which is available
through `currentDatasetInfo.getCurrentDSDSuperBean()`. The IMF WEO
DSD declares TIME_PERIOD with a text representation, so this branch
fires and the proxy stops fabricating `start`/`end` bounds.

**Tests.** Extend
`StreamingDataConversionServiceTest` with assertions on the JSON shape:

- `data.structures[0].dimensions.observation[0].roles` is a JSON array
  containing `"time"`.
- `data.structures[0].dimensions.observation[0].values[0]` has a
  `value` key and no `start`/`end`/`id`/`name` keys for IMF WEO
  TIME_PERIOD.

### Stage 2 — Group C: rescue metadata-attribute usages on the data endpoint

Apply the design 025 Stage 2 Step 2 pattern (cache from raw input,
re-inject post-write) to the *data* endpoint:

1. New raw-stream data fixture
   `JSON_2_0_RESCUE_METADATA_ATTRIBUTE_USAGES_DATA`. Runs *before*
   `MetadataAttributeUsageToAttributeJsonFixture` would (the data
   endpoint doesn't actually configure that fixture today —
   see "Notes" below). Walks
   `data.dataStructures[].dataStructureComponents.attributeList.metadataAttributeUsages`,
   stashes a deep copy keyed by DSD URN in a request-scoped
   `MetadataAttributeUsageRescueCache` bean (same bean shared with
   the design 025 Stage 2 structure path).
2. Post-write injection: a new step in `AdapterRouterImpl` (or a
   dedicated `DataOutputFixture` concept mirroring
   `StructureOutputFixture` from design 025) walks the *written-out*
   JSON, re-inserts `metadataAttributeUsages` onto each DSD whose
   URN is in the cache, and (critically) **removes from
   `attributes.dataset` any attributes that were introduced solely by
   the design 025 Stage 1 fixture** so the proxy's structure sidecar
   in the data response matches what upstream would have emitted.
3. The set of "synthetic" attributes is identified by either (a)
   tagging the fixture-introduced attributes with a sentinel
   annotation on raw rewrite and stripping them on output, or (b)
   recomputing the difference between the pre-fixture and
   post-fixture attribute lists at structure-fetch time and stashing
   the diff in the same request-scoped cache.

**Notes.**

- The current IMF data endpoint config has *no* fixtures. The
  metadata-attribute promotion is happening through the *pre-fetched
  DSD*, which is itself the output of the structure endpoint with
  fixtures applied. So the fix lives on the structure-endpoint
  fixture (avoiding fixture-side promotion, deferring to the rescue
  pattern). The "data fixture" framing above is an alternative if
  the structure-endpoint fix isn't enough — e.g. if the data
  response carries its own metadataAttributeUsages independent of
  what was fetched at structure time.
- This stage cannot fully fire until design 025 Stage 2 Step 2 lands
  (currently incomplete — design 025 says "Detailed design and
  implementation defer to a Stage-2-only follow-up once Stage 1 is
  in", and the rescue infrastructure isn't built yet). This design
  proposes to share the rescue cache across both endpoints.

### Stage 3 — Group B: dimension-group attributes end-to-end

Requires writer and reader extensions in sdmx-core for both the
structure side (the `dimensionGroup` array under `attributes`) and
the value side (the `dimensionGroupAttributes` map under each
dataset).

**3a. Structure side.** Override `SeriesDataWriter.writeAttributes`
(via the custom V2 writer from Stage 1) to emit a separate
`dimensionGroup` array containing the DSD's group attributes,
**removing** them from the `series` section. The structure of each
attribute object stays the same (`id`, `name`, `relationship`,
`values`); only the section placement changes.

**3b. Value side.** Replace `GroupDataWriterEngine`'s two-pass
"convert group attrs to series attrs" with a one-pass scheme that
collects group attribute reports per group-key (e.g.
`"COUNTRY-INDICATOR" -> "MAC-GGX_NGDP" -> [attribute index per group attr]`)
and emits them as `dimensionGroupAttributes` in the writer's
`close()` (before the structure sidecar is written so the indices
match the values lists).

The group-key encoding follows SDMX-JSON 2.0:
`<COUNTRY_idx>:<INDICATOR_idx>::` where `:` separators delimit each
DSD dimension position and empty positions indicate dimensions not
covered by the group. This needs the writer to know group definitions
(available via the DSD `getGroups()` already used by the reader).

**Risk.** Replacing `GroupDataWriterEngine` for SDMX-JSON 2.0 may
disturb the misroute (Stage 4) — possibly fixing it as a side effect,
since the misroute is suspected to live in the two-pass machinery.
The Stage 4 reproducer test will tell us either way.

**Tests.** New tests on the trimmed reproducer asserting:

- `data.structures[0].attributes.dimensionGroup` is present and
  matches upstream's group attributes by id (17 entries for IMF WEO).
- `data.dataSets[0].dimensionGroupAttributes` is a non-empty object
  whose keys are valid group-key encodings and values are integer
  arrays of the same length as the dimensionGroup attribute list.
- `data.structures[0].attributes.series` no longer contains the 17
  promoted attributes.

### Stage 4 — Group D: series misrouting

Gated on root-cause identification. The reproducer test landed in
this design (see Reproduction above) is the entry point.

Two paths to explore in order:

1. **Bypass the `GroupDataWriterEngine` two-pass on SDMX-JSON 2.0.**
   If Stage 3 replaces the two-pass with a one-pass scheme, the
   misroute may disappear as a side effect. Validate via the
   reproducer test: if pre-Stage-3 it fails with 108/111 and
   post-Stage-3 it passes, Stage 4 is done.
2. **Fix the underlying sdmx-core site.** If Stage 3 doesn't fix it,
   instrument `DatasetInfoDataWriterEngine.addKeyValue` and
   `getReportedIndex` to log the (concept, code) → index assignments
   on the reproducer and inspect the mismatched indicator entries.
   Likely sites:
   - `Object2IntAVLTreeMap` use with a sliced/predecessor lookup
     (unlikely — it's exact-lookup) — verify via a unit test that
     fastutil returns the default int (0) for missing keys, not a
     neighbour's value.
   - `KeyValueImpl.getInstance` SoftReference cache returning a
     stale/wrong instance under memory pressure (the cache is
     synchronized and keyed by `id+":"+value[0]`, but the test
     bench can force-evict by setting a small heap).
   - `ComponentIterator.next` state-machine corner case during
     reader parsing (the `pos == 1` rewind heuristic is the most
     suspicious — even if it doesn't fire on the IMF JSON's
     `{"id":"..."}` -only entries, there may be a state-leak across
     dimensions if the same iterator is reused).

The reproducer narrows the search to a 12-second test, so each
hypothesis can be verified in a tight loop once a hypothesis is in
place.

## Implementation Plan

### Stage 1, Step 1 — Custom writer engines

**New file:**
`sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/sdmxsource/CustomSdmxJsonDataWriterEngineV2.java`
— thin subclass that overrides `startDataset` to install the custom
series/flat writers.

**New file:**
`sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/sdmxsource/CustomSdmxJsonSeriesDataWriterV2.java`
— overrides `writeComponent` and `writeCode` for the `roles` plural
field and the `value` non-coded shape.

**New file:**
`sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/sdmxsource/CustomSdmxJsonFlatDataWriterV2.java`
— same overrides for the flat (`dimensionAtObservation=AllDimensions`)
writer.

**Edit:** `CustomSdmxJsonDataWriterFactory.getDataWriterEngine` —
return `CustomSdmxJsonDataWriterEngineV2` for `SDMXJSON_2_0_0`.

### Stage 1, Step 2 — Tests

Add to `StreamingDataConversionServiceTest`:

- `shouldEmitRolesPluralOnTimeDimension`: feed IMF WEO data, assert
  `data.structures[0].dimensions.observation[0].roles` is array
  `["time"]`.
- `shouldEmitNonCodedTimePeriodValueShape`: assert
  `data.structures[0].dimensions.observation[0].values[0]` has key
  `value` and lacks `start`/`end`.
- `shouldNotFabricateStartEndBoundsOnTimePeriod`: assert any
  `value` entry serializing as `{"value":"1999"}` exactly (no
  surrounding fields).

### Stage 2+ — Detailed plans defer

Per design 025's precedent: Stage 2 (Group C) and Stage 3 (Group B)
have architecture-level shapes above but their concrete file changes
are scoped on a follow-up once Stage 1 ships and the rescue-cache
infrastructure from design 025 Stage 2 Step 2 is in place. Stage 4
(Group D) gates on Stage 3's outcome.

## Risks and unknowns

- **The reproducer is fragile to non-determinism.** The 3-country
  trim relies on the upstream ordering (alphabetical) producing the
  same misroute pattern as the full file. Verified empirically on
  2026-05-22 (3 misroutes match the issue). If sdmx-core changes
  iteration order in a future bump, the trim may need updating.
- **Custom writer engines bypass sdmx-core's test coverage.** Each
  override is a fresh code path. We need unit tests for the override
  itself, not just integration tests.
- **The Stage 1 `roles` change is breaking for clients that read
  `role` (singular).** Per the SDMX-JSON 2.0 schema this is the
  *correct* shape, but downstream consumers that hard-coded the
  current proxy output will need updating. Stat-GPT-backend (the
  primary consumer) should be checked.
- **Stage 3 replacing `GroupDataWriterEngine` may introduce
  per-format divergence** — sdmx-core's two-pass is shared between
  JSON 1.0 and JSON 2.0. The Stage 3 design replaces it for JSON 2.0
  only; JSON 1.0 keeps the two-pass. Format selection at writer
  factory time.
- ~~**The Stage 2 rescue cache shape needs to be designed jointly with
  design 025 Stage 2 Step 2.**~~ Obsolete: Stage 2 is no longer needed
  (design 027 removed the workaround at its source).
- ~~**Issue 4's structure-fetch-time damage is the cleanest fix
  surface, but the proxy's design 025 Stage 1 already promotes the
  attributes**. Reverting Stage 1's fixture is not on the table
  because the structure-endpoint use case depends on it.~~ Obsolete:
  the workaround *was* reverted in design 027 because
  `PRESERVE_METADATA_ATTRIBUTE_USAGES` covers the structure-endpoint
  use case on its own, without forging synthetic attributes.
