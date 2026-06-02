# Design 033: Preserve MSD, Metadataflow, and MetadataProvisionAgreement in SDMX-JSON structure responses

**Status:** implemented (2026-06-02). Verified by unit tests, a gated e2e
pin against IMF.RES:WEO, and a manual run on a local proxy (MSD with all 39
metadata attributes now present in the DESCENDANTS response; direct
`/structure/metadatastructure/...` returns 200).

**As-built deviations from the original plan below:**
1. The e2e proxy-config allow-list lives in the per-suite
   `.../registry/{imf,bis}/3_0/{imf,bis}_3_0_registry_config.json`, not the
   `.../tests/config/sdmx_registries_config.json` referenced earlier (that
   one holds the SDMX 2.1 fallbacks). Both 3.0 per-suite files were updated.
2. `MetadataStructureDefinitionMapper` guards representation mapping: a
   presentational metadata attribute surfaces a non-null but empty
   `RepresentationBean`, which `RepresentationMapper` rejects with
   `UnexpectedStateException`. The mapper now treats "neither enumerated nor
   text format" as no local representation.
3. The e2e pin sends `references`/`detail` via the RestAssured query-param
   map, not embedded in the path — `RestClient.get(path)` uses
   `basePath(path)`, which swallows an embedded `?query` (the request would
   otherwise default to `references=none` and drop the MSD).
4. The flat reader-sanity unit test was dropped in favour of the e2e pin,
   which exercises the full reader→mapper→writer chain against live IMF and
   inherently proves the reader keeps the MSD. The metadataflow/MPA mapper
   tests use a real bean (with a metadata target) / a Mockito-stubbed bean
   respectively, because the MPA immutable bean enforces SDMX 2.1
   provider-scheme version rules unrelated to the mapper.

Follow-up to design 025 (DSD conversion fidelity) and design 027
(`PRESERVE_METADATA_ATTRIBUTE_USAGES`). Those preserved the DSD's
*reference* to its MSD and the MSD-derived *attribute usages*; this design
restores the **MSD artefact itself** (and the two sibling metadata
artefacts) when a registry returns them as DESCENDANTS.

## Scope

SDMX-JSON 2.0 structure conversion. In scope:

- `MetadataStructureDefinition` (MSD)
- `Metadataflow`
- `MetadataProvisionAgreement`

Out of scope: SDMX-ML 2.1 structure output (the XML writer path is a
separate engine; see "No changes required"), the data and availability
endpoints, and any new fixture.

## Context

A DESCENDANTS request against an IMF dataflow loses the MSD that the
upstream registry includes. Reproduced 2026-06-02 against
`IMF.RES:WEO(9.0.0)`:

```
GET /statgpt/sdmx-proxy/api/v0/sdmx/3.0/structure/dataflow/IMF.RES/WEO/9.0.0?references=DESCENDANTS&detail=full
Accept: application/vnd.sdmx.structure+json;version=2.0.0
```

| Source                                                              | Top-level `data.*` artefact keys                                                  |
|---------------------------------------------------------------------|-----------------------------------------------------------------------------------|
| Upstream `api.imf.org/external/sdmx/3.0/structure/...`              | `codelists`, `conceptSchemes`, `dataStructures`, `dataflows`, **`metadataStructures`** |
| Proxy (`localhost:8050`)                                            | `codelists`, `conceptSchemes`, `dataStructures`, `dataflows` — **no `metadataStructures`** |

The IMF SDMX 3.0 structure endpoint is configured with
`bypassEnabled: false`
(`sdmx-proxy-config/src/main/resources/sdmx_registries_config.json`), so
every response is round-tripped:

```
upstream JSON
  -> StructureFixtureService.applyFixtures            (raw-stream rewrites)
  -> sdmx-core CustomSdmxJsonStructureReaderManagerV2 -> SdmxBeans
  -> StructureMapperImpl                              -> jsdmx Artefacts
  -> jsdmx SDMX-JSON 2.0 JsonWriterFactory
  -> client JSON
```

