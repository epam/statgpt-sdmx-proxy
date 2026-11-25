# Design: Enable Hierarchy Structures for IMF and BIS

## Context

StatGPT needs hierarchy structures (e.g. `H_BOP_BOP_AGG_ANALYTIC_PRESENTATION`) to support navigating hierarchical
code relationships in IMF and BIS data. Both registries expose hierarchy endpoints natively (SDMX 3.0), but the proxy
currently blocks these requests because `hierarchy` and `hierarchyassociation` are not listed in `supportedStructures`.

## Problem

Requesting a hierarchy structure through the proxy returns an error:

```
hierarchy structure type is not supported by SDMX version SDMX_3_0;
Supported structures: [datastructure, conceptscheme, codelist, dataflow]
```

The validation in `QueryTranslatorImpl.checkStructureTypeIsSupported()` rejects any structure type not in the
registry's `supportedStructures` set.

## Solution

Add `"hierarchy"` and `"hierarchyassociation"` to `supportedStructures` for both IMF and BIS SDMX 3.0 configurations.

No code changes are needed -- the proxy already supports hierarchy structures:

- `CustomSdmxJsonStructureReaderManagerV2` registers `SdmxJsonHierarchyAssociationReaderEngineV2`
- `CustomStaxAbstractStructureWriterEngineV21` registers `StaxHclWriterEngineV21` for XML 2.1 output
- The generic structure endpoint (`SdmxStructure30Controller`) handles any structure type

### SDMX structure types (per REST 2.2.0 spec)

| Type                   | Description                                                                 |
|------------------------|-----------------------------------------------------------------------------|
| `hierarchy`            | Defines hierarchical relationships between codes from one or more codelists |
| `hierarchyassociation` | Links a hierarchy to a component within a DSD or dataflow                   |

## Changes

### 1. `sdmx-proxy/src/main/resources/sdmx_registries_config.json`

Add to `supportedStructures` for both IMF and BIS:

```json
"supportedStructures": [
"datastructure", "conceptscheme", "codelist", "dataflow",
"hierarchy", "hierarchyassociation"
]
```

### 2. E2E test configs

- Add `hierarchy` and `hierarchyassociation` to `supportedStructures` in both IMF and BIS E2E registry configs
- Add IMF hierarchy artefact for specific-structure E2E tests:
  `IMF.STA:H_BOP_BOP_AGG_ANALYTIC_PRESENTATION(1.13.0)`
- BIS hierarchy E2E tests deferred until a valid hierarchy URN is identified

## Verification

1. `./gradlew clean build -x test` -- build succeeds
2. Manual test:
   `GET /api/sdmx/3.0/structure/hierarchy/IMF.STA/H_BOP_BOP_AGG_ANALYTIC_PRESENTATION/1.13.0?references=descendants&detail=full`
3. IMF E2E: `./gradlew :sdmx-proxy-e2e:test --tests "*.IMF_3_0_RegistryTestSuit"`
4. BIS E2E: `./gradlew :sdmx-proxy-e2e:test --tests "*.BIS_3_0_RegistryTestSuit"`
