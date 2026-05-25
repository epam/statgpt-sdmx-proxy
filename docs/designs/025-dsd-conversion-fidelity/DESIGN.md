# Design 025: Restore DSD conversion fidelity for SDMX-JSON 2.0 structure responses

**Status:** implemented (2026-05-22). Tracks
issue [#79](https://github.com/epam/statgpt-sdmx-proxy/issues/79).
Stage 1 + both Stage 2 fixtures landed; Stage 2 Step 2 implemented as the
inline pre/post buffer variant described under "Recommended approach" /
"Stage 2, Step 2" below (no request-scoped Spring bean, no fixture-chain
state — capture and inject happen inside `AdapterRouterImpl.getStructuresConversion`
with local variables, gated by a `PRESERVE_METADATA_ATTRIBUTE_USAGES`
marker in the endpoint's fixture list).

**Follow-up (2026-05-25, [design 027](../027-remove-metadata-attribute-usage-to-attribute/DESIGN.md)):**
the legacy `METADATA_ATTRIBUTE_USAGE_TO_ATTRIBUTE` fixture has been
removed. `PRESERVE_METADATA_ATTRIBUTE_USAGES` is now the sole mechanism
by which `metadataAttributeUsages` survives the round trip. References to
the removed fixture below are kept for historical context only.

## Context

Issue #79 reports four pieces of structural information that the proxy
loses when converting an SDMX-JSON 2.0 DSD response from an upstream
registry (reproduced against IMF SDMX Central `IMF.RES:DSD_WEO(9.0.0)`
on 2026-05-22; full samples in `imf_dsd_weo.json` and
`proxt_dsd_weo.json` at the repo root):

| # | Upstream emits                                                                | Proxy emits                                    |
|---|-------------------------------------------------------------------------------|------------------------------------------------|
| 1 | `{"id":"origin","value":"INTEGRATION"}`                                       | `{"id":"origin","text":null,"texts":{}}`       |
| 2 | `metadataAttributeUsages: [ {...} x 33 ]` (DOI, AUTHOR, TOPIC, METHODOLOGY, …) | `metadataAttributeUsages: []`                  |
| 3 | `"metadata": "urn:sdmx:...MetadataStructure=IMF.RES:MSD_WEO_METADATA_EXTERNAL(2.0+.0)"` | key absent                                     |
| 4 | `FREQUENCY` dimension `conceptRoles: ["urn:...SDMX_CONCEPT_ROLES(1.0).FREQ"]` | `conceptRoles: []`                             |

All four pieces are valid per the SDMX-JSON 2.0.0 structure schema
(`sdmx-json-2.0.0/structure-message/tools/schemas/2.0.0/sdmx-json-structure-schema.json`)
and are emitted by the upstream registry. The proxy is configured for
the IMF SDMX 3.0 endpoint with `bypassEnabled: false`, so the same
SDMX-JSON 2.0 wire format is round-tripped through:

```
upstream JSON
  -> StructureFixtureService.applyFixtures (raw-stream rewrites)
  -> sdmx-core SdmxJsonStructureReaderManagerV2 -> SdmxBeans
  -> StructureMapperImpl                        -> jsdmx Artefacts
  -> jsdmx SDMX-JSON 2.0 writer
  -> client JSON
```

The conversion is not pure passthrough because the IMF config relies
on the `METADATA_ATTRIBUTE_USAGE_TO_ATTRIBUTE` and `VERSION_WILDCARD`
fixtures to patch upstream quirks before parsing.

The losses fall into two groups depending on where in that pipeline
they originate.

## Problem

### Group A -- proxy mapper drops information sdmx-core preserved

**Issue 3 (DSD `metadata` URN).** `SdmxJsonDataStructureReaderEngineV2`
calls `mutable.setMSDReference(...)` when it sees the `metadata`
field (file
`fusion-sdmx-json/src/main/java/io/sdmx/format/json/engine/structure/reader/sdmx/v2/SdmxJsonDataStructureReaderEngineV2.java:73`).
The immutable `DataStructureBean` exposes it via `getMSDRef()`. jsdmx's
`DataStructureDefinitionImpl` has `setMetadataStructure(ArtefactReference)`.
The proxy's `DataStructureMapper.map` reads `agencyId`, `id`,
`version`, names, descriptions, annotations, and the four descriptors
-- but never calls `getMSDRef()`, so the URN is dropped on the
SdmxBeans -> jsdmx hop.

**Issue 4 (dimension `conceptRoles`).** Same shape:
`SdmxJsonDataStructureReaderEngineV2.readSingleDimension` handles
`conceptRoles` (file
`fusion-sdmx-json/.../SdmxJsonDataStructureReaderEngineV2.java:238-241`)
and calls `dim.setConceptRole(...)` on the mutable bean. The immutable
`DimensionBean.getConceptRole()` (note: singular) exposes the list.
jsdmx's `DimensionImpl.setConceptRoles(List<ArtefactReference>)`
accepts it. The proxy's `DataStructureMapper.mapComponent` copies
`id`, `conceptIdentity`, `localRepresentation`, and `annotations`
only -- `conceptRoles` is dropped.

By inspection the same omission applies to `AttributeBean.getConceptRoles()`
(plural) and to measures (sdmx-core's `MeasureDimensionBean` has no
conceptRoles getter, so attributes are the only other affected
component type). The IMF sample happens to have empty conceptRoles
on every attribute, so the latent attribute-side bug is not visible
in the reproduction.

### Group B -- sdmx-core itself drops information before the mapper sees it

**Issue 1 (annotation `value`).** SDMX-JSON 2.0 defines `Annotation`
with both a non-localised `value` (string) and a localised
`text`/`texts` pair (see
`sdmx-json-2.0.0/.../sdmx-json-structure-schema.json:749-782`). sdmx-core's
generic annotation reader `SdmxJsonAnnotableUtil.buildAnnotation`
(file `fusion-sdmx-json/.../reader/util/SdmxJsonAnnotableUtil.java:49-82`)
handles `title`, `id`, `type`, `links`, `texts`, `text` -- and silently
ignores `value`. The downstream `AnnotationBean` API has no
`getValue()` either; the field is unrepresentable in sdmx-core. The
proxy's `AnnotationMapper.map(AnnotationBean)` therefore has nothing
to read, and the resulting jsdmx `AnnotationImpl` ends up with
`text=null` and an empty `texts` map, which is what we see in the
proxy output.

**Issue 2 (`metadataAttributeUsages`).** Two compounding problems:

1. `SdmxJsonDataStructureReaderEngineV2.getAttributes` only handles
   the `attributes` field; the `metadataAttributeUsages` sibling field
   is silently skipped (file
   `fusion-sdmx-json/.../SdmxJsonDataStructureReaderEngineV2.java:247-296`).
2. Even if it were read, sdmx-core has no model slot for it on the
   DSD side. `AttributeListBean` and `AttributeListMutableBean`
   expose `getAttributes` only; metadata attribute usages are
   modelled on the *metadata structure* side
   (`IMetadataAttributeContainerMutableBean`), not the DSD attribute
   list.

The existing
`MetadataAttributeUsageToAttributeJsonFixture` (registered for IMF in
`sdmx_registries_config.json`) papers over this by rewriting
`metadataAttributeUsages` into regular `attributes` at the raw-JSON
stage -- but only when the referenced MSD is included in the same
response. A bare
`GET /structure/datastructure/IMF.RES/DSD_WEO/9.0.0` request returns
the DSD alone, so the fixture finds no MSD in the JSON and skips the
DSD entirely (`MetadataAttributeUsageToAttributeJsonFixture.java:133`).

The proxy has no infrastructure to fetch the MSD as a side effect of
serving the DSD.

## Non-goals

- **Modifying sdmx-core source.** sdmx-core is an external EPAM fork
  consumed as a binary. Behaviour gaps are addressed via custom
  override classes (see [[reference_bug_report_template]] precedent:
  `CustomSdmxJsonHierarchicalCodelistReaderEngineV2`). Patching the
  upstream JAR is out of scope.
- **Restoring annotation `value` *and* annotation `texts` round-trip
  fidelity for the same annotation.** SDMX-JSON 2.0 allows both, but
  the IMF samples only ever populate one of the two. If a single
  annotation comes in with both, we keep both -- but designing for
  conflict resolution between the two is out of scope.
- **Fetching the MSD as a side request to populate
  `metadataAttributeUsages` via the existing
  `METADATA_ATTRIBUTE_USAGE_TO_ATTRIBUTE` fixture.** That is a
  reasonable future enhancement but is its own design (involves
  registry round-trips, caching, error handling on partial failure).
  This design keeps the usages on the wire untouched instead.
- **Generalising to non-IMF registries.** The fix is registry-agnostic
  on the mapper side (Group A); for Group B we describe behaviour
  changes that apply to every SDMX-JSON 2.0 source -- but we do not
  audit other registries for similar quirks in this design.
- **Data and availability endpoints.** Only the structure endpoint is
  in scope, matching the issue report. The DSDs returned via inline
  references in data responses may exhibit the same losses; those
  reuse the same `DataStructureMapper` path and inherit the Group A
  fix transparently, but no separate verification is planned here.

## Current architecture (relevant slices)

`StructureMapperImpl.map(SdmxBeans)` delegates per artefact type. For
DSDs:

```
DataStructureMapper.map(DataStructureBean)
    dsd.setOrganizationId / setId / setVersion       -- maintainable fields
    dsd.setName / setDescription / setAnnotations    -- nameable fields
    mapDimensions(dimensionDescriptor, dataStructure)
        mapDimension -> mapComponent(dim, dimensionBean)
            component.setId / setConceptIdentity / setLocalRepresentation / setAnnotations
    mapMeasures(...)        -- same mapComponent
    mapAttributes(...)      -- same mapComponent + mapRelationship
    mapGroupDimensionDescriptors(...)
```

Three call sites are missing today:

- `dsd.setMetadataStructure(...)` -- never called.
- `setConceptRoles(...)` on `DimensionImpl` and `DataAttributeImpl`
  -- never called.

Generic annotation handling sits in `AnnotationMapper.map(AnnotationBean)`
which reads `getId/getTitle/getUri/getText/getType` -- the bean does
not expose `value`, so even adding a `setValue` here would not help
without a richer bean.

Fixtures execute upstream of sdmx-core (`StructureFixtureService.applyFixtures`
runs on the raw `InputStream` before the parser sees it -- file
`sdmx-proxy/.../fixture/structure/StructureFixtureService.java:34-54`).

## Solution

Two unrelated fixes, in two distinct layers:

### Fix Group A inside the proxy mapper (issues 3 and 4)

`DataStructureMapper.map` learns three new lines (one for MSD ref, two
for conceptRoles via `mapComponent` enrichment), with the underlying
references built through the existing `ReferenceMapper`. No sdmx-core
override required; no extension API; no new dependency. This restores
the DSD-level `metadata` URN and conceptRoles for both dimensions and
attributes for *every* registry that ships them through SDMX-JSON 2.0
or SDMX-ML 3.0 (since sdmx-core's readers already populate the beans
for both wire formats).

### Fix Group B via SDMX-JSON 2.0 reader overrides + a richer annotation bean (issues 1 and 2)

Both group-B failures share the same root cause: sdmx-core's
SDMX-JSON 2.0 readers ignore fields the spec defines. The proxy
already has the override pattern in `services/sdmxsource/` for exactly
this class of bug
(`CustomSdmxJsonHierarchicalCodelistReaderEngineV2`). Apply it twice:

**B1: `CustomSdmxJsonAnnotableUtil` + `CustomAnnotationMutableBean`
(annotation `value`).** Provide a custom annotation reader that
captures the SDMX-JSON 2.0 `value` field; carry it through a wrapper
mutable bean that extends `AnnotationMutableBeanImpl` with a `value`
slot; reflect the value back into a wrapper immutable bean implementing
`AnnotationBean` + `getValue()`. The proxy's `AnnotationMapper`
detects the wrapper (instanceof check) and calls `setValue` on the
jsdmx `AnnotationImpl`. Annotations sourced from non-JSON-2.0 readers
keep working unchanged because the wrapper is only produced by the
overridden reader.

This is invasive because the annotation reader is invoked from
*every* SDMX-JSON 2.0 reader engine in sdmx-core (via the shared
`SdmxJsonAnnotableUtil.processBean` helper). We cannot subclass and
re-register one reader -- we need sdmx-core to call the custom
util everywhere. There are two ways:

- **Reflection-based field swap on `SdmxJsonAnnotableUtil`** to
  replace the static `buildAnnotation` behaviour. Risky.
- **Bytecode patch** at build time. Out of scope.
- **Reader-engine subclass per artefact type** that overrides only
  the call sites we care about (DSDs at minimum) -- doable but
  high effort and incomplete (loses `value` on codelist items,
  concept scheme items, etc.).

A pragmatic alternative avoids touching sdmx-core's annotation
pipeline entirely:

**B1' (recommended): rescue annotation `value` at the JSON level via
a new structure fixture.** Add a `JSON_2_0_VALUE_TO_TEXT` fixture
that walks `data.*[].annotations[]`, and for each annotation with a
`value` and no `text`/`texts`, rewrites `value` -> `text` (single
non-localised string). sdmx-core does read `text`, so the value
survives the round trip and reappears as `text` in the output.

Trade-off: the *type* of the field changes (`value` -> `text`).
Clients that interpret `text` as a localised string will see a
non-localised value. The SDMX-JSON 2.0 schema declares `text` as
`localisedBestMatchText` (a plain string), so this is type-correct;
only the semantics shift slightly. Documented in the fixture javadoc.

**B2: `CustomSdmxJsonDataStructureReaderEngineV2` +
`AttributeListMutableBean` enrichment (`metadataAttributeUsages`).**
Reading the field is straightforward (mirror sdmx-core's
`getAttributes` and add a `case "metadataAttributeUsages"` arm).
Carrying it is the hard part: sdmx-core's
`AttributeListMutableBeanImpl` has no slot. Two paths:

- **B2a: extend the mutable bean.** Provide
  `CustomAttributeListMutableBean` with a `List<MetadataAttributeUsageData>`
  field; have the custom reader instantiate it instead of the
  sdmx-core default. Reflect through a paired immutable wrapper or
  side-channel. The mapper then reads the wrapper and populates
  `AttributeDescriptorImpl.setMetadataAttributes(...)` on the jsdmx
  side. Complex because sdmx-core's
  `DataStructureMutableBeanImpl.setAttributeList(...)` calls
  `super.setAttributeList(...)` which does various conversions; we
  need to verify the wrapper survives.
- **B2b (recommended): side-channel cache keyed by DSD URN.** During
  fixture execution (which already runs on raw JSON), walk
  `data.dataStructures[]`, extract each DSD's URN
  (`urn:sdmx:org.sdmx.infomodel.datastructure.DataStructure=<agency>:<id>(<version>)`),
  and stash `metadataAttributeUsages` keyed by URN in a request-scoped
  `MetadataAttributeUsageCache` (Spring `@RequestScope`). After
  `StructureMapperImpl` finishes, an additional pipeline step (or
  the mapper itself, injected with the cache) replays the cached
  usages onto the jsdmx `AttributeDescriptorImpl.metadataAttributes`.
  The cache is thrown away at end of request.

Both B1' and B2b keep the fix inside `sdmx-proxy/` source -- no
sdmxsource overrides, no bean wrappers, no reflection. They cost a
second pass over the structure JSON but only for endpoints/registries
that opt in.

## Recommended approach

Implement in two stages so the easy wins ship first and the harder
work is scoped on its own.

**Stage 1 (issues 3 + 4): three-line mapper fix.** Pure
`DataStructureMapper` edit. Restores the DSD `metadata` URN and
dimension/attribute conceptRoles for every registry. Independent of
fixtures and of sdmx-core overrides.

**Stage 2 (issues 1 + 2): two new fixtures.**
`JSON_2_0_VALUE_TO_TEXT` rewrites annotation `value` -> `text` at the
raw-JSON layer; `JSON_2_0_RESCUE_METADATA_ATTRIBUTE_USAGES` extracts
usages from raw JSON into a request-scoped cache, then re-injects them
after the writer. Configurable per registry via the existing
`fixtures: [...]` list; default-enabled for IMF since that is the
only known affected registry today.

The cache-and-reinject approach for B2 is the only one of the
considered designs that does *not* require touching sdmx-core
internals or its bean model, which matches the "monkey-patch as a
last resort" stance in CLAUDE.md.

## Implementation Plan

### Stage 1, Step 1: Map MSD reference and conceptRoles

**File:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/common/mapping/DataStructureMapper.java`

```java
@Override
public DataStructureDefinition map(DataStructureBean dataStructure) {
    final var dsd = new DataStructureDefinitionImpl();
    dsd.setOrganizationId(dataStructure.getAgencyId());
    dsd.setId(dataStructure.getId());
    dsd.setVersion(Version.createFromString(dataStructure.getVersion().toString()));
    dsd.setName(textMapper.map(dataStructure.getNames()));
    dsd.setDescription(textMapper.map(dataStructure.getDescriptions()));
    dsd.setAnnotations(annotationMapper.map(dataStructure.getAnnotations()));

    // NEW: link to backing MSD (DSD-level `metadata` URN, SDMX-JSON 2.0)
    if (dataStructure.getMSDRef() != null) {
        dsd.setMetadataStructure(referenceMapper.mapMaintainable(dataStructure.getMSDRef(), StructureClassImpl.METADATA_STRUCTURE));
    }

    // ... descriptors (unchanged) ...
}
```

For conceptRoles, add a dedicated helper alongside `mapComponent`
since the bean-side accessor differs by component type (`getConceptRole`
singular on `DimensionBean`, `getConceptRoles` plural on
`AttributeBean`, absent on `MeasureDimensionBean`):

```java
private void mapDimensionConceptRoles(DimensionComponentImpl dim, DimensionBean bean) {
    final var roles = bean.getConceptRole();
    if (roles == null || roles.isEmpty()) {
        return;
    }
    dim.setConceptRoles(roles.stream()
        .map(role -> referenceMapper.mapItem(role, StructureClassImpl.CONCEPT))
        .toList());
}

private void mapAttributeConceptRoles(DataAttributeImpl attr, AttributeBean bean) {
    final var roles = bean.getConceptRoles();
    if (roles == null || roles.isEmpty()) {
        return;
    }
    attr.setConceptRoles(roles.stream()
        .map(role -> referenceMapper.mapItem(role, StructureClassImpl.CONCEPT))
        .toList());
}
```

Wire them into `mapDimension` and `mapAttribute` respectively, after
the existing `mapComponent` call. `mapComponent` itself stays as-is
because measures cannot carry conceptRoles in sdmx-core's bean model.

Note: `DimensionImpl` and `TimeDimensionImpl` both extend
`DimensionComponentImpl`; only the non-time dimensions need
conceptRoles since `TIME_PERIOD` does not declare them in practice.
The helper accepts the common base, so it works for either.

### Stage 1, Step 2: Unit tests

**File:** `sdmx-proxy/src/test/java/com/epam/sdmxproxy/common/mapping/DataStructureMapperTest.java`
(new)

Test cases:

- Maps DSD `metadata` URN onto `getMetadataStructure()` when
  `DataStructureBean.getMSDRef()` is non-null.
- Leaves `getMetadataStructure()` null when `getMSDRef()` is null.
- Maps `DimensionBean.getConceptRole()` onto
  `DimensionImpl.getConceptRoles()` preserving order.
- Leaves `getConceptRoles()` empty when the bean returns null/empty.
- Same for `AttributeBean.getConceptRoles()` ->
  `DataAttributeImpl.getConceptRoles()`.

Drive each case with hand-built `DataStructureBean` test doubles (or
the existing test helpers in `fusion-sdmx-im`'s `TestDSDInstances`
if it is on the test classpath) -- no upstream registry needed.

### Stage 1, Step 3: Regression fixture for the IMF DSD

**File:** `sdmx-proxy/src/test/java/com/epam/sdmxproxy/services/adapter/StreamingStructureConversionServiceTest.java`
(or a sibling class)

Add a parsed-JSON assertion driven by the `imf_dsd_weo.json` sample
already in the repo root: feed it through the streaming conversion
service (with the IMF fixture chain) and assert the output JSON
contains:

- `data.dataStructures[0].metadata` equals
  `"urn:sdmx:org.sdmx.infomodel.metadatastructure.MetadataStructure=IMF.RES:MSD_WEO_METADATA_EXTERNAL(2.0+.0)"`
- `data.dataStructures[0].dataStructureComponents.dimensionList.dimensions[?(@.id=="FREQUENCY")].conceptRoles[0]`
  ends with `SDMX_CONCEPT_ROLES(1.0).FREQ`

These two assertions fail today and pass after Stage 1. The
`metadataAttributeUsages` and annotation `value` assertions stay
deferred to Stage 2.

### Stage 2, Step 1: `JSON_2_0_VALUE_TO_TEXT` fixture

**File:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/fixture/structure/AnnotationValueToTextJsonFixture.java`
(new)

**File:** `sdmx-proxy-config/src/main/java/com/epam/sdmxproxy/configuration/data/fixture/StructureFixtureType.java`

Add `ANNOTATION_VALUE_TO_TEXT` enum value. Per CLAUDE.md, also update
the schema table in `sdmx-proxy-config/README.md`.

The fixture recursively walks the JSON tree, locates every
`annotations` array, and for each annotation rewrites:

```
{ "id": "...", "value": "INTEGRATION" }
  ->
{ "id": "...", "text": "INTEGRATION" }
```

Skip when `text` or `texts` already exists (defer to localised form).
Single non-localised string; sdmx-core's `text` reader handles it.

Cost: ~50 LOC, one new fixture type. Trade-off: callers reading
`value` from the proxy output never see it; they see `text` instead.
Acceptable because clients of the proxy already need to be tolerant
of either form (both are SDMX-JSON 2.0).

Add to IMF structure endpoint config in
`sdmx-proxy-config/src/main/resources/sdmx_registries_config.json`:

```jsonc
"fixtures": [
    { "type": "METADATA_ATTRIBUTE_USAGE_TO_ATTRIBUTE", "config": {} },
    { "type": "ANNOTATION_VALUE_TO_TEXT", "config": {} },
    { "type": "VERSION_WILDCARD", "config": {} }
]
```

### Stage 2, Step 2: `MetadataAttributeUsageRescue` cache + post-write injection

This is the more involved piece; the design here intentionally stays
at the architecture level pending Stage 1 review.

Shape:

1. A new request-scoped Spring bean
   `MetadataAttributeUsageRescueCache` with
   `record Entry(String dsdUrn, JsonNode usagesArray)` and a thread-safe
   list.
2. A new structure fixture `RESCUE_METADATA_ATTRIBUTE_USAGES` that
   runs *before* `METADATA_ATTRIBUTE_USAGE_TO_ATTRIBUTE`, walks
   `data.dataStructures[].dataStructureComponents.attributeList.metadataAttributeUsages`,
   and stashes a deep copy of each usages array in the cache, keyed
   by the DSD URN. It does *not* mutate the JSON (so the existing
   `METADATA_ATTRIBUTE_USAGE_TO_ATTRIBUTE` fixture still runs its
   MSD-resolved conversion path when the MSD happens to be in the
   response).
3. A post-write step in `AdapterRouterImpl` or
   `StructureFixtureService` (or a new output-fixture concept)
   that walks the *written-out* JSON and, for each DSD whose URN is
   in the cache, restores `metadataAttributeUsages` from the cached
   copy onto the attribute list. Idempotent: skip if the array is
   already present and non-empty (in which case the existing fixture
   handled it).

A simpler variant: skip the post-write injection and instead let the
fixture mutate the upstream JSON in place to write usages as plain
attributes via a synthetic concept identity. But that loses the
distinction between metadata attribute usages and regular attributes,
which IMF clients may rely on.

Detailed design and implementation defer to a Stage-2-only follow-up
once Stage 1 is in.

## Risks and unknowns

- **conceptRoles on attributes are listed without `IDirectCrossReferenceBean`
  guarantees.** sdmx-core returns `List<IDirectCrossReferenceBean<ConceptBean>>`;
  the proxy's `ReferenceMapper.mapItem` is built for `ICrossReferenceBean`.
  Need to verify the interface hierarchy at implementation time -- if
  not assignable, `mapItem` may need a small overload. (Quick check
  during Stage 1 step 1.)
- **`TimeDimensionImpl` conceptRoles.** SDMX-JSON 2.0 does not forbid
  conceptRoles on the time dimension, but IMF samples never populate
  them. The Stage 1 helper accepts a `DimensionComponentImpl`, so
  the time-dimension case is covered "for free" if the bean exposes
  the field -- but we should at least confirm the IMF sample's time
  dimension stays unaffected post-fix.
- **`MetadataAttributeUsageRescueCache` lifecycle in fan-out.** The
  structure fan-out path (design 023) issues parallel queries and
  merges responses. A request-scoped cache must be process-shared
  across the fan-out worker pool; verify the cache is keyed by URN
  (not by registry) so duplicates from multiple registries either
  agree or are deduplicated.
- **Other SDMX-JSON 2.0 registries.** BIS, Eurostat, and ECB may
  emit annotation `value` or DSD-level `metadata` URNs in their own
  responses. Stage 1 fixes those silently; Stage 2 will need
  per-registry opt-in for the rescue fixture to avoid double-work
  where the upstream-MSD-in-response fixture already covers them.
