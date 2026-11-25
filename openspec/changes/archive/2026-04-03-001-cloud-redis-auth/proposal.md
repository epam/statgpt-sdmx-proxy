# Proposal: Cloud-Provider Redis Authentication for SDMX Proxy

Migrated from `docs/designs/001-cloud-redis-auth/` on 2026-11-25. [design.md](design.md) holds the original design document unchanged, apart from rewritten links. Its own status line predates the migration and can be stale; the status below is the one verified against the code at migration time.

## Status

Implemented.

## Why

Currently, SDMX Proxy connects to Redis using static host/port/password credentials (`REDIS_HOST`, `REDIS_PORT`,
`REDIS_PASSWORD` env vars). This works for local development but is insufficient for cloud deployments where best
practice is to use managed identity / IAM roles for authentication (no static passwords).

## What Changes

New property `sdmxproxy.cache.redis.provider` (env: `REDIS_AUTH_PROVIDER`, default: `NONE`).

## Capabilities

None recorded. This change predates the specs in `openspec/specs/`, so it has no delta specs.

## Impact

See the files-affected section of [design.md](design.md).
