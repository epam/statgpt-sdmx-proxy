# Proposal: Design: Enable Hierarchy Structures for IMF and BIS

Migrated from `docs/designs/007-enable-hierarchies/` on 2026-11-25. [design.md](design.md) holds the original design document unchanged, apart from rewritten links. Its own status line predates the migration and can be stale; the status below is the one verified against the code at migration time.

## Status

Implemented.

## Why

Requesting a hierarchy structure through the proxy returns an error:

## What Changes

Add `"hierarchy"` and `"hierarchyassociation"` to `supportedStructures` for both IMF and BIS SDMX 3.0 configurations.

## Capabilities

None recorded. This change predates the specs in `openspec/specs/`, so it has no delta specs.

## Impact

See the files-affected section of [design.md](design.md).
