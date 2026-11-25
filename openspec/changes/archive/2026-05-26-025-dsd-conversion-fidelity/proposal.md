# Proposal: Restore DSD conversion fidelity for SDMX-JSON 2.0 structure responses

Migrated from `docs/designs/025-dsd-conversion-fidelity/` on 2026-11-25. [design.md](design.md) holds the original design document unchanged, apart from rewritten links. Its own status line predates the migration and can be stale; the status below is the one verified against the code at migration time.

## Status

Implemented.

## Why

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

## What Changes

Two unrelated fixes, in two distinct layers:

## Capabilities

None recorded. This change predates the specs in `openspec/specs/`, so it has no delta specs.

## Impact

See the files-affected section of [design.md](design.md).
