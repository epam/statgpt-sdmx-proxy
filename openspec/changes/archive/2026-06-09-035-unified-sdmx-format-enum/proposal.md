# Proposal: Unified `SdmxFormat` enum (collapse `ReturnFormat` + `SdmxMediaType`)

Migrated from `docs/designs/035-unified-sdmx-format-enum/` on 2026-11-25. [design.md](design.md) holds the original design document unchanged, apart from rewritten links. Its own status line predates the migration and can be stale; the status below is the one verified against the code at migration time.

## Status

Implemented.

## Why

Concrete symptoms a maintainer hits today:

## What Changes

Introduce a single canonical enum **`SdmxFormat`** in
`com.epam.sdmxproxy.configuration.data` (a rename + expansion of `ReturnFormat`, not a new
sibling). Each member carries:

## Capabilities

None recorded. This change predates the specs in `openspec/specs/`, so it has no delta specs.

## Impact

See the files-affected section of [design.md](design.md).
