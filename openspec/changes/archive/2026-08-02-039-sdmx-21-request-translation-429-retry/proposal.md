# Proposal: Design: SDMX 2.1 request translation and HTTP 429 retry

Migrated from `docs/designs/039-sdmx-21-request-translation-429-retry/` on 2026-11-25. [design.md](design.md) holds the original design document unchanged, apart from rewritten links. Its own status line predates the migration and can be stale; the status below is the one verified against the code at migration time.

## Status

Implemented.

## Why

OECD caps at roughly 20 requests/minute (measured: 40 back-to-back requests return 200 for #1-19
and 429 from #20; the same 60 requests spaced ~3.7 s apart all return 200). A single E2E run
produced **96 HTTP 429 responses**, every one of which propagated straight to the client.

## What Changes

Three independent changes.

## Capabilities

None recorded. This change predates the specs in `openspec/specs/`, so it has no delta specs.

## Impact

See the files-affected section of [design.md](design.md).
