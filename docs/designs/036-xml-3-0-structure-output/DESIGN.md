# Design 036 — SDMX 3.0 XML structure output

Status: Draft (pending human review)
Author: design-feature skill
Related: builds on 035 (unified `SdmxFormat`), 032 (XML 2.1 usage folding), 033 (MSD/Metadataflow/MPA mapper fidelity), 025 (DSD conversion fidelity)

## Summary

The proxy can return structures as SDMX-JSON 2.0 and SDMX-ML 2.1, but not as
SDMX-ML 3.0 (`application/vnd.sdmx.structure+xml;version=3.0.0`). Registries are
still read as JSON 2.0; this change adds the **outbound** XML 3.0 writer path
(JSON 2.0 read -> `SdmxBeans` -> XML 3.0 write), analogous to the existing XML 2.1
arm. No native XML 3.0 *reading* / bypass is introduced (explicitly out of scope —
see Non-goals).

The enum member `SdmxFormat.XML_STRUCTURE_3_0_0` and the media-type constant
`SdmxMediaTypes.STRUCTURE_XML_3_0_0` already exist (added in 035); the writer arm
that consumes them is currently a hard `UnsupportedConversionException`.

The one **format-specific** gap is **DSD `metadataAttributeUsages`** on the XML 3.0
output. To be precise about what already works: the sdmx-core bean round-trip drops
usages (the custom JSON reader skips them, no bean slot exists), but the proxy
already compensates per output format — **JSON 2.0 output is not broken**:
- **JSON 2.0**: `AdapterRouterImpl` captures usages from the raw upstream JSON
  *before* conversion and re-injects them into the converted JSON *after*
  (`MetadataAttributeUsagePreserver.capture`/`inject`), gated by the
  `PRESERVE_METADATA_ATTRIBUTE_USAGES` marker fixture. Output is correct.
- **XML 2.1**: usages are folded into regular `DataAttribute` elements by
  `MetadataAttributeUsageFolder` (design 032).
- **XML 3.0**: **no equivalent exists today** — so usages are genuinely absent from
  XML 3.0 output. This is the gap this design closes, with a post-conversion XML
  injector that mirrors the proven JSON capture/reinject pattern (reusing
  `MetadataAttributeUsagePreserver.capture`) but emits native
  `<structure:MetadataAttributeUsage>` elements.

Every other artefact type (MSD, Metadataflow, MetadataProvisionAgreement, DSD-level
MSD reference) survives the `SdmxBeans` round-trip and is emitted by the v3 writer —
these need verification, not new code.

## Background — two output pipelines

Structure conversion has two distinct writer pipelines. This distinction is the
crux of the design:

- **JSON output**: `SdmxBeans` -> `StructureMapperImpl.map()` -> jsdmx `Artefacts`
  -> `JsonWriterFactory`. The 033 MSD/Metadataflow/MPA mappers live *here*.
- **XML output (2.1 and 3.0)**: `SdmxBeans` -> `StaxStructureWriterEngineV{21,3}`
  -> bytes. **No jsdmx mapping.** The 033 mappers are irrelevant to XML output.

So for XML 3.0, what matters is (a) what the JSON 2.0 reader puts into `SdmxBeans`
and (b) what `StaxStructureWriterEngineV3` emits from `SdmxBeans`.

### Per-artefact verification (already done against source + live registries)

| Artefact | In `SdmxBeans` after JSON 2.0 read? | v3 XML writer emits it? | Verdict |
|---|---|---|---|
| Codelists, ConceptSchemes, AgencySchemes | yes | yes | OK |
| DSD dimensions / attributes / measures | yes | yes | OK |
| DSD-level MSD reference (`<str:Metadata>`) | yes — `CustomSdmxJsonDataStructureReaderEngineV2:72-73` calls `setMSDReference(...)` | yes — `StaxDsdWriterEngineV3:64` writes `getMSDRef()` | OK (verify in E2E) |
| MSD / Metadataflow / MetadataProvisionAgreement | yes — readers registered in `CustomSdmxJsonStructureReaderManagerV2` | yes — engines registered in `StaxStructureWriterEngineV3:141-148` | OK (verify in E2E) |
| **DSD `metadataAttributeUsages`** | **NO** — reader skips them (`CustomSdmxJsonDataStructureReaderEngineV2:251-260`, "absent in sdmx source infomodel"); no bean slot exists | **NO** — `StaxDsdWriterEngineV3` writes only `Attribute` elements, zero refs to `MetadataAttributeUsage` | **Absent from XML 3.0 output** — JSON 2.0 compensates via capture/reinject and XML 2.1 via folding, but the XML 3.0 path has neither. **Needs fix.** |

