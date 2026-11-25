# Proposal: Design: Heuristic `limit` emulation for registries that do not support it (Issue #40)

Migrated from `docs/designs/014-heuristic-limit-emulation/` on 2026-11-25. [design.md](design.md) holds the original design document unchanged, apart from rewritten links. Its own status line predates the migration and can be stale; the status below is the one verified against the code at migration time.

## Status

Implemented.

## Why

The SDMX 3.0 REST specification defines a `limit` query parameter on the `/data` endpoint
that caps the number of series returned by the registry. The BIS SDMX v2 registry
(`https://stats.bis.org/api/v2`) does not accept `limit` -- see the parameter set at
https://stats.bis.org/api-doc/v2/#/Data%20queries/get_data__context___agencyID___resourceID___version___key_.
BIS does offer `firstNObservations` / `lastNObservations`, but those cap observations per
series, not the total number of series, and therefore cannot substitute for `limit`.

## What Changes

Five pieces:

## Capabilities

None recorded. This change predates the specs in `openspec/specs/`, so it has no delta specs.

## Impact

See the files-affected section of [design.md](design.md).
