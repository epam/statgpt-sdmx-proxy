# Proposal: Replace bisect midpoint with proportional re-step

Migrated from `docs/designs/017-bisect-proportional-restep/` on 2026-11-25. [design.md](design.md) holds the original design document unchanged, apart from rewritten links. Its own status line predates the migration and can be stale; the status below is the one verified against the code at migration time.

## Status

Implemented.

## Why

The limit-emulation bisect (design 014) shrinks the cube one dim at a time. After the
warm-start probe gives an `(SC, kHigh)` data point, subsequent bisect probes used the
plain midpoint `(kLow + kHigh) / 2` to pick the next `k`. That works in worst case
(`O(log n)` probes), but it ignores the magnitude of the overshoot / undershoot we just
observed. When the target lies *asymmetrically* inside `[kLow, kHigh]` -- which is the
common case right after a warm-start -- midpoint over-corrects:

## What Changes

See [design.md](design.md).

## Capabilities

None recorded. This change predates the specs in `openspec/specs/`, so it has no delta specs.

## Impact

See the files-affected section of [design.md](design.md).
