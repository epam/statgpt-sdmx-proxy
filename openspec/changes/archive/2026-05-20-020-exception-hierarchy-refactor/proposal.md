# Proposal: Unified exception hierarchy and ad-hoc throw migration

Migrated from `docs/designs/020-exception-hierarchy-refactor/` on 2026-11-25. [design.md](design.md) holds the original design document unchanged, apart from rewritten links. Its own status line predates the migration and can be stale; the status below is the one verified against the code at migration time.

## Status

Implemented.

## Why

The SDMX Proxy currently throws a mix of JDK runtime exceptions (`IllegalArgumentException`,
`IllegalStateException`, `RuntimeException`, `UnsupportedOperationException`) and a handful
of custom exceptions in `com.epam.sdmxproxy.exception`. The custom exceptions sit on three
different parents (`RuntimeException`, `IllegalArgumentException`, ad-hoc) and the
`GlobalExceptionHandler` has a dedicated `@ExceptionHandler` for each one. Ad-hoc throws
either fall through to the catch-all `Exception.class` handler (returning HTTP 500 with a
sanitised "An unexpected error occurred." message) or are caught generically by
`IllegalArgumentException.class` (returning HTTP 400 regardless of whether the cause was a
client mistake or an internal bug).

## What Changes

See [design.md](design.md).

## Capabilities

None recorded. This change predates the specs in `openspec/specs/`, so it has no delta specs.

## Impact

See the files-affected section of [design.md](design.md).
