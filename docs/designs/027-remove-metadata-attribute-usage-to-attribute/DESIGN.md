# Design 027: Remove `METADATA_ATTRIBUTE_USAGE_TO_ATTRIBUTE` workaround

**Status:** draft (2026-05-25). Awaiting review.

## Context

`MetadataAttributeUsageToAttributeJsonFixture` was introduced (see
[design 025](../025-dsd-conversion-fidelity/DESIGN.md) and earlier
work) as a workaround for two related sdmx-core gaps:

1. sdmx-core's SDMX-JSON 2.0 DSD reader silently drops the
   `metadataAttributeUsages` field on `attributeList`.
2. sdmx-core's bean model has no slot to store metadata-attribute
   usages on the DSD side anyway (`AttributeListBean.getAttributes`
   is the only collection).

The fixture's strategy is to rewrite each metadata-attribute usage
into a synthetic regular DSD attribute on the raw JSON before
parsing, borrowing `conceptIdentity` from the referenced MSD and
copying the usage's `attributeRelationship` verbatim. sdmx-core
then accepts the rewritten DSD without complaint, and the
in-memory `SdmxBeans` carries the forged attributes alongside the
real ones.

Design 025 Stage 2 added `MetadataAttributeUsagePreserver` and the
`PRESERVE_METADATA_ATTRIBUTE_USAGES` marker: capture the original
`metadataAttributeUsages` array from raw upstream bytes before
fixtures run, then re-inject it onto the converted output. With
that landed, the structure response carries the field exactly as
upstream emits it, independent of whether
`METADATA_ATTRIBUTE_USAGE_TO_ATTRIBUTE` runs.

That leaves the older fixture doing only one job: forging entries
on the in-memory `SdmxBeans` so that downstream conversions see
something at the position metadata-attribute usages would
otherwise occupy. The job is harmful at the *data* endpoint and
unnecessary at the structure endpoint.

## Problem

### The workaround conflates two distinct SDMX concepts

The SDMX information model separates two ideas that the workaround
collapses (see SDMX-JSON 2.0 structure schema, `AttributeType` at
line 2052 vs `MetadataAttributeUsageType` at line 2154; and
`sdmx-rest-2.2.0/doc/metadata.md` for the metadata endpoint):

- **Data attribute** (DSD): a qualifier carried per
  observation/series in *data* messages
  (`attributes.dataset/series/observation[].values[]`). Examples
  for IMF.RES:WEO: `SCALE`, `DECIMALS_DISPLAYED`, `OVERLAP`,
  `COUNTRY_UPDATE_DATE`.
- **Metadata attribute** (MSD): a reference-metadata field —
  documentation, provenance, methodology. Examples:
  `DOI`, `AUTHOR`, `TOPIC`, `METHODOLOGY`. Values are reported in
  a separate **MetadataSet** retrievable from
  `GET /metadata/structure/datastructure/{agency}/{id}/{version}`
  using the `application/vnd.sdmx.metadata+json;version=2.0.0`
  media type. They are **never** in a data response.
- **Metadata-attribute usage** (on a DSD): a *declaration of
  permission* — "data conforming to this DSD MAY have a
  reference-metadata report for the AUTHOR attribute, attached at
  this level." The usage carries the reference and the relationship.
  No value.

The workaround forges (3) as (1): for every usage on IMF's DSD it
creates a synthetic data attribute with a fabricated
`conceptIdentity`. sdmx-core can't tell them apart from real DSD
attributes.

### Observable damage on the data endpoint

`AdapterRouterImpl.getData` pre-fetches the DSD via the structure
endpoint (`AdapterRouterImpl.java:344`):

```java
SdmxBeans sdmxBeans = getSdmxBeans(getStructureQuery(query));
```

That fetch goes through `getFixedStructureStream` which applies
the structure-endpoint fixture chain — including
`METADATA_ATTRIBUTE_USAGE_TO_ATTRIBUTE`. The promoted attributes
end up in `SdmxBeans` and the data writer iterates them.

