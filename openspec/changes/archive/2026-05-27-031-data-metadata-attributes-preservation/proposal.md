# Proposal: Preserve MSD-derived metadata attribute usages on the data endpoint

Migrated from `docs/designs/031-data-metadata-attributes-preservation/` on 2026-11-25. [design.md](design.md) holds the original design document unchanged, apart from rewritten links. Its own status line predates the migration and can be stale; the status below is the one verified against the code at migration time.

## Status

Implemented.

## Why

Design 030 closed the dimension-group attribute gap for attributes
declared in the DSD's `attributeList.attributes` array (e.g. IMF WEO's
`FUNCTIONAL_CAT`, `UNIT`, `REPORTING_PERIOD_TYPE` — 17 of them, all with
`AttributeRelationship.dimensions=[INDICATOR]`). After 030 these
attributes appear in `attributes.dimensionGroup` and their values appear
in `dimensionGroupAttributes` as the SDMX-JSON 2.0 spec requires.

## What Changes

A new `DataFixtureType.PRESERVE_METADATA_ATTRIBUTES` toggle, paired
with a new `MetadataAttributesPreserver` service that mirrors
`MetadataAttributeUsagePreserver` (design 027) on the data endpoint:

## Capabilities

None recorded. This change predates the specs in `openspec/specs/`, so it has no delta specs.

## Impact

See the files-affected section of [design.md](design.md).