## Problem

The loss is entirely in the proxy's own mapper. The two ends of the
pipeline already support metadata artefacts:

1. **Reader keeps them.**
   `CustomSdmxJsonStructureReaderManagerV2`
   (`sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/sdmxsource/CustomSdmxJsonStructureReaderManagerV2.java:63-66`)
   registers `SdmxJsonMetadataStructureReaderEngineV2`,
   `SdmxJsonMetadataflowReaderEngineV2`, and
   `SdmxJsonMetadataProvisionReaderEngineV2`, so the three artefacts land
   in `SdmxBeans`. Confirm with a parse test (see Test Plan) — if the
   reader populates only the MSD header and not its attribute descriptor,
   that is a separate sdmx-core limitation to note, but the JSON writer is
   symmetric with the reader so full population is expected.

2. **Writer can emit them.** The jsdmx model exposes
   `Artefacts.getMetadataStructureDefinitions()`,
   `getMetadataflows()`, and `getMetadataProvisionAgreements()`, and the
   SDMX-JSON 2.0 writer ships
   `MetadataStructureDefinitionWriter`, `MetadataflowWriter`, and
   `MetadataProvisionAgreementWriter` (verified in
   `sdmx30-infomodel-2.0.0.jar` and `sdmx-json20-2.0.0.jar`).

3. **The mapper drops them.**
   `StructureMapperImpl.map(SdmxBeans)`
   (`sdmx-proxy/src/main/java/com/epam/sdmxproxy/common/mapping/StructureMapperImpl.java:45-56`)
   builds the output `Artefacts` from a fixed list of seven bean types
   (codelists, concept schemes, data structures, dataflows, category
   schemes, content constraints, hierarchies). It never reads
   `beans.getMetadataStructures()`, `beans.getMetadataflows()`, or
   `beans.getMetadataProvisions()`. Those artefacts are parsed, held in
   beans, then silently discarded.

This is the same class of gap design 025 fixed in the mapper (Issue 3:
the DSD `metadata` URN). Design 025's rule: when sdmx-core's bean model
*holds* the data and the loss is in the proxy mapper, **extend the
mapper** — raw-JSON preservation (designs 025 Stage 2, 027, 031) is
reserved for fields the bean model has no slot for. Both `SdmxBeans` and
jsdmx `Artefacts` hold all three metadata artefacts, so this is squarely
a mapper fix.

## Approach

Add three `Mapper<T>` implementations and wire them into
`StructureMapperImpl.map()`. Additionally, add the three metadata
structure types to each registry's `supportedStructures` allow-list so
direct `/structure/metadatastructure/...` requests stop returning 404
(currently blocked at `QueryTranslatorImpl.checkStructureTypeIsSupported`,
`QueryTranslatorImpl.java:389-401`).

### Mapper reuse

All three mappers follow the established pattern (see `DataflowMapper`,
`DataStructureMapper`) and reuse the existing helper mappers constructed
in `StructureMapperImpl`'s constructor: `TextMapper`, `AnnotationMapper`,
`ReferenceMapper`, `RepresentationMapper`.

Source bean getters on `SdmxBeans`
(`io.sdmx.api.sdmx.model.beans.SdmxBeans`):

| Bean type                          | `SdmxBeans` getter            |
|------------------------------------|-------------------------------|
| `MetadataStructureDefinitionBean`  | `getMetadataStructures()`     |
| `MetadataFlowBean`                 | `getMetadataflows()`          |
| `MetadataProvisionAgreementBean`   | `getMetadataProvisions()`     |

### New: `MetadataflowMapper`

Mirrors `DataflowMapper`. `MetadataFlowBean.getMetadataStructureRef()`
gives the MSD reference; `Metadataflow` is a `StructureUsage`, so the
reference goes on `setStructure` (verify `MetadataflowImpl.setStructure`
exists — `DataflowImpl` has it and both extend the same StructureUsage
impl).