In the IMF.RES:WEO data response (samples
`imf_data_weo.json` 18 MB / `proxy_data_weo.json` 22 MB at the
repo root, captured 2026-05-22):

| section                     | upstream IMF | proxy (with workaround) |
|-----------------------------|--------------|-------------------------|
| `attributes.dataset`        | 0            | 18                      |
| `attributes.series`         | 4            | 41                      |
| `attributes.observation`    | 2            | 3                       |
| `attributes.dimensionGroup` | 17           | 0                       |

The 18 + 37 = 55-ish extra entries are promoted metadata-attribute
usages. Per-series `attributes[...]` tuples carry the matching
number of trailing `null`s — no upstream observation ever reported
values for these because they aren't data attributes. A client
diffing the two sees an apparent loss-of-data (lots of nulls) when
in fact the proxy is *adding* phantoms.

This is the symptom labelled "Group C" / "Issue 4" in
[design 026](../026-data-conversion-fidelity-sdmx-json-2-0/DESIGN.md).
That design's Stage 2 proposed a "rescue and strip" pattern to
unwind the damage. Removing the workaround makes Stage 2
unnecessary.

### Structure endpoint no longer needs the workaround

`PRESERVE_METADATA_ATTRIBUTE_USAGES`
(`MetadataAttributeUsagePreserver`) captures
`metadataAttributeUsages` from raw upstream bytes before any
fixture runs, then re-injects the array onto matching DSDs in the
converted output. The capture is independent of the workaround;
the inject overwrites any empty/missing array on the converted
output with the captured one.

Concretely, `MetadataAttributeUsagePreserver.inject`
(`MetadataAttributeUsagePreserver.java:90-131`) skips DSDs whose
output already has a non-empty `metadataAttributeUsages` and
otherwise replaces the empty array with the captured deep copy.
With the workaround removed, the SdmxBeans-derived output emits
no `metadataAttributeUsages` field at all (sdmx-core drops it on
read); inject fills it back from the captured raw bytes.

The `metadataAttributeUsages must be restored as a non-empty
array` assertion in
`StreamingStructureConversionServiceTest`
(line 393) already exercises this path with both fixtures active;
the assertion holds with PRESERVE alone.

## Non-goals

- **Restoring metadata-attribute *values* on the data response.**
  Per SDMX-REST 2.2, reference-metadata values live in MetadataSets
  fetched separately via the metadata endpoint. The proxy never
  surfaces them on the data endpoint today and this design does
  not change that.
- **Implementing the metadata endpoint on the proxy.** Clients of
  the proxy that need DOI / AUTHOR / METHODOLOGY values must call
  the upstream registry's metadata endpoint directly (or wait
  for separate work on the proxy's metadata routing). This design
  is purely a removal.
- **Adding a sdmx-core override that models
  `metadataAttributeUsages` properly in the bean tree.** Possible
  future work; out of scope here. The wire-byte
  capture+inject in `MetadataAttributeUsagePreserver` is the
  pragmatic substitute and has been validated against the IMF
  reproducer.
- **Audit of non-IMF registries.** Only the IMF SDMX 3.0
  structure-endpoint config currently references the fixture
  (verified by repository grep). No other registry needs change.

## Solution

Delete the workaround and every reference to it. The structure
endpoint's correctness is preserved by
`PRESERVE_METADATA_ATTRIBUTE_USAGES` alone; the data endpoint's
attribute sections collapse back to upstream's real DSD attributes.

The change is registry-agnostic in source but only the IMF SDMX 3.0
config has the fixture wired, so user-visible behaviour changes
only on IMF responses.

## Behavioural change

For an IMF.RES:WEO `GET /data/dataflow/IMF.RES/WEO/9.0.0` request:

- `data.structures[0].attributes.dataset` shrinks from 18 entries
  to 0 (matching upstream).
- `data.structures[0].attributes.series` shrinks from 41 entries
  to 4 (the real DSD series attributes: `SCALE`,
  `DECIMALS_DISPLAYED`, `OVERLAP`, `COUNTRY_UPDATE_DATE`).
