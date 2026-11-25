# Proposal: Structure cache preheat for slow registries

Migrated from `docs/designs/044-structure-cache-preheat/` on 2026-11-25. [design.md](design.md) holds the original design document unchanged, apart from rewritten links. Its own status line predates the migration and can be stale; the status below is the one verified against the code at migration time.

## Status

Implemented.

## Why

- **P1: Distinct-structure stampede.** A cold load sends 184 distinct OECD structure calls. At
  about 20 calls per minute, that is at least nine minutes, with hundreds of 429 retries. StatGPT
  gives up on many requests before the proxy answers.
- **P2: The proxy fetches the same artefacts twice.** For every dataflow, the proxy fetches
  `references=descendants`, which holds all concept schemes and codelists, and then fetches
  those artefacts again one by one. 146 of the 184 calls fetch data that's already in the raw
  cache.
- **P3: No warm state survives a restart or a configuration change.** `CACHE_MODE=LOCAL` loses
  everything on restart. `CacheService.invalidateAll()` runs on every configuration change, from
  the config server (`ConfigServerConfigExtractor.pollForUpdate`) or from `POST /config`.
- **P4: Entries expire under load.** Raw entries live 24 hours. Nothing refreshes them, so the
  stampede repeats a day after the last cold load.
- **P5: Empty answers are refetched every time.** When a registry answers HTTP 204 (for example
  for a removed artefact version), `RawStructureFetcherImpl` returns an empty array and caches
  nothing, so every request goes upstream again. The design 042 E2E run showed 21 such repeat
  calls for `IMF.STA:IRFCL(8.0.0)`.

## What Changes

The design has three parts:

## Capabilities

None recorded. This change predates the specs in `openspec/specs/`, so it has no delta specs.

## Impact

See the files-affected section of [design.md](design.md).
