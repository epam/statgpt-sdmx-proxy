# Proposal: Memory-only ReadableDataLocation factory

Migrated from `docs/designs/037-memory-only-readable-data-location/` on 2026-11-25. [design.md](design.md) holds the original design document unchanged, apart from rewritten links. Its own status line predates the migration and can be stale; the status below is the one verified against the code at migration time.

## Status

Implemented.

## Why

On a pod that has served traffic for a while, availability (and in principle any
converted) requests suddenly start logging:

## What Changes

A minimal `byte[]`-backed `ReadableDataLocation`, in the existing sdmx-core
override package `services/sdmxsource/`. It extends sdmx-core's
`AbstractReadableDataLocation`
(`fusion-utils/.../io/AbstractReadableDataLocation.java`) so it inherits the
`isClosed`/`isProtected`/`getSize`/`getInputStream`/`split`/`getFormat`
plumbing the readers expect — in particular `getFormat()`'s format-sniffing,
which re-reads the stream. Each `getInputStream()` returns a fresh
`ByteArrayInputStream` over the same array, satisfying the re-readable contract.

## Capabilities

None recorded. This change predates the specs in `openspec/specs/`, so it has no delta specs.

## Impact

See the files-affected section of [design.md](design.md).
