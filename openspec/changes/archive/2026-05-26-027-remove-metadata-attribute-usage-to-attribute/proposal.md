# Proposal: Remove `METADATA_ATTRIBUTE_USAGE_TO_ATTRIBUTE` workaround

Migrated from `docs/designs/027-remove-metadata-attribute-usage-to-attribute/` on 2026-11-25. [design.md](design.md) holds the original design document unchanged, apart from rewritten links. Its own status line predates the migration and can be stale; the status below is the one verified against the code at migration time.

## Status

Implemented.

## Why

The SDMX information model separates two ideas that the workaround
collapses (see SDMX-JSON 2.0 structure schema, `AttributeType` at
line 2052 vs `MetadataAttributeUsageType` at line 2154; and
`sdmx-rest-2.2.0/doc/metadata.md` for the metadata endpoint):

## What Changes

Delete the workaround and every reference to it. The structure
endpoint's correctness is preserved by
`PRESERVE_METADATA_ATTRIBUTE_USAGES` alone; the data endpoint's
attribute sections collapse back to upstream's real DSD attributes.

## Capabilities

None recorded. This change predates the specs in `openspec/specs/`, so it has no delta specs.

## Impact

See the files-affected section of [design.md](design.md).
