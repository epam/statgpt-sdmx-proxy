# Proposal: Cache pre-heat (cache prewarming) — evaluation

Migrated from `docs/designs/021-cache-prehit/` on 2026-11-25. [design.md](design.md) holds the original design document unchanged, apart from rewritten links. Its own status line predates the migration and can be stale; the status below is the one verified against the code at migration time.

## Status

Rejected. Superseded by design 044 (structure cache preheat), which shipped in #130.

## Why

The SDMX Proxy starts with all three caches cold: parsed structures (raw bytes of
`SdmxBeans`), ready responses (serialized output keyed by URL + Accept + query params),
and limit-emulation shrunk-query results. Every request after a cold start hits the
upstream registry until the relevant entry has been populated by traffic. The cost of a
cold-start miss is significant on three dimensions:

## What Changes

See [design.md](design.md).

## Capabilities

None recorded. This change predates the specs in `openspec/specs/`, so it has no delta specs.

## Impact

See the files-affected section of [design.md](design.md).
