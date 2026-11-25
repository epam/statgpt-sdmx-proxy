# Proposal: Design: Single-flight for registry structure fetches

Migrated from `docs/designs/042-registry-structure-single-flight/` on 2026-11-25. [design.md](design.md) holds the original design document unchanged, apart from rewritten links. Its own status line predates the migration and can be stale; the status below is the one verified against the code at migration time.

## Status

Implemented.

## Why

`AdapterRouterImpl.getStructureBytes`
(`sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/adapter/AdapterRouterImpl.java:262`) reads
the raw-structures cache, and on a miss calls the registry and writes the result. Nothing
coordinates two threads that miss at the same time. N concurrent callers produce N upstream calls.

## What Changes

Route every structure fetch through one new service, `RawStructureFetcher`. It owns the raw cache
and the single-flight, and it is the only class that calls `GenericRegistryAdapter.getStructures`.

## Capabilities

None recorded. This change predates the specs in `openspec/specs/`, so it has no delta specs.

## Impact

See the files-affected section of [design.md](design.md).