```java
package com.epam.sdmxproxy.common.mapping;

import com.epam.jsdmx.infomodel.sdmx30.Metadataflow;
import com.epam.jsdmx.infomodel.sdmx30.MetadataflowImpl;
import com.epam.jsdmx.infomodel.sdmx30.StructureClassImpl;
import com.epam.jsdmx.infomodel.sdmx30.Version;
import io.sdmx.api.sdmx.model.beans.metadatastructure.MetadataFlowBean;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class MetadataflowMapper implements Mapper<MetadataFlowBean> {

    private final AnnotationMapper annotationMapper;
    private final ReferenceMapper referenceMapper;
    private final TextMapper textMapper;

    @Override
    public Metadataflow map(MetadataFlowBean bean) {
        var mf = new MetadataflowImpl();
        mf.setOrganizationId(bean.getAgencyId());
        mf.setId(bean.getId());
        mf.setVersion(Version.createFromString(bean.getVersion().toString()));
        mf.setName(textMapper.map(bean.getNames()));
        mf.setDescription(textMapper.map(bean.getDescriptions()));
        mf.setAnnotations(annotationMapper.map(bean.getAnnotations()));
        if (bean.getMetadataStructureRef() != null) {
            mf.setStructure(referenceMapper.mapMaintainable(
                    bean.getMetadataStructureRef(), StructureClassImpl.METADATA_STRUCTURE));
        }
        return mf;
    }
}
```

### New: `MetadataProvisionAgreementMapper`

`MetadataProvisionAgreementImpl` exposes
`setControlledStructureUsage(ArtefactReference)`,
`setMetadataProvider(ArtefactReference)`, and `setDataSource(String)`.
Bean refs: `getMetadataflowRef()` (the controlled structure usage →
`StructureClassImpl.METADATAFLOW`) and `getMetadataProviderRef()` (→
`StructureClassImpl.METADATA_PROVIDER`).

```java
package com.epam.sdmxproxy.common.mapping;

import com.epam.jsdmx.infomodel.sdmx30.MetadataProvisionAgreement;
import com.epam.jsdmx.infomodel.sdmx30.MetadataProvisionAgreementImpl;
import com.epam.jsdmx.infomodel.sdmx30.StructureClassImpl;
import com.epam.jsdmx.infomodel.sdmx30.Version;
import io.sdmx.api.sdmx.model.beans.metadatastructure.MetadataProvisionAgreementBean;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class MetadataProvisionAgreementMapper implements Mapper<MetadataProvisionAgreementBean> {

    private final AnnotationMapper annotationMapper;
    private final ReferenceMapper referenceMapper;
    private final TextMapper textMapper;

    @Override
    public MetadataProvisionAgreement map(MetadataProvisionAgreementBean bean) {
        var mpa = new MetadataProvisionAgreementImpl();
        mpa.setOrganizationId(bean.getAgencyId());
        mpa.setId(bean.getId());
        mpa.setVersion(Version.createFromString(bean.getVersion().toString()));
        mpa.setName(textMapper.map(bean.getNames()));
        mpa.setDescription(textMapper.map(bean.getDescriptions()));
        mpa.setAnnotations(annotationMapper.map(bean.getAnnotations()));
        if (bean.getMetadataflowRef() != null) {
            mpa.setControlledStructureUsage(referenceMapper.mapMaintainable(
                    bean.getMetadataflowRef(), StructureClassImpl.METADATAFLOW));
        }
        if (bean.getMetadataProviderRef() != null) {
            mpa.setMetadataProvider(referenceMapper.mapMaintainable(
                    bean.getMetadataProviderRef(), StructureClassImpl.METADATA_PROVIDER));
        }
        // VERIFY at implementation: MetadataProvisionAgreementBean has no getDataSource()
        // in the decompiled interface. If a data-source accessor exists, wire it via
        // mpa.setDataSource(...); otherwise leave it unset.
        return mpa;
    }
}
```

### New: `MetadataStructureDefinitionMapper` (the substantive one)

The MSD carries a `MetadataAttributeDescriptor` whose components are
(potentially nested) `MetadataAttribute`s. The jsdmx shape:

- `MetadataStructureDefinitionImpl extends StructureImpl` — same
  maintainable setters as `DataStructureDefinitionImpl`
  (`setId`/`setOrganizationId`/`setVersion`/`setName`/`setDescription`/`setAnnotations`),
  plus `setAttributeDescriptor(MetadataAttributeDescriptor)`.
- `MetadataAttributeDescriptorImpl extends ComponentListImpl<MetadataAttribute>`
  — set `setId(...)` and `setComponents(List<MetadataAttribute>)`, exactly
  like `AttributeDescriptorImpl` in `DataStructureMapper`.
- `MetadataAttributeImpl extends AttributeComponentImpl` (→ `ComponentImpl`)
  — inherits `setId`, `setConceptIdentity(ArtefactReference)`,
  `setLocalRepresentation(Representation)`, `setAnnotations`; adds
  `setPresentational(boolean)`, `setMinOccurs(int)`, `setMaxOccurs(int)`,
  and `setHierarchy(List<MetadataAttribute>)` for nested attributes.

Source side: `MetadataStructureDefinitionBean` is a
`MetadataAttributeContainerBean` whose `getMetadataAttributes()` returns
`List<MetadataAttributeBean>`; each `MetadataAttributeBean` is itself a
container (recursion for nesting) and a `ComponentBean`
(`getConceptRef()`, `getRepresentation()`, `getId()`, plus
`getPresentational()` / `getMaxOccurs()`).

```java
package com.epam.sdmxproxy.common.mapping;

import com.epam.jsdmx.infomodel.sdmx30.MetadataAttribute;
import com.epam.jsdmx.infomodel.sdmx30.MetadataAttributeDescriptorImpl;
import com.epam.jsdmx.infomodel.sdmx30.MetadataAttributeImpl;
import com.epam.jsdmx.infomodel.sdmx30.MetadataStructureDefinition;
import com.epam.jsdmx.infomodel.sdmx30.MetadataStructureDefinitionImpl;
import com.epam.jsdmx.infomodel.sdmx30.StructureClassImpl;
import com.epam.jsdmx.infomodel.sdmx30.StreamUtils;
import com.epam.jsdmx.infomodel.sdmx30.Version;
import io.sdmx.api.sdmx.model.beans.metadatastructure.MetadataAttributeBean;
import io.sdmx.api.sdmx.model.beans.metadatastructure.MetadataStructureDefinitionBean;
import lombok.RequiredArgsConstructor;

import java.util.List;

@RequiredArgsConstructor
public class MetadataStructureDefinitionMapper implements Mapper<MetadataStructureDefinitionBean> {

    private final AnnotationMapper annotationMapper;
    private final ReferenceMapper referenceMapper;
    private final RepresentationMapper representationMapper;
    private final TextMapper textMapper;

    @Override
    public MetadataStructureDefinition map(MetadataStructureDefinitionBean bean) {
        var msd = new MetadataStructureDefinitionImpl();
        msd.setOrganizationId(bean.getAgencyId());
        msd.setId(bean.getId());
        msd.setVersion(Version.createFromString(bean.getVersion().toString()));
        msd.setName(textMapper.map(bean.getNames()));
        msd.setDescription(textMapper.map(bean.getDescriptions()));
        msd.setAnnotations(annotationMapper.map(bean.getAnnotations()));

        var descriptor = new MetadataAttributeDescriptorImpl();
        descriptor.setId("MetadataAttributeDescriptor");
        descriptor.setComponents(mapAttributes(bean.getMetadataAttributes()));
        msd.setAttributeDescriptor(descriptor);
        return msd;
    }

    private List<MetadataAttribute> mapAttributes(List<MetadataAttributeBean> beans) {
        return StreamUtils.streamOfNullable(beans)
                .map(this::mapAttribute)
                .toList();
    }

    private MetadataAttribute mapAttribute(MetadataAttributeBean bean) {
        var attr = new MetadataAttributeImpl();
        attr.setId(bean.getId());
        if (bean.getConceptRef() != null) {
            attr.setConceptIdentity(referenceMapper.mapItem(bean.getConceptRef(), StructureClassImpl.CONCEPT));
        }
        attr.setLocalRepresentation(representationMapper.map(bean.getRepresentation()));
        attr.setAnnotations(annotationMapper.map(bean.getAnnotations()));
        attr.setPresentational(bean.getPresentational());
        attr.setMaxOccurs(bean.getMaxOccurs());
        // VERIFY at implementation: confirm MetadataAttributeBean exposes getMinOccurs();
        // if absent, omit setMinOccurs or derive from the mutable bean.
        // attr.setMinOccurs(bean.getMinOccurs());
        List<MetadataAttribute> nested = mapAttributes(bean.getMetadataAttributes());
        if (!nested.isEmpty()) {
            attr.setHierarchy(nested);
        }
        return attr;
    }
}
```