`StaxDsdWriterEngineV3` source:
`sdmx-core-2.3.9/fusion-sdmx-ml/src/main/java/io/sdmx/format/ml/engine/structure/writer/v3/dsd/StaxDsdWriterEngineV3.java`
`StaxStructureWriterEngineV3` source:
`sdmx-core-2.3.9/fusion-sdmx-ml/src/main/java/io/sdmx/format/ml/engine/structure/writer/v3/StaxStructureWriterEngineV3.java`

## Discovery baselines (live registries, 2026-06-09)

Authoritative reference for what correct XML 3.0 looks like. (The recon captures
were saved to local temp dirs during design, **not committed** — the implementer
re-fetches the baselines on demand via the curl commands in Manual verification, or
uses the committed test fixture `qnea_dsd_with_msd.json`.)

- **IMF** (`api.imf.org/external/sdmx/3.0`): returns the proper versioned
  content-type (`...structure+xml;version=3.0.0`). `IMF.STA:DSD_ANEA(8.0.0)`
  carries **25 `MetadataAttributeUsage` elements** natively, plus a DSD-level
  `<structure:Metadata>` MSD reference. This is the registry that reproduces the
  drop bug.
- **BIS** (`stats.bis.org/api/v2`): returns generic `application/xml`
  (no version param). **Zero** `metadataAttributeUsages` on any of its 27 DSDs —
  so BIS exercises the rest of the XML 3.0 path but not the usage injector.
- Both: counts match exactly between the registry's own XML 3.0 and JSON 2.0 —
  nothing is dropped at source; any loss is ours.

### Ground-truth `MetadataAttributeUsage` shape (from IMF `DSD_ANEA`)

All usages appear **first** in `<structure:AttributeList>`, before the regular
`<structure:Attribute>` elements. Each carries a synthesized `urn` and a
`MetadataAttributeReference`; the `AttributeRelationship` (XSD-required — see note
below) follows the JSON `attributeRelationship` shape.

(Snippets below use the `structure:` prefix for readability; the proxy's v3 writer
actually emits the `str:` prefix for the same namespace. The injector reuses
whatever prefix the live `AttributeList` element carries, so it is prefix-agnostic.)

```xml
<structure:AttributeList id="AttributeDescriptor">
  <!-- JSON {"metadataAttributeReference":"DOI","attributeRelationship":{"none":{}}} -->
  <!-- We KEEP the dataset/DSD-level relationship as <Dataflow/> (see relationship map). -->
  <structure:MetadataAttributeUsage urn="urn:sdmx:org.sdmx.infomodel.metadatastructure.MetadataAttribute=IMF.STA:DSD_ANEA(8.0.0).DOI">
    <structure:MetadataAttributeReference>DOI</structure:MetadataAttributeReference>
    <structure:AttributeRelationship><structure:Dataflow/></structure:AttributeRelationship>
  </structure:MetadataAttributeUsage>
  <!-- JSON {"metadataAttributeReference":"TOPIC","attributeRelationship":{"dimensions":["INDICATOR","PRICE_TYPE","TYPE_OF_TRANSFORMATION"]}} -->
  <structure:MetadataAttributeUsage urn="...MetadataAttribute=IMF.STA:DSD_ANEA(8.0.0).TOPIC">
    <structure:MetadataAttributeReference>TOPIC</structure:MetadataAttributeReference>
    <structure:AttributeRelationship>
      <structure:Dimension>INDICATOR</structure:Dimension>
      <structure:Dimension>PRICE_TYPE</structure:Dimension>
      <structure:Dimension>TYPE_OF_TRANSFORMATION</structure:Dimension>
    </structure:AttributeRelationship>
  </structure:MetadataAttributeUsage>
  <!-- JSON {"metadataAttributeReference":"ACCESS_SHARING_LEVEL","attributeRelationship":{"observation":{}}} -->
  <structure:MetadataAttributeUsage urn="...MetadataAttribute=IMF.STA:DSD_ANEA(8.0.0).ACCESS_SHARING_LEVEL">
    <structure:MetadataAttributeReference>ACCESS_SHARING_LEVEL</structure:MetadataAttributeReference>
    <structure:AttributeRelationship><structure:Observation/></structure:AttributeRelationship>
  </structure:MetadataAttributeUsage>
  <!-- ... then the regular <structure:Attribute> elements emitted by the v3 writer ... -->
</structure:AttributeList>
```

### Relationship encoding map (authoritative — matches IMF baseline)

| JSON `attributeRelationship` | XML 3.0 emitted |
|---|---|
| `{"none":{}}` | `<structure:AttributeRelationship><structure:Dataflow/></structure:AttributeRelationship>` (dataset/DSD-level — kept, not dropped) |
| `{"dataflow":{}}` | `<structure:AttributeRelationship><structure:Dataflow/></structure:AttributeRelationship>` |
| `{"observation":{}}` | `<structure:AttributeRelationship><structure:Observation/></structure:AttributeRelationship>` |
| `{"dimensions":["A","B"]}` | `<structure:AttributeRelationship><structure:Dimension>A</structure:Dimension><structure:Dimension>B</structure:Dimension></structure:AttributeRelationship>` |
| `{"group":"G"}` | `<structure:AttributeRelationship><structure:Group>G</structure:Group></structure:AttributeRelationship>` (per XSD; not observed in live data) |

