# Proposal: `all` keyword alias for `*` on the structure endpoint

Migrated from `docs/designs/024-structure-all-keyword-alias/` on 2026-11-25. [design.md](design.md) holds the original design document unchanged, apart from rewritten links. Its own status line predates the migration and can be stale; the status below is the one verified against the code at migration time.

## Status

Implemented.

## Why

`/structure/{type}/all/all/all` and similar mixed forms (e.g.
`/structure/dataflow/BIS/MY_FLOW/all`) currently route as specific-
artefact queries against an artefact literally named "all". The
existing wildcard / fan-out paths only trigger on the exact string
`*`.

## What Changes

The wildcard vocabulary (`*` vs `all`) is SDMX wire-syntax knowledge;
that belongs to the translator. The rule lives in `QueryTranslator`,
and no other component ever sees the literal string `all`.

## Capabilities

None recorded. This change predates the specs in `openspec/specs/`, so it has no delta specs.

## Impact

See the files-affected section of [design.md](design.md).