### Wiring into `StructureMapperImpl`

Add three fields, construct them, and add three lines to `map()`:

```java
// fields
private final Mapper<MetadataStructureDefinitionBean> metadataStructureMapper;
private final Mapper<MetadataFlowBean> metadataflowMapper;
private final Mapper<MetadataProvisionAgreementBean> metadataProvisionAgreementMapper;

// in constructor, after hierarchyMapper:
metadataStructureMapper = new MetadataStructureDefinitionMapper(annotationMapper, referenceMapper, representationMapper, textMapper);
metadataflowMapper = new MetadataflowMapper(annotationMapper, referenceMapper, textMapper);
metadataProvisionAgreementMapper = new MetadataProvisionAgreementMapper(annotationMapper, referenceMapper, textMapper);

// in map(), before `return artefacts;`
artefacts.addMaintainables(metadataStructureMapper.map(beans.getMetadataStructures()));
artefacts.addMaintainables(metadataflowMapper.map(beans.getMetadataflows()));
artefacts.addMaintainables(metadataProvisionAgreementMapper.map(beans.getMetadataProvisions()));
```

`Mapper<T>` already has a `map(Collection<T>)` overload (used today for all
seven existing types via `codelistMapper.map(beans.getCodelists())`),
so no batch-mapping helper is needed — confirm the interface signature in
`Mapper.java` and follow it.

### Config: allow-list

Add the three types to `supportedStructures` for every SDMX 3.0 registry
block in both config files:

`sdmx-proxy-config/src/main/resources/sdmx_registries_config.json`
(BIS block ~line 16-23, IMF block ~line 93-100) and
`sdmx-proxy-e2e/src/test/resources/com/epam/sdmxproxy/e2e/tests/config/sdmx_registries_config.json`:

```json
"supportedStructures": [
  "datastructure",
  "conceptscheme",
  "codelist",
  "dataflow",
  "hierarchy",
  "hierarchyassociation",
  "metadatastructure",
  "metadataflow",
  "metadataprovisionagreement"
]
```

Match the exact lowercase tokens the controller passes through; verify
against the SDMX-REST path grammar
(`sdmx-rest-2.2.0/`) — the structure-type path segment is lowercase and
hyphen-free (`metadatastructure`, `metadataflow`,
`metadataprovisionagreement`).

## Files Affected

