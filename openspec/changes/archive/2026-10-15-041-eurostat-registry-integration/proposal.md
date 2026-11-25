# Proposal: Design: Eurostat registry integration

Migrated from `docs/designs/041-eurostat-registry-integration/` on 2026-11-25. [design.md](design.md) holds the original design document unchanged, apart from rewritten links. Its own status line predates the migration and can be stale; the status below is the one verified against the code at migration time.

## Status

Implemented.

## Why

Eurostat gzips the body when the request carries exactly `Accept-Encoding: gzip`, and sets no
`Content-Encoding`. It marks the compression only in `Content-Disposition`. OkHttp decompresses on
`Content-Encoding` alone, so the SDMX readers receive a gzip stream.

## What Changes

Nine independent changes, each small, ordered so no step depends on a later one.

## Capabilities

None recorded. This change predates the specs in `openspec/specs/`, so it has no delta specs.

## Impact

See the files-affected section of [design.md](design.md).
