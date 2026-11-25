# Proposal: Design 036 — SDMX 3.0 XML structure output

Migrated from `docs/designs/036-xml-3-0-structure-output/` on 2026-11-25. [design.md](design.md) holds the original design document unchanged, apart from rewritten links. Its own status line predates the migration and can be stale; the status below is the one verified against the code at migration time.

## Status

Implemented.

## Why

The proxy can return structures as SDMX-JSON 2.0 and SDMX-ML 2.1, but not as
SDMX-ML 3.0 (`application/vnd.sdmx.structure+xml;version=3.0.0`). Registries are
still read as JSON 2.0; this change adds the **outbound** XML 3.0 writer path
(JSON 2.0 read -> `SdmxBeans` -> XML 3.0 write), analogous to the existing XML 2.1
arm. No native XML 3.0 *reading* / bypass is introduced (explicitly out of scope —
see Non-goals).

## What Changes

`sdmx-proxy/.../services/adapter/conversion/StreamingStructureConversionService.java`,
`writeAsXml()` (currently lines 131-143). Replace the `SDMX_3_0` throw with a real
writer, mirroring the existing `SDMX_2_1` arm:

## Capabilities

None recorded. This change predates the specs in `openspec/specs/`, so it has no delta specs.

## Impact

See the files-affected section of [design.md](design.md).