| File | Status | Change |
|------|--------|--------|
| `sdmx-proxy/src/main/java/com/epam/sdmxproxy/common/mapping/MetadataStructureDefinitionMapper.java` | New | Full MSD bean → jsdmx mapper |
| `sdmx-proxy/src/main/java/com/epam/sdmxproxy/common/mapping/MetadataflowMapper.java` | New | Metadataflow mapper |
| `sdmx-proxy/src/main/java/com/epam/sdmxproxy/common/mapping/MetadataProvisionAgreementMapper.java` | New | Metadata provision agreement mapper |
| `sdmx-proxy/src/main/java/com/epam/sdmxproxy/common/mapping/StructureMapperImpl.java` | Modified | 3 fields + 3 constructor lines + 3 `addMaintainables` calls |
| `sdmx-proxy-config/src/main/resources/sdmx_registries_config.json` | Modified | Add 3 types to BIS + IMF `supportedStructures` |
| `sdmx-proxy-e2e/src/test/resources/com/epam/sdmxproxy/e2e/tests/config/sdmx_registries_config.json` | Modified | Same allow-list addition |
| `sdmx-proxy/src/test/java/com/epam/sdmxproxy/common/mapping/MetadataStructureDefinitionMapperTest.java` | New | Unit test |
| `sdmx-proxy/src/test/java/com/epam/sdmxproxy/common/mapping/MetadataflowMapperTest.java` | New | Unit test |
| `sdmx-proxy/src/test/java/com/epam/sdmxproxy/common/mapping/MetadataProvisionAgreementMapperTest.java` | New | Unit test |
| `sdmx-proxy-e2e/src/test/java/com/epam/sdmxproxy/e2e/tests/framework/config/MetadataDescendantsTestSuitConfiguration.java` | New | Gated e2e config block |
| `sdmx-proxy-e2e/src/test/java/com/epam/sdmxproxy/e2e/tests/framework/config/RegistryTestSuitConfiguration.java` | Modified | Add `metadataDescendantsTestSuitConfiguration` field |
| `sdmx-proxy-e2e/src/test/java/com/epam/sdmxproxy/e2e/tests/framework/BaseRegistryTestSuite.java` | Modified | Add gated pin `testStructureMetadataDescendantsPreserved` |
| `sdmx-proxy-e2e/src/test/resources/com/epam/sdmxproxy/e2e/tests/registry/imf/3_0/imf_3_0_test_config.json` | Modified | Populate the new config block for IMF WEO |

## No changes required

- **`CustomSdmxJsonStructureReaderManagerV2`** — already registers all
  three metadata reader engines.
- **`CustomStaxAbstractStructureWriterEngineV21`** — the SDMX-ML 2.1
  writer path; out of scope (JSON only). It already maps
  `SDMX_STRUCTURE_TYPE.MSD`, so no regression risk either way.
- **`AdapterRouterImpl`** — the conversion entry point is unchanged; the
  fix is entirely inside the mapper it already calls.
- **`StreamingStructureConversionService`** — `writeAsJson` already calls
  `StructureMapperImpl.map` then the `JsonWriterFactory`; both handle the
  new artefacts once the mapper populates them.

## Test Plan

### Unit tests (`sdmx-proxy/src/test`)

Mirror `DataStructureMapperTest` — build sdmx-core mutable beans, map,
assert on the jsdmx output. Use sdmx-core mutable builders
(`MetadataStructureDefinitionMutableBeanImpl`,
`MetadataflowMutableBeanImpl`, `MetadataProvisionAgreementMutableBeanImpl`
— verify exact class names in `io.sdmx.im.mutable.metadatastructure`).

`MetadataStructureDefinitionMapperTest`:
- `mapsIdentityAndName` — agency/id/version/name round-trip.
- `mapsAttributeDescriptorWithAttributes` — a 2-attribute MSD yields a
  descriptor whose `getComponents()` has 2 `MetadataAttribute`s with the
  right ids, concept identities, and representations.
- `mapsNestedMetadataAttributes` — a parent attribute with one child
  yields `getHierarchy()` of size 1.
- `mapsEmptyAttributeDescriptor` — MSD with no attributes yields an empty
  (non-null) descriptor.

`MetadataflowMapperTest`:
- `mapsMetadataStructureReference` — `getStructure()` points at the MSD
  with `StructureClassImpl.METADATA_STRUCTURE`.
- `mapsIdentityAndName`.

`MetadataProvisionAgreementMapperTest`:
- `mapsControlledStructureUsageAndProvider` — both refs populated with the
  correct structure classes.

Add a case to the existing `StructureMapperImpl` coverage (or a new
`StructureMapperImplTest`) asserting that a `SdmxBeans` containing one MSD
+ one metadataflow + one MPA produces an `Artefacts` whose
`getMetadataStructureDefinitions()`, `getMetadataflows()`, and
`getMetadataProvisionAgreements()` are each non-empty.

