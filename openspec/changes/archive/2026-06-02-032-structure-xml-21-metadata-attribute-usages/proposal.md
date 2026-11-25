# Proposal: Preserve `metadataAttributeUsages` on JSON 2.0 -> XML 2.1 structure conversion

Migrated from `docs/designs/032-structure-xml-21-metadata-attribute-usages/` on 2026-11-25. [design.md](design.md) holds the original design document unchanged, apart from rewritten links. Its own status line predates the migration and can be stale; the status below is the one verified against the code at migration time.

## Status

Implemented.

## Why

Every structure request for an IMF DSD with `Accept: ...+xml;version=2.1`
produces a swallowed exception per DSD:

## What Changes

1. **Representation:** fold each `metadataAttributeUsage` into the
   `AttributeList` as a `DataAttribute`, matching IMF's native 2.1. This is
   fidelity to the registry's canonical 2.1 form, not strict-standard 2.1
   (strict 2.1 would drop them). Chosen over dropping because the proxy's job
   is to mirror what the registry serves at 2.1.
2. **MSD acquisition:** targeted **side-fetch + splice**. When gated, fetch
   only the MSD, splice its `metadataStructures[]` into the raw DSD JSON for the
   fixture, and remove it before conversion. Output stays exactly the requested
   DSD (no MSD/codelist leak). Chosen over `references=descendants` (3.2 MB +
   strip-what-the-client-didn't-ask-for complexity).
3. **Config marker:** reuse the existing `PRESERVE_METADATA_ATTRIBUTE_USAGES`
   marker. One type, two output-format-specific behaviors. No enum/config
   change; IMF already has the marker wired.
4. **Selection by output format:** extend the `StructureFixture` framework so a
   fixture can declare which client output it applies to. The fold fixture
   applies only to XML 2.1 output; the JSON preserver path applies only to JSON
   output.

## Capabilities

None recorded. This change predates the specs in `openspec/specs/`, so it has no delta specs.

## Impact

See the files-affected section of [design.md](design.md).