Note on `none` (decision): a `none` relationship denotes a dataset/DSD-level
attribute that does not vary with any dimension. We **keep** it rather than drop
it. SDMX-ML 3.0's `AttributeRelationshipType` (`SDMXStructureDataStructure.xsd`)
has no literal `None` element — its choice is `Dataflow | Dimension+ | Group |
Observation` — and `<Dataflow/>` is precisely "the value does not vary with any
dimension; treated as a dataflow/dataset-level attribute" (XSD doc). So `none` maps
to `<Dataflow/>`. This is consistent on three axes: (a) the JSON reader already
collapses both `none` and `dataflow` to `ATTRIBUTE_ATTACHMENT_LEVEL.DATA_SET`
(`CustomSdmxJsonDataStructureReaderEngineV2:332-339`); (b) the v3 writer already
emits `<Dataflow/>` for dataset-level *regular* attributes
(`StaxDsdWriterEngineV3:157`), so usages render uniformly; (c) it is schema-valid,
unlike IMF's own output, which omits the (XSD-required) relationship for `none`.
This is a **deliberate divergence from the IMF baseline** (IMF omits; we keep),
chosen for schema-validity and to not lose the dataset-level relationship.

`AttributeListType` is an unbounded `<xs:choice>` of `Attribute | MetadataAttributeUsage`,
so usage elements may legally appear in any position — placing them first matches IMF.

The `urn` attribute is synthesized from the **DSD's** coordinates (not the MSD's),
with the plain DSD version (e.g. `8.0.0`):
`urn:sdmx:org.sdmx.infomodel.metadatastructure.MetadataAttribute={dsdAgency}:{dsdId}({dsdVersion}).{reference}`.
It is optional in the schema (`MetadataAttributeUrnType` validates by prefix only —
`SDMXCommonReferences.xsd`), and is **cosmetic / IMF-baseline-matching** — not a
semantically resolvable reference to the MSD attribute. Synthesizing it matches the
live IMF output.

## Goals

1. Return valid SDMX-ML 3.0 structure documents for
   `Accept: application/vnd.sdmx.structure+xml;version=3.0.0`, converted from the
   registry's JSON 2.0.
2. Preserve DSD `metadataAttributeUsages` on the XML 3.0 path with the native
   `<structure:MetadataAttributeUsage>` element, gated by the existing
   `PRESERVE_METADATA_ATTRIBUTE_USAGES` fixture (so it runs only for registries
   known to carry usages, e.g. IMF — never for BIS).
3. Verify (via E2E against BIS + IMF) that MSD, Metadataflow,
   MetadataProvisionAgreement and the DSD-level MSD reference arrive intact on the
   XML 3.0 path; add tests where coverage is thin.

## Non-goals

- **No native XML 3.0 reading / bypass.** Even though IMF serves correct XML 3.0
  natively, the proxy continues to read JSON 2.0 and convert. (User decision:
  bypass is risky; conversion-only.) This can be a future enhancement.
- No data / availability / metadata-message endpoints — structures only.
- No changes to the JSON 2.0 or XML 2.1 output paths.
- No new sdmx-core fork changes; the usage gap is closed in proxy code, not by
  modifying the bean model or the v3 writer (consistent with the chosen
  post-conversion injection approach).
- **No `metadataAttributeUsages` on fan-out (`agencyID=*`) responses.** The fan-out
  path (`AdapterRouterImpl.getStructuresWithFanOut`, line 280) converts the *merged
  `SdmxBeans`* directly via `convert(beans, buffer, contentType)` — it never reads
  the raw upstream JSON, so there is nothing to capture/inject. This is not a
  regression: usages are already lost in each leg's JSON-2.0 -> bean read (the reader
  discards them; no bean slot), *before* the merge — so the existing JSON fan-out
  path drops them today too. Recovering usages on fan-out would require a different
  mechanism (per-leg raw capture keyed across the merge) and is out of scope.

## Design

### 1. Enable the XML 3.0 writer arm

`sdmx-proxy/.../services/adapter/conversion/StreamingStructureConversionService.java`,
`writeAsXml()` (currently lines 131-143). Replace the `SDMX_3_0` throw with a real
writer, mirroring the existing `SDMX_2_1` arm:

```java
private void writeAsXml(SdmxBeans sdmxBeans, OutputStream outputStream, MediaType targetMediaType) {
    switch (SdmxMediaTypeResolver.extractSdmxVersion(targetMediaType.toString())) {
        case SDMX_2_1 -> {
            SdmxStructureFormat xmlV21 = new SdmxStructureFormat(STRUCTURE_OUTPUT_FORMAT.SDMX_V21_STRUCTURE_DOCUMENT);
            sdmxMLStructureWriterFactory.getStructureWriterEngine(xmlV21).writeStructures(sdmxBeans, null, outputStream);
        }
        case SDMX_3_0 -> {
            SdmxStructureFormat xmlV30 = new SdmxStructureFormat(STRUCTURE_OUTPUT_FORMAT.SDMX_V3_STRUCTURE_DOCUMENT);
            sdmxMLStructureWriterFactory.getStructureWriterEngine(xmlV30).writeStructures(sdmxBeans, null, outputStream);
        }
        default -> throw new UnsupportedConversionException("Unsupported SDMX version for XML conversion");
    }
}
```

`CustomSdmxMLStructureWriterFactory.getStructureWriterEngine()` already routes
`SDMX_V3_STRUCTURE_DOCUMENT` to `StaxStructureWriterEngineV3.getInstance(prettyPrint)`
(verified) — no factory change needed. `STRUCTURE_OUTPUT_FORMAT` is already imported.

### 2. New service: `MetadataAttributeUsageXmlInjector`

New file:
`sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/fixture/structure/MetadataAttributeUsageXmlInjector.java`

Mirrors `MetadataAttributeUsagePreserver` (JSON capture/inject) but injects native
`<structure:MetadataAttributeUsage>` elements into the converted XML 3.0 bytes.
**Reuses `MetadataAttributeUsagePreserver.capture(rawJson)`** — the captured map is
keyed identically by `agencyID|id|version` and holds the raw JSON `metadataAttributeUsages`
array per DSD, which is exactly what this injector needs. No second capture path.

Responsibilities:
- Parse converted XML 3.0 with a **namespace-aware** `DocumentBuilder`.
- For each `DataStructure` element (namespace `http://www.sdmx.org/resources/sdmxml/schemas/v3_0/structure`,
  localName `DataStructure`), read `agencyID` / `id` / `version` attributes; build the
  key and look up captured usages.
- Read the converted `DataStructure`'s `version` attribute for the key. The v3 writer
  emits `version` for normal (non-fixed-version) maintainables and round-trips the
  JSON value verbatim (e.g. `8.0.0`). If a `DataStructure` carries no `version`
  attribute (fixed-version artefact), the key won't match and that DSD is skipped —
  an acceptable no-op (usages simply absent, the best-effort fallback).
- Locate (or create — see next bullet) the `AttributeList` child; for each captured
  usage build a `MetadataAttributeUsage` element, reusing the `AttributeList`'s own
  namespace prefix so output is consistent regardless of whether the writer emits
  `str:` or `structure:`:
  - `urn` attribute synthesized from the DSD coordinates + `metadataAttributeReference`.
  - child `MetadataAttributeReference` (text = the JSON `metadataAttributeReference`).
  - `AttributeRelationship` per the encoding map above — **always emitted** (XSD-required).
    A usage whose JSON carries no `attributeRelationship` falls back to `<Dataflow/>`
    (same as `none`).
- Insert all usage elements **before the first `Attribute` child** of the
  `AttributeList`, but **after** any leading `common:Annotations` / `common:Link`
  (`AttributeListBaseType` sequences those first). Usages-before-attributes matches
  IMF and is schema-valid given the unbounded `Attribute | MetadataAttributeUsage`
  choice.