**Reader sanity test (important):** parse the captured upstream WEO JSON
through `JsonV2StructureReaderFactory` and assert
`beans.getMetadataStructures()` is non-empty and the MSD's
`getMetadataAttributes()` is populated. This confirms premise (1) — that
the reader keeps the full MSD — before relying on the mapper. A fixture
sample can be trimmed from the live upstream response.

### E2E test (gated pin)

Add `MetadataDescendantsTestSuitConfiguration` (mirrors
`DsdFidelityTestSuitConfiguration`) with fields:

```java
@Data
@NoArgsConstructor
public class MetadataDescendantsTestSuitConfiguration {
    /** Required: dataflow to fetch with references=descendants. */
    private String dataflowUrn;
    /** Accept header. Defaults to application/vnd.sdmx.structure+json;version=2.0.0. */
    private String mediaType;
    /** If true, assert data.metadataStructures is a non-empty array. */
    private boolean expectMetadataStructures;
    /** Optional substring the first MSD's urn/id must contain (e.g. "MSD_WEO"). */
    private String expectedMsdIdContains;
    /** If true, assert data.metadataflows is non-empty (omit if registry has none). */
    private boolean expectMetadataflows;
    /** If true, assert data.metadataProvisionAgreements is non-empty. */
    private boolean expectMetadataProvisionAgreements;
}
```

Register it on `RegistryTestSuitConfiguration` as
`metadataDescendantsTestSuitConfiguration`. Add the pin to
`BaseRegistryTestSuite`, following `testDsdConversionFidelity`:

```java
@Test
@DisplayName("Structure Endpoint: DESCENDANTS preserves MSD / metadataflow / metadataProvisionAgreement")
@SneakyThrows
void testStructureMetadataDescendantsPreserved() {
    MetadataDescendantsTestSuitConfiguration cfg = testConfig.getMetadataDescendantsTestSuitConfiguration();
    Assumptions.assumeTrue(cfg != null,
            "No metadataDescendantsTestSuitConfiguration -- skipping MSD descendants pin");

    String[] p = parseUrn(cfg.getDataflowUrn());
    String path = String.format("%s/sdmx/3.0/structure/dataflow/%s/%s/%s?references=descendants&detail=full",
            BASE_PATH, p[0], p[1], p[2]);
    String accept = cfg.getMediaType() != null ? cfg.getMediaType()
            : "application/vnd.sdmx.structure+json;version=2.0.0";

    Response response = restClient.getResponseWithAccept(path, accept);
    assertThat(response.getStatusCode())
            .as("DESCENDANTS request must return HTTP 200 (dataflow=%s)", cfg.getDataflowUrn())
            .isEqualTo(200);

    JsonNode data = objectMapper.readTree(response.getBody().asByteArray()).path("data");

    if (cfg.isExpectMetadataStructures()) {
        JsonNode msds = data.path("metadataStructures");
        assertThat(msds.isArray() && !msds.isEmpty())
                .as("data.metadataStructures must be non-empty -- the MSD mapper restores it (design 033)")
                .isTrue();
        if (cfg.getExpectedMsdIdContains() != null && !cfg.getExpectedMsdIdContains().isBlank()) {
            assertThat(msds.get(0).path("id").asText())
                    .as("first MSD id must contain %s", cfg.getExpectedMsdIdContains())
                    .contains(cfg.getExpectedMsdIdContains());
        }
        // Fidelity guard: the restored MSD must not be a bare stub.
        JsonNode attrs = msds.get(0)
                .path("metadataStructureComponents")
                .path("metadataAttributeList")
                .path("metadataAttributes");
        assertThat(attrs.isArray() && !attrs.isEmpty())
                .as("MSD must carry its metadataAttributes, not just a header")
                .isTrue();
    }
    if (cfg.isExpectMetadataflows()) {
        assertThat(data.path("metadataflows").isArray() && !data.path("metadataflows").isEmpty())
                .as("data.metadataflows must be non-empty").isTrue();
    }
    if (cfg.isExpectMetadataProvisionAgreements()) {
        assertThat(data.path("metadataProvisionAgreements").isArray()
                && !data.path("metadataProvisionAgreements").isEmpty())
                .as("data.metadataProvisionAgreements must be non-empty").isTrue();
    }
}
```

