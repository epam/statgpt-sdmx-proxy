# Proposal: Route all SDMX 3.0 filters through `c[]`, never mix with the path key (BIS bug workaround)

Migrated from `docs/designs/016-c-bracket-filter-routing/` on 2026-11-25. [design.md](design.md) holds the original design document unchanged, apart from rewritten links. Its own status line predates the migration and can be stale; the status below is the one verified against the code at migration time.

## Status

Implemented.

## Why

Production logs from a BIS limit-emulation request showed pathological behavior in the
shrink loop: the probe budget (8) was exhausted with `series_count` barely moving from
`181` to `182`, while we narrowed `c[REF_AREA]` from 64 countries down to a single
country (`AE`). After the request the proxy still returned the correct payload (the
truncator did its job), but the bisect itself was useless -- every probe burned ~150ms
of HTTP latency without informing the next decision.

## What Changes

See [design.md](design.md).

## Capabilities

None recorded. This change predates the specs in `openspec/specs/`, so it has no delta specs.

## Impact

See the files-affected section of [design.md](design.md).