- `data.structures[0].attributes.observation` shrinks from 3
  entries to 2 (`PRECISION`, `DERIVATION_TYPE`).
- Each `series[].attributes[...]` tuple length collapses from 41
  to 4, dropping all the trailing `null` placeholders.
- The `attributes.dimensionGroup` section remains absent
  (separate bug — see design 026, Group B). The 17 real
  dimension-group attributes still aren't surfaced; that gap was
  there before and stays.

For a `GET /structure/datastructure/IMF.RES/DSD_WEO/9.0.0`
request:

- `dataStructureComponents.attributeList.attributes` contains
  only the 23 real DSD attributes (same set as today's pre-Stage-1
  output — the 33 promoted entries that previously also appeared
  here are gone).
- `dataStructureComponents.attributeList.metadataAttributeUsages`
  carries the upstream usages array unchanged
  (`PRESERVE_METADATA_ATTRIBUTE_USAGES` already handles this).
- `dataStructureComponents.attributeList.metadataAttributeUsages`
  presence is unaffected by this change in either direction.

Net: client-facing data responses become a faithful reflection of
upstream; structure responses are unchanged.

## Impact on other designs

- **Design 025 Stage 2** continues to work as designed. The
  workaround was orthogonal to PRESERVE; removing one doesn't
  affect the other.
- **Design 026 Group C (Issue 4)** is resolved by this removal.
  Design 026's planned "rescue and strip" Stage 2 becomes
  unnecessary — there's no longer anything to strip. The Stage
  2 implementation section in design 026 should be marked
  obsolete pointing here.
- **Design 026 Stage 4** (series misrouting, Group D) is
  unaffected.

## Implementation Plan

### Step 1: Delete the fixture class

**File:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/fixture/structure/MetadataAttributeUsageToAttributeJsonFixture.java`

Delete the file.

### Step 2: Remove the enum constant

**File:** `sdmx-proxy-config/src/main/java/com/epam/sdmxproxy/configuration/data/fixture/StructureFixtureType.java`

Remove `METADATA_ATTRIBUTE_USAGE_TO_ATTRIBUTE` from the enum body.

### Step 3: Remove from the production registry config

**File:** `sdmx-proxy-config/src/main/resources/sdmx_registries_config.json`

Remove the `{"type": "METADATA_ATTRIBUTE_USAGE_TO_ATTRIBUTE", "config": {}}` entry from the IMF SDMX 3.0 `structureEndpointConfig.fixtures` array.

### Step 4: Remove from the E2E registry config

**File:** `sdmx-proxy-e2e/src/test/resources/com/epam/sdmxproxy/e2e/tests/registry/imf/3_0/imf_3_0_registry_config.json`

Same edit as Step 3.

### Step 5: Update the config schema table

**File:** `sdmx-proxy-config/README.md`

Remove the `METADATA_ATTRIBUTE_USAGE_TO_ATTRIBUTE` row from the
`StructureFixtureType` table.

### Step 6: Trim Preserver javadoc

**File:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/fixture/structure/MetadataAttributeUsagePreserver.java`

The class javadoc currently reads "This preserver runs alongside
that fixture: it captures the wire bytes before fixtures mutate
them..." — drop the dependency claim and explain that the
preserver is the sole mechanism by which `metadataAttributeUsages`
survives the round trip.

### Step 7: Delete the fixture's unit test

**File:** `sdmx-proxy/src/test/java/com/epam/sdmxproxy/services/fixture/MetadataAttributeUsageToAttributeJsonFixtureTest.java`

Delete the file.

### Step 8: Update tests that prep SdmxBeans via the fixture

The fixture is referenced from four test classes as part of
SdmxBeans-loading helpers. Each call drops the fixture
configuration; if assertions hard-coded the inflated attribute
counts they get adjusted to upstream's real counts.

**File:** `sdmx-proxy/src/test/java/com/epam/sdmxproxy/services/adapter/StreamingStructureConversionServiceTest.java`

