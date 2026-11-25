# Proposal: Structure fan-out as a toggled feature

Migrated from `docs/designs/023-structure-fan-out-feature-toggle/` on 2026-11-25. [design.md](design.md) holds the original design document unchanged, apart from rewritten links. Its own status line predates the migration and can be stale; the status below is the one verified against the code at migration time.

## Status

Implemented.

## Why

Today `QueryTranslatorImpl.translateStructureQuery` at
`sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/translator/QueryTranslatorImpl.java:161`
rejects any wildcard or comma-separated `agencyId` with HTTP 501:

## What Changes

Reintroduce fan-out for the `agencyId == "*"` case only, gated by a single
top-level boolean on `ProxyConfiguration` named `structureFanOutEnabled`
(default `false`). The control surface is:

## Capabilities

None recorded. This change predates the specs in `openspec/specs/`, so it has no delta specs.

## Impact

See the files-affected section of [design.md](design.md).
