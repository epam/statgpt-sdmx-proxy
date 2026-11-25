# Proposal: Design: Availability Filter Parameter Unwrapping

Migrated from `docs/designs/008-availability-filter-unwrapping/` on 2026-11-25. [design.md](design.md) holds the original design document unchanged, apart from rewritten links. Its own status line predates the migration and can be stale; the status below is the one verified against the code at migration time.

## Status

Implemented.

## Why

BIS registry (`stats.bis.org`) does not recognize `c[DIMENSION]=value` query parameters on the availability endpoint.
When the proxy sends:

## What Changes

Add a boolean configuration flag `unwrapFilterParameters` to `AvailabilityEndpointConfiguration`. When enabled, the
proxy passes filter dimension names directly as query parameters (e.g., `FREQ=Q`) instead of wrapping them in `c[]`
format (e.g., `c[FREQ]=Q`).

## Capabilities

None recorded. This change predates the specs in `openspec/specs/`, so it has no delta specs.

## Impact

See the files-affected section of [design.md](design.md).