IMF config block (`imf_3_0_test_config.json`) — WEO returns an MSD as a
descendant but **not** metadataflows/MPAs, so only the MSD assertion is
turned on:

```json
"metadataDescendantsTestSuitConfiguration": {
  "dataflowUrn": "IMF.RES:WEO(9.0.0)",
  "expectMetadataStructures": true,
  "expectedMsdIdContains": "MSD_WEO"
}
```

Verify the exact MSD `id` substring and the JSON path
`metadataStructureComponents.metadataAttributeList.metadataAttributes`
against the live upstream sample before pinning — adjust if the SDMX-JSON
2.0 key names differ.

### Manual verification

Port 8050 is occupied this session; run the local proxy on **8051**.

```powershell
# terminal A — start proxy on an alternate port
.\gradlew.bat :sdmx-proxy:bootRun "--args=--server.port=8051"
```

```bash
# terminal B — proxy must now include metadataStructures
curl -s -H "Accept: application/vnd.sdmx.structure+json;version=2.0.0" \
  "http://localhost:8051/statgpt/sdmx-proxy/api/v0/sdmx/3.0/structure/dataflow/IMF.RES/WEO/9.0.0?references=DESCENDANTS&detail=full" \
  | grep -oE '"(metadataStructures|metadataflows|metadataProvisionAgreements)"\s*:' | sort | uniq -c

# direct MSD request must now return 200 (was 404)
curl -s -o /dev/null -w "%{http_code}\n" -H "Accept: application/vnd.sdmx.structure+json;version=2.0.0" \
  "http://localhost:8051/statgpt/sdmx-proxy/api/v0/sdmx/3.0/structure/metadatastructure/IMF.RES/MSD_WEO/+?references=none&detail=full"
```

Diff proxy vs upstream MSD object to confirm field-level fidelity (names,
attribute ids, representations).

## Edge cases

| Case | Expected behaviour |
|------|--------------------|
| Registry returns no metadata artefacts | `beans.getMetadata*()` empty → `addMaintainables` adds nothing → output unchanged. No regression for non-metadata responses. |
| MSD with nested metadata attributes | Recursion via `setHierarchy`; covered by unit test. |
| MSD attribute with no representation | `representationMapper.map(null)` must tolerate null (it does for DSD components — confirm). |
| MPA without metadata-provider ref | Null-guarded; field left unset. |
| Output format = SDMX-ML 2.1 | Different writer path, out of scope; behaviour unchanged. |
| Reader populates only MSD header (no attributes) | Mapper maps an empty descriptor; the reader-sanity unit test will catch this and flag it as an sdmx-core limitation rather than a mapper bug. |

## Open questions / verify at implementation

1. `MetadataAttributeBean.getMinOccurs()` — present? (only `getMaxOccurs()`
   confirmed in the decompiled interface).
2. `MetadataProvisionAgreementBean` data-source accessor — none seen;
   confirm whether `dataSource` is reachable from the bean.
3. `MetadataflowImpl.setStructure(...)` — confirm the setter exists on the
   StructureUsage impl (analogue of `DataflowImpl.setStructure`).
4. `Mapper<T>` collection-overload signature — confirm `map(Collection<T>)`
   vs `map(Set<T>)` so the `addMaintainables(...map(beans.getMetadata*()))`
   calls compile.
5. SDMX-JSON 2.0 MSD key names
   (`metadataStructureComponents` / `metadataAttributeList` /
   `metadataAttributes`) — confirm against the live sample for the e2e
   assertion path.
6. Whether BIS SDMX 3.0 actually serves any metadata artefacts; if not,
   the allow-list addition is harmless but its e2e block stays unset.
