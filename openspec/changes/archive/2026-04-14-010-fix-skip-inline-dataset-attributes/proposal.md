# Proposal: Design: Fix Skip Logic for Dataset-Level `attributes` in CustomSdmxJsonDataReaderEngineV2

Migrated from `docs/designs/010-fix-skip-inline-dataset-attributes/` on 2026-11-25. [design.md](design.md) holds the original design document unchanged, apart from rewritten links. Its own status line predates the migration and can be stale; the status below is the one verified against the code at migration time.

## Status

Implemented.

## Why

The bug surfaces on every series-keyed SDMX-JSON 2.0 response with a non-empty dataset-level `attributes`. It was
reported on:

## What Changes

Drop the spurious `jReader.moveNext()` from the dataset-level `attributes` case in
`CustomSdmxJsonDataReaderEngineV2.moveNextDatasetInternal`:

## Capabilities

None recorded. This change predates the specs in `openspec/specs/`, so it has no delta specs.

## Impact

See the files-affected section of [design.md](design.md).
