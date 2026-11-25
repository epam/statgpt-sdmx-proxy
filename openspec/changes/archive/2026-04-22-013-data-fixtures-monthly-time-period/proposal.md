# Proposal: Design: Data-fixture subsystem and BIS monthly TIME_PERIOD normalization

Migrated from `docs/designs/013-data-fixtures-monthly-time-period/` on 2026-11-25. [design.md](design.md) holds the original design document unchanged, apart from rewritten links. Its own status line predates the migration and can be stale; the status below is the one verified against the code at migration time.

## Status

Implemented.

## Why

BIS `/api/v2/data/` returns SDMX-JSON 1.0.0 with `defaultFormat: JSON_1_0_0` and
`bypassEnabled: false`, so every data response flows through the proxy's conversion path
(`AdapterRouterImpl.getData()` lines 183-201). Inside the response, `TIME_PERIOD` appears as an
observation dimension:

## What Changes

Introduce a `DataFixture` subsystem parallel to `AvailabilityFixture`, with one critical
departure: **the interface contract mandates streaming**. Implementations must process the
input as a stream and return an `InputStream` that produces transformed bytes on demand.
Buffering the full response (e.g. `readTree`, `readAllBytes`, `TokenBuffer`) is forbidden.

## Capabilities

None recorded. This change predates the specs in `openspec/specs/`, so it has no delta specs.

## Impact

See the files-affected section of [design.md](design.md).
