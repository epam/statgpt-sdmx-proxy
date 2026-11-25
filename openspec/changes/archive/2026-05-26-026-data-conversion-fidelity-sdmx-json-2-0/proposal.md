# Proposal: Restore data conversion fidelity for SDMX-JSON 2.0 data responses

Migrated from `docs/designs/026-data-conversion-fidelity-sdmx-json-2-0/` on 2026-11-25. [design.md](design.md) holds the original design document unchanged, apart from rewritten links. Its own status line predates the migration and can be stale; the status below is the one verified against the code at migration time.

## Status

Stage 1 implemented (2026-05-22). Stage 3 superseded by design 030. Other stages were not verified during the migration.

## Why

**Issue 3 (`role` vs `roles`).**
`AbstractJsonDataWriter.writeComponent`
(`fusion-sdmx-json/.../AbstractJsonDataWriter.java:607-612`) hardcodes:

## What Changes

Four staged fixes, ordered from lowest to highest risk. Each stage is
shippable on its own.

## Capabilities

None recorded. This change predates the specs in `openspec/specs/`, so it has no delta specs.

## Impact

See the files-affected section of [design.md](design.md).
