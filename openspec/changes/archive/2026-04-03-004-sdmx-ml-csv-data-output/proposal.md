# Proposal: Design: SDMX-ML and SDMX-CSV Output for Data Endpoint

Migrated from `docs/designs/004-sdmx-ml-csv-data-output/` on 2026-11-25. [design.md](design.md) holds the original design document unchanged, apart from rewritten links. Its own status line predates the migration and can be stale; the status below is the one verified against the code at migration time.

## Status

Implemented.

## Why

The data endpoint (`GET /api/sdmx/3.0/data/...`) currently only outputs **SDMX-JSON** (v1.0.0 for SDMX 2.1, v2.0.0 for
SDMX 3.0). Clients requesting SDMX-ML (XML) get `UnsupportedOperationException`; SDMX-CSV is not handled at all.

## What Changes

See [design.md](design.md).

## Capabilities

None recorded. This change predates the specs in `openspec/specs/`, so it has no delta specs.

## Impact

See the files-affected section of [design.md](design.md).
