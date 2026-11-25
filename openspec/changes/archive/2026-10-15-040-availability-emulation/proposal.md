# Proposal: Design: Availability emulation for registries that do not serve `availableconstraint`

Migrated from `docs/designs/040-availability-emulation/` on 2026-11-25. [design.md](design.md) holds the original design document unchanged, apart from rewritten links. Its own status line predates the migration and can be stale; the status below is the one verified against the code at migration time.

## Status

Implemented.

## Why

Eurostat is being onboarded as an SDMX 2.1 registry (`ESTAT`). It serves structures and data, but
it does not implement the availability endpoint at all:

## What Changes

1. **Production gate on `availabilityEnabled`.** `AdapterRouterImpl.getAvailability` branches
   before the bypass check. `availabilityEnabled: true` keeps today's behaviour byte-for-byte.
2. **`emulation` block on `AvailabilityEndpointConfiguration`.** `type: NONE` (the default)
   rejects with `501` -- the spec's code for an unimplemented method, and strictly better than
   proxying a `405`.
3. **`AvailabilityEmulator` + `AvailabilityEmulatorProvider`**, resolving by type from the Spring
   bean list -- the same shape as `SeriesLimitTruncatorProvider`.
4. **`DataQueryAvailabilityEmulator`** -- routes on "is this request narrowed?", serves the
   unfiltered case from `UnfilteredConstraintSource`, and probes otherwise.
5. **`UnfilteredConstraintSource`** -- fetches the registry's `Actual` constraint once, cached,
   and serves it in both roles: the unfiltered response, and the intersection universe.
6. **A cache domain for emulated availability**, because there is none today: `CacheService`
   caches parsed structures, ready structure responses and the limit-emulation shrink result, and
   `AdapterRouterImpl.getAvailability` never touches `cacheService` at all.

## Capabilities

None recorded. This change predates the specs in `openspec/specs/`, so it has no delta specs.

## Impact

See the files-affected section of [design.md](design.md).
