# Proposal: Agency Routing

Migrated from `docs/designs/003-dynamic-agency-routing/` on 2026-11-25. [design.md](design.md) holds the original design document unchanged, apart from rewritten links. Its own status line predates the migration and can be stale; the status below is the one verified against the code at migration time.

## Status

Implemented.

## Why

The current agency routing system (`AgencyRoutingServiceImpl`) is fully static. All agency-to-registry mappings are
hardcoded in `sdmx_registries_config.json`. This creates several problems:

## What Changes

The new routing model is simple:

## Capabilities

None recorded. This change predates the specs in `openspec/specs/`, so it has no delta specs.

## Impact

See the files-affected section of [design.md](design.md).