- **Usage-only DSD (no regular attributes):** the v3 writer omits the `<AttributeList>`
  element entirely in this case (`StaxDsdWriterEngineV3:140-142` early-returns when the
  bean's attribute list is empty). The injector must then **create** `<AttributeList
  id="AttributeDescriptor">` and insert it into `<DataStructureComponents>` after the
  last `Group` (or after `DimensionList` when there are no groups) and before
  `MeasureList`, per `DataStructureComponentsType` order
  (`DimensionList, Group*, AttributeList?, MeasureList?`). In practice every live IMF
  DSD also has real attributes, so this branch is exercised mainly by a synthetic unit
  test, not live data.
- Re-serialize and return. **Best-effort**: on any parse/transform failure, log a
  warning and return the input bytes unchanged (usages then simply absent — same as
  pre-feature behaviour). No exception escapes.

Method shape:

```java
public byte[] inject(byte[] convertedXml, Map<String, JsonNode> usagesByKey) { ... }
```

Implementation notes:
- Use `javax.xml.parsers.DocumentBuilderFactory` with `setNamespaceAware(true)` and
  XXE-hardening (`disallow-doctype-decl`), `javax.xml.transform.Transformer` for
  output. These are JDK-only; no new dependency.
- The `none` (and `dataflow`) case writes `<structure:AttributeRelationship><structure:Dataflow/></structure:AttributeRelationship>` (see map).
- **Serialization drift:** the v3 writer is compact (single-arg `SdmxStructureFormat`
  ctor sets `prettyPrint=false`), so configure the `Transformer` to avoid drift:
  `OMIT_XML_DECLARATION=no`, `ENCODING=UTF-8`, no indentation. Only usage-bearing
  responses (marker-enabled, IMF-like) pass through this DOM round-trip; the common
  path is untouched. A unit test asserts the XML declaration/encoding survive.

### 3. Wire the injector into `AdapterRouterImpl`

`sdmx-proxy/.../services/adapter/AdapterRouterImpl.java`,
`getStructuresConversion()` (lines 171-221). Add the new service as a constructor
field (alongside `metadataAttributeUsagePreserver` / `metadataAttributeUsageFolder`,
lines 81-82) and a third gating flag parallel to `preserveUsages` / `foldUsages`:

```java
boolean preserveUsages    = markerEnabled && SdmxMediaTypeResolver.isJson(requestedMediaType);
boolean foldUsages        = markerEnabled && SdmxMediaTypeResolver.isXmlV21(requestedMediaType) && returnFormat == SdmxFormat.JSON_STRUCTURE_2_0_0;
boolean injectUsagesXml30 = markerEnabled && SdmxMediaTypeResolver.isXmlV30(requestedMediaType) && returnFormat == SdmxFormat.JSON_STRUCTURE_2_0_0;
```

The three flags are mutually exclusive by output type (JSON / XML 2.1 / XML 3.0).
Extend the capture branch so XML 3.0 also captures from the raw bytes:

```java
Map<String, JsonNode> capturedUsages = Map.of();
InputStream forFixtures;
if (preserveUsages || injectUsagesXml30) {
    byte[] rawBytes = rawStream.readAllBytes();
    capturedUsages = metadataAttributeUsagePreserver.capture(rawBytes);
    forFixtures = new ByteArrayInputStream(rawBytes);
} else if (foldUsages) {
    forFixtures = new ByteArrayInputStream(metadataAttributeUsageFolder.foldForXml21(rawStream.readAllBytes(), query));
} else {
    forFixtures = rawStream;
}
```

And the post-conversion injection (after the existing JSON `preserveUsages` inject):

```java
byte[] convertedBytes = buffer.toByteArray();
if (preserveUsages && !capturedUsages.isEmpty()) {
    convertedBytes = metadataAttributeUsagePreserver.inject(convertedBytes, capturedUsages);
} else if (injectUsagesXml30 && !capturedUsages.isEmpty()) {
    convertedBytes = metadataAttributeUsageXmlInjector.inject(convertedBytes, capturedUsages);
}
outputStream.write(convertedBytes);
cacheService.putReadyResponse(responseKey, convertedBytes);
```

The injected result is what gets cached — consistent with the JSON path.

### 4. `SdmxMediaTypeResolver.isXmlV30` helper

`sdmx-proxy/.../common/data/SdmxMediaTypeResolver.java`. Add next to `isXmlV21`
(lines 70-75), same shape:

```java
/**
 * True when {@code mediaType} is an SDMX-ML XML media type carrying {@code version=3.0.0}
 * (e.g. {@code application/vnd.sdmx.structure+xml;version=3.0.0}). Null-safe.
 */
public static boolean isXmlV30(MediaType mediaType) {
    return mediaType != null
            && mediaType.getSubtype() != null
            && mediaType.getSubtype().toLowerCase().contains("xml")
            && "3.0.0".equals(mediaType.getParameter("version"));
}
```

### 5. Accept XML 3.0 at the controller

`sdmx-proxy/.../api/SdmxStructure30Api.java`. Add `STRUCTURE_XML_3_0_0` to the
`produces` list (lines 78-83) and a matching `@Content` in the 200 `@ApiResponse`
(lines 55-64) for OpenAPI accuracy:

```java
produces = {
        SdmxMediaTypes.STRUCTURE_XML_2_1,
        SdmxMediaTypes.STRUCTURE_XML_3_0_0,   // added
        SdmxMediaTypes.STRUCTURE_JSON_2_0_0,
        SdmxMediaTypeResolver.ANY,
        MediaType.APPLICATION_JSON_VALUE
}
```

No `SdmxMediaTypes` constant needs adding — `STRUCTURE_XML_3_0_0` already exists
(line 30). `SdmxFormat.XML_STRUCTURE_3_0_0` already exists (line 43).

## Files affected

| File | New/Mod | Change |
|---|---|---|
| `services/fixture/structure/MetadataAttributeUsageXmlInjector.java` | New | DOM-based injection of native `MetadataAttributeUsage` into converted XML 3.0 |
| `services/adapter/conversion/StreamingStructureConversionService.java` | Mod | Implement `SDMX_3_0` arm in `writeAsXml()` |
| `services/adapter/AdapterRouterImpl.java` | Mod | Inject new service; `injectUsagesXml30` flag + capture + post-convert inject |
| `common/data/SdmxMediaTypeResolver.java` | Mod | Add `isXmlV30(MediaType)` |
| `api/SdmxStructure30Api.java` | Mod | Add `STRUCTURE_XML_3_0_0` to `produces` + 200 `@Content` |

### No changes required (verified)

- `SdmxFormat`, `SdmxMediaTypes` — `XML_STRUCTURE_3_0_0` / `STRUCTURE_XML_3_0_0`
  already present (035).
- `CustomSdmxMLStructureWriterFactory` — already routes `SDMX_V3_STRUCTURE_DOCUMENT`.
- `StructureMapperImpl` and the 033 metadata mappers — JSON-only path; untouched.
- `CustomSdmxJsonDataStructureReaderEngineV2` — keeps skipping usages (the skip is
  intentional; the injector works from the raw JSON capture, not the bean). The
  DSD-level `setMSDReference` it already does is what makes `<str:Metadata>` survive.
- `MetadataAttributeUsagePreserver` / `MetadataAttributeUsageFolder` — reused /
  untouched. (`capture()` is reused by the new injector.)
- `SdmxStructure30Controller` — delegates to `AdapterRouter`; no change.
- `QueryTranslatorImpl` — already resolves XML 3.0 Accept to `SdmxVersion.SDMX_3_0`
  and picks the registry's JSON 2.0 default return format; no change.

## Edge cases

| Case | Expected behaviour |
|---|---|
| BIS (no usages) requested as XML 3.0 | Full XML 3.0 doc; injector finds no captured usages, no-op. |
| IMF DSD with 25 usages, XML 3.0 | All 25 emitted as `MetadataAttributeUsage`, before regular attributes, with correct relationships. |
| Registry without the `PRESERVE_METADATA_ATTRIBUTE_USAGES` fixture | `markerEnabled=false`; injector never runs (usages dropped, as today for XML). |
| `none` relationship | Kept as `<structure:AttributeRelationship><structure:Dataflow/></structure:AttributeRelationship>` (dataset/DSD-level; deliberate divergence from IMF, which omits it). |
| Malformed converted XML / DOM failure | Best-effort: warn + return unchanged bytes. |
| DSD whose `AttributeList` has only usages (no real attributes) | v3 writer omits `<AttributeList>` entirely (`StaxDsdWriterEngineV3:140-142`); injector **creates** it inside `<DataStructureComponents>` (after `Group`, before `MeasureList`). Synthetic unit test (live IMF DSDs always also have real attributes). |
| Fan-out (`agencyID=*`) requested as XML 3.0 | No `MetadataAttributeUsage` (usages lost in per-leg bean round-trip before merge; same as today's JSON fan-out). Documented in Non-goals. |
| Unversioned `Accept: application/xml` | Returns XML 3.0 (intended — `extractSdmxVersion` defaults unversioned SDMX XML to 3.0). See Open questions #2. |
| `DataStructure` without a `version` attribute (fixed-version) | Key won't match; that DSD's usages are skipped (best-effort no-op). |
| Multiple DSDs in one response (`references=descendants`) | Keyed per `agencyID|id|version`; each matched independently. |

## Test plan

### Unit tests

`StreamingStructureConversionServiceTest` (extend existing):
- `shouldConvertStructures_Json20_to_Xml30_dataflowWithDescendants` — JSON 2.0 in,
  assert root `message:Structure` v3.0 namespaces, codelists/DSD/dataflow present.
- `shouldConvertStructures_Json20_to_Xml30_msd` — MSD present in output
  `<structure:MetadataStructures>`.
- `shouldConvertStructures_Json20_to_Xml30_dsdMetadataReference` — DSD-level
  `<structure:Metadata>` MSD URN present.
- `shouldConvertStructures_Json20_to_Xml30_metadataflow` — Metadataflow + its
  `<structure:Structure>` ref present.

New `MetadataAttributeUsageXmlInjectorTest`:
- `injectsNativeUsages_dimensionsRelationship` — `dimensions` -> `Dimension` list.
- `injectsNativeUsages_observationRelationship` — `observation` -> `<Observation/>`.
- `injectsNativeUsages_noneRelationship_emitsDataflow` — `none` -> `<Dataflow/>` (kept, not dropped).
- `synthesizesUrnFromDsdCoordinates`.
- `placesUsagesBeforeRegularAttributes`.
- `noCapturedUsages_returnsInputUnchanged`.
- `malformedXml_returnsInputUnchanged`.
- `multipleDsds_matchedByKey`.
- Fixture: reuse `qnea_dsd_with_msd.json` (added in 032) as the raw JSON source.

`AdapterRouterImplTest` — assert `injectUsagesXml30` path is selected for XML 3.0 +
JSON 2.0 return format + marker enabled, and the injector is invoked.

### E2E (per memory: cross-registry pins in `BaseRegistryTestSuite`, gated by a toggleable config block)

- Add an XML 3.0 structure return-format block to the existing structure /
  DSD-fidelity test configs for IMF 3.0 and BIS 3.0 (`*_test_config.json`), driving
  `BaseRegistryTestSuite` to request `application/vnd.sdmx.structure+xml;version=3.0.0`.
- Assertions (consistent with current E2E depth): HTTP 200, non-empty body,
  content-type echoes XML 3.0, body parses as XML with root `message:Structure` in
  the v3.0 namespace. For IMF DSD: assert presence of `MetadataAttributeUsage`
  element(s). For the MSD-descendants pin: assert `MetadataStructures` present.
- Run via the `e2e-report` skill (boot proxy with
  `SDMXPROXY_TEST_CONFIG_ENDPOINT_ENABLED=true`, then `:sdmx-proxy-e2e:e2eTest`).

### Manual verification

```bash
# IMF — expect MetadataAttributeUsage elements + DSD-level <structure:Metadata>
curl -H "Accept: application/vnd.sdmx.structure+xml;version=3.0.0" \
  "http://localhost:8050/structure/dataflow/IMF.STA/ANEA/6.0.1?references=descendants&detail=full"

# BIS — expect valid XML 3.0, no usages
curl -H "Accept: application/vnd.sdmx.structure+xml;version=3.0.0" \
  "http://localhost:8050/structure/datastructure/BIS/BIS_CBS?references=descendants&detail=full"
```

The registry's own XML 3.0 output (from these same URLs, fetched directly without
the proxy) is the fidelity reference to diff against.

## Open questions / deferred

1. **`none` relationship — RESOLVED.** Keep dataset/DSD-level usages, encoding
   `none` as `<structure:Dataflow/>` (schema-valid, consistent with reader/writer
   handling of dataset-level attributes). Deliberate divergence from IMF, which
   omits the relationship. See the relationship-map note.
2. **Unversioned `application/xml` — RESOLVED.** Generic `application/xml` returns
   XML 3.0 structure output (user decision). `extractSdmxVersion` already defaults
   unversioned SDMX XML to 3.0; enabling the v3 writer turns the former
   `UnsupportedConversionException` into a valid XML 3.0 document. Intended behaviour,
   consistent with the proxy's unified-SDMX-3.0 identity. Documented in Edge cases.
3. **Native XML 3.0 read / bypass** — deferred (Non-goals).
4. **Fan-out usages** — deferred (Non-goals): wildcard-agency XML 3.0 will not carry
   `MetadataAttributeUsage`, consistent with today's JSON fan-out behaviour.

## Review Log

### Iteration 1 — 2026-06-10

Two critics run in parallel (general-purpose), findings deduped, each verified
against real code / sdmx-core source / the v3 XSD before recording. **No Critical
findings.** All cited file:line claims in the design were independently confirmed
accurate (writer arm, factory routing, reader skip/MSDRef, AdapterRouter branch,
resolver, controller, config constants, XSD content models, cache key, translator).

**Critic findings (verified):**

1. **[High] §3 wiring / `AdapterRouterImpl.java:280`** — The fan-out path
   (`getStructuresWithFanOut`) converts merged `SdmxBeans` directly and never calls
   capture/inject, so wildcard-agency XML 3.0 drops usages; the design was silent on it.
   - Evidence: line 280 `convert(merged, buffer, contentType)`; usages are already
     discarded per-leg in the JSON->bean read (`CustomSdmxJsonDataStructureReaderEngineV2:257-260`)
     before the merge — so JSON fan-out drops them today too (pre-existing, not a regression).
   - Resolution: **Accepted (scope-out)** — added a Non-goals bullet, an Edge-cases row,
     and Open-questions #4 documenting that fan-out XML 3.0 carries no usages, consistent
     with current JSON fan-out. Recovering them is out of scope.

2. **[Medium] §2 / `StaxDsdWriterEngineV3.java:140-142`** — A DSD with only usages and
   no regular attributes gets **no `<AttributeList>` element at all** (writer early-returns
   on empty attribute list), so the injector has nothing to find; the design understated
   this as a mere test note.
   - Evidence: `if(attrList == null || attrList.getAttributes()...size() == 0) { return; }`.
     `DataStructureComponentsType` order is `DimensionList, Group*, AttributeList?, MeasureList?`.
   - Resolution: **Accepted** — promoted to a first-class implementation requirement in §2
     (create `AttributeList`, insert after `Group`/before `MeasureList`) and rewrote the
     Edge-cases row; noted live IMF DSDs always also have real attributes (synthetic test).

3. **[Medium] Open questions #2 / `SdmxMediaTypeResolver.java:166-241`** — Enabling the v3
   arm changes generic `application/xml` from throwing to returning XML 3.0; left unresolved.
   - Evidence: `extractSdmxVersion` returns `SDMX_3_0` for unversioned generic XML (lines 180-188,
     239-240); `writeAsXml` switches on it.
   - Resolution: **Accepted (user decision)** — return XML 3.0 (intended). Resolved Open
     questions #2 and added an Edge-cases row.

4. **[Low] §2, ground-truth caption / `SDMXStructureDataStructure.xsd:254-274`** —
   `AttributeRelationship` is XSD-**required** in `MetadataAttributeUsageType`, but the design
   called it "optional".
   - Evidence: `<xs:sequence>` of `MetadataAttributeReference` then `AttributeRelationship`,
     no `minOccurs="0"`.
   - Resolution: **Accepted** — reworded to "(XSD-required)"; specified the injector **always**
     emits a relationship, with a `<Dataflow/>` fallback when the JSON has no `attributeRelationship`.

5. **[Low] §2 / `javax.xml.transform.Transformer`** — DOM re-serialization could alter the XML
   declaration/whitespace for usage-bearing responses.
   - Evidence: v3 writer is compact (`SdmxStructureFormat` single-arg ctor `prettyPrint=false`).
   - Resolution: **Accepted** — added a "serialization drift" note (Transformer config:
     declaration on, UTF-8, no indent) + a unit-test assertion; only IMF-like responses round-trip.

6. **[Low] §2 / `SDMXCommonReferences.xsd` (`MetadataAttributeUrnType`)** — synthesized `urn` is
   keyed on DSD (not MSD) coordinates; semantically the attribute belongs to the MSD.
   - Evidence: urn type validates by prefix only; IMF's baseline urn uses DSD coordinates.
   - Resolution: **Accepted** — noted the urn is cosmetic / IMF-baseline-matching (plain DSD
     version), not a resolvable reference.

7. **[Low] §2 / `StaxMaintainableWriterUtilV3` + `MetadataAttributeUsagePreserver:131-137`** —
   the key relies on the converted `version` attribute being present and matching the JSON value.
   - Evidence: writer emits `version` only for non-fixed-version maintainables, verbatim; the
     version-touching `VersionWildcardJsonFixture` rewrites only URN-parenthesized versions, not
     the top-level field.
   - Resolution: **Accepted** — added a note + Edge-cases row: a `DataStructure` without a
     `version` attribute is a best-effort no-op.

8. **[Low] snippets / `SDMX_NAMESPACE.STRUCTURE_3` = `str`** — the example XML uses `structure:`
   but the writer emits `str:`.
   - Evidence: existing XML 2.1 test asserts on `str:` elements.
   - Resolution: **Accepted** — added a note that snippets use `structure:` for readability and
     the injector is prefix-agnostic (reuses the live `AttributeList` prefix).

9. **[Low] §Discovery, §Manual verification** — cited recon baselines (`imf-recon/`, `bis-recon/`)
   are local temp dirs, not committed, so not reproducible.
   - Evidence: no `*recon*` paths in the repo.
   - Resolution: **Accepted** — reworded to state captures are not committed; the implementer
     re-fetches via the curl commands or uses the committed `qnea_dsd_with_msd.json` fixture.

**Discarded (unverified):** none — both critics verified their findings against the code, and
the one premise that did not hold (a claim that the DOM round-trip would reformat *all* XML 3.0
responses) was self-corrected by the critic, who confirmed injection is gated to marker-enabled,
usage-bearing responses only.

**Exit condition:** converged — no Critical or High findings survived as unresolved (the single
High was accepted as an explicit scope-out, consistent with existing fan-out behaviour). Stopping
at iteration 1 of 4.

### Post-review correction (user) — 2026-06-10

User flagged that the design's framing implied DSD `metadataAttributeUsages` are "dropped in
JSON 2.0". **Corrected — the user is right.** Verified in code:
`PRESERVE_METADATA_ATTRIBUTE_USAGES` has no `StructureFixture` implementation
(`StructureFixtureService.findFixture` returns null and skips it); it is a **marker** consumed by
`MetadataAttributeUsagePreserver.isEnabled` in `AdapterRouterImpl`, which captures usages from the
raw JSON pre-conversion and re-injects them post-conversion (lines 187-189, 207-209). So **JSON 2.0
output is correct** — usages are not dropped from the response, only from the sdmx-core bean
round-trip (which is why the capture/reinject exists). XML 2.1 compensates via folding (032). Only
XML 3.0 has no equivalent — that is the true, format-specific gap. Reworded the Summary (added a
per-format breakdown) and the per-artefact table verdict accordingly; the design's conclusion and
approach are unchanged.
