# Proposal: Design: Force Seed Config via `CONFIG_SERVER_FORCE_SEED`

Migrated from `docs/designs/015-force-seed-config/` on 2026-11-25. [design.md](design.md) holds the original design document unchanged, apart from rewritten links. Its own status line predates the migration and can be stale; the status below is the one verified against the code at migration time.

## Status

Implemented.

## Why

`ConfigService.init()` calls `seedFromClasspathDefault()` only inside the `isNullOrEmpty(config)`
branch:

## What Changes

Introduce one env var, `CONFIG_SERVER_FORCE_SEED` (default `false`), bound to the Spring
property `sdmxproxy.configserver.source.force-seed`. When `true`, `ConfigService.init()`
unconditionally loads the bundled classpath default, validates, writes via `ConfigWriter`,
and sets it as the current config — skipping the `isNullOrEmpty` guard entirely.

## Capabilities

None recorded. This change predates the specs in `openspec/specs/`, so it has no delta specs.

## Impact

See the files-affected section of [design.md](design.md).
