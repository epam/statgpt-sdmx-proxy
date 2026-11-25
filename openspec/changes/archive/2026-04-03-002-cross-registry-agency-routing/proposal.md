# Proposal: Cross-Registry Agency Routing

Migrated from `docs/designs/002-cross-registry-agency-routing/` on 2026-11-25. [design.md](design.md) holds the original design document unchanged, apart from rewritten links. Its own status line predates the migration and can be stale; the status below is the one verified against the code at migration time.

## Status

Implemented.

## Why

BIS data structures reference artefacts from other agencies (IMF, ESTAT, ISO, SDMX). When the client fetches a BIS DSD
with `references=all` and then requests a referenced `codelist/IMF/CL_AREA/1.15`, the proxy currently routes to IMF
based on the agency ID. But the artefact lives in BIS. We need a routing mechanism that can resolve the correct registry
when the agency ID alone is ambiguous.

## What Changes

Replaces `supportedAgencies` on each registry. Moved to the top-level `ProxyConfiguration` so routing is a cross-cutting
concern, not per-registry.

## Capabilities

None recorded. This change predates the specs in `openspec/specs/`, so it has no delta specs.

## Impact

See the files-affected section of [design.md](design.md).
