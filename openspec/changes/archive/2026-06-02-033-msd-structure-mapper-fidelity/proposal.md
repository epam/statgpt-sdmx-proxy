# Proposal: Preserve MSD, Metadataflow, and MetadataProvisionAgreement in SDMX-JSON structure responses

Migrated from `docs/designs/033-msd-structure-mapper-fidelity/` on 2026-11-25. [design.md](design.md) holds the original design document unchanged, apart from rewritten links. Its own status line predates the migration and can be stale; the status below is the one verified against the code at migration time.

## Status

Implemented.

## Why

The loss is entirely in the proxy's own mapper. The two ends of the
pipeline already support metadata artefacts:

## What Changes

Add three `Mapper<T>` implementations and wire them into
`StructureMapperImpl.map()`. Additionally, add the three metadata
structure types to each registry's `supportedStructures` allow-list so
direct `/structure/metadatastructure/...` requests stop returning 404
(currently blocked at `QueryTranslatorImpl.checkStructureTypeIsSupported`,
`QueryTranslatorImpl.java:389-401`).

## Capabilities

None recorded. This change predates the specs in `openspec/specs/`, so it has no delta specs.

## Impact

See the files-affected section of [design.md](design.md).