Three references (lines 51, 192, 325). Two of the three tests
build a fixture chain `[metadataAttributeFixture, versionWildcardFixture]`;
drop the metadata-attribute fixture, leaving only the version
wildcard. The third test (around line 320, the
PRESERVE+ANNOTATION_VALUE+VERSION_WILDCARD case) drops the
metadata-attribute fixture from its `fixtureConfigs` list. The
assertion that `metadataAttributeUsages` is restored as a
non-empty array still passes because PRESERVE alone provides it.

**File:** `sdmx-proxy/src/test/java/com/epam/sdmxproxy/services/adapter/StreamingDataConversionServiceTest.java`

Eleven references, all in test setups and two helper methods
(`loadWeoStructures`, `parseStructuresFixture`). Remove the
fixture from every list. Helpers become "load structures, parse,
return SdmxBeans" — no fixture chain needed since no other fixture
runs in those helpers.

Two consequences to verify:

1. The `data_weo_misroute_3countries.json` test
   (`shouldNotMisrouteSeriesAcrossIndicatorPositions_issue80`) only
   checks series counts and labels — unaffected by attribute
   removal.
2. Any test asserting `attributes.*` content or array shape on the
   converted data needs its expected values updated to upstream's
   real DSD attributes (the 23-count set, broken into 18
   dimensionGroup + 4 series + 2 observation per upstream — but
   note dimensionGroup still drops on output per design 026, so the
   on-the-wire counts are 0 dataset + 4 series + 2 observation).
   Walk each test case and adjust where needed; if a test was
   only checking observation values (the bulk), no change.

**File:** `sdmx-proxy/src/test/java/com/epam/sdmxproxy/services/adapter/StreamingAvailabilityConversionServiceTest.java`

One reference (line 59). The fixture preps structures for
availability conversion. Drop the fixture, keep the structure
parse — sdmx-core silently ignores `metadataAttributeUsages` on
read, and availability conversion doesn't need them.

**File:** `sdmx-proxy/src/test/java/com/epam/sdmxproxy/services/fixture/availability/MoveCubeRegionComponentsToKeyValuesJsonFixtureRealDataTest.java`

One reference (line 67). Same as the availability test — drop the
fixture from the structures prep, assertions on the cube-region
key-values output don't depend on metadata-attribute attributes.

### Step 9: Amend designs 025 and 026

**File:** `docs/designs/025-dsd-conversion-fidelity/DESIGN.md`

Add a "Follow-up" note in the status block: the
`METADATA_ATTRIBUTE_USAGE_TO_ATTRIBUTE` fixture has been removed
(design 027); `PRESERVE_METADATA_ATTRIBUTE_USAGES` is now the sole
mechanism by which the field survives the round trip.

**File:** `docs/designs/026-data-conversion-fidelity-sdmx-json-2-0/DESIGN.md`

In the Group C / Issue 4 problem section, append a resolution
note pointing at design 027. In the Stage 2 implementation plan,
mark the stage obsoleted by design 027 (the "rescue and strip"
pattern is no longer needed because there is nothing to strip
after the workaround is removed). Strike the "Reverting Stage 1's
fixture is not on the table" caveat in the risks section since
that assumption no longer holds.

## Risks and unknowns

- **Other clients depending on the inflated attribute list.**
  Any downstream consumer that learned to filter out the
  null-valued attributes will continue to work (they're absent
  rather than null). Any consumer that *relied on* the
  promoted attributes to discover metadata-attribute existence
  must switch to reading `metadataAttributeUsages` from the
  structure response, then following the `metadata` URN to the
  MSD. This is the SDMX-intended discovery flow.
- **sdmx-core silently dropping the field.** Already happens
  today on every code path; this removal does not introduce a
  new dropped-data risk.
- **Cached responses.** Any `READY_RESPONSE` or
  `RAW_STRUCTURES` cache entries produced under the old fixture
  carry the promoted attributes / forged JSON. They are
  invalidated on the next ETag mismatch or TTL roll, but a
  manual cache flush is appropriate at deploy time to avoid
  serving stale workaround-affected responses.
