# Proposal: Design: Merge All-Wildcard Key for Restrictive Registries (Issue #41)

Migrated from `docs/designs/011-merge-all-wildcard-key/` on 2026-11-25. [design.md](design.md) holds the original design document unchanged, apart from rewritten links. Its own status line predates the migration and can be stale; the status below is the one verified against the code at migration time.

## Status

Implemented.

## Why

The failing request:

## What Changes

Add a boolean flag `mergeAllWildcardKey` to both
`DataEndpointConfiguration` and `AvailabilityEndpointConfiguration`. When
enabled, `QueryTranslatorImpl` collapses any key whose every non-empty
dot-separated part equals `*` down to a single `*`.

## Capabilities

None recorded. This change predates the specs in `openspec/specs/`, so it has no delta specs.

## Impact

See the files-affected section of [design.md](design.md).
