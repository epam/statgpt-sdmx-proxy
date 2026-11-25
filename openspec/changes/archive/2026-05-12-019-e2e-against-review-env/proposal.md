# Proposal: E2E tests against a deployed review environment

Migrated from `docs/designs/019-e2e-against-review-env/` on 2026-11-25. [design.md](design.md) holds the original design document unchanged, apart from rewritten links. Its own status line predates the migration and can be stale; the status below is the one verified against the code at migration time.

## Status

Implemented.

## Why

We need `sdmx-proxy-e2e` to run against a base URL supplied externally
(an already-deployed review env) without the Testcontainers / Docker
machinery. The tests' assertion logic and registry configuration model
(`/config` POST mutation, parameterized AllPairs cases, limit emulation
diagnostics, the wildcard 501 contract) are all kept as-is — only the *how do
I find the server* part changes.

## What Changes

Introduce a `BaseUrlProvider` (replacing `ContainerFixture` as the single
source of truth for the proxy URL) that reads, in order:

## Capabilities

None recorded. This change predates the specs in `openspec/specs/`, so it has no delta specs.

## Impact

See the files-affected section of [design.md](design.md).
