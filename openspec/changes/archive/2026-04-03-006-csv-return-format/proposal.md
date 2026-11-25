# Proposal: Design: CSV Return Format with Parameters

Migrated from `docs/designs/006-csv-return-format/` on 2026-11-25. [design.md](design.md) holds the original design document unchanged, apart from rewritten links. Its own status line predates the migration and can be stale; the status below is the one verified against the code at migration time.

## Status

Implemented.

## Why

1. `ReturnFormat.CSV` had an empty content type (`""`), making it unusable as an Accept header to upstream registries
2. `determineDataReturnFormat` ignored `containsCsvParameters` and always fell back to JSON default format
3. No registry configs declared CSV support
4. sdmx-core's CSV v2 reader (`SdmxCsvDataReaderEngineV2`) cannot parse `labels=both` column headers

## What Changes

See [design.md](design.md).

## Capabilities

None recorded. This change predates the specs in `openspec/specs/`, so it has no delta specs.

## Impact

See the files-affected section of [design.md](design.md).
