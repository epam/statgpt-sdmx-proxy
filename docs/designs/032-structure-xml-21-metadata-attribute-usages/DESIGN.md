# Design 032: Preserve `metadataAttributeUsages` on JSON 2.0 -> XML 2.1 structure conversion

**Status:** draft (2026-06-02). Awaiting review.

## Context

The proxy exposes an SDMX 3.0 facade and converts upstream SDMX-JSON 2.0
structures to whatever the client asks for. IMF declares
`metadataAttributeUsages` on its DSDs (e.g. `IMF.STA:DSD_QNEA(7.0.0)` has 25:
`DOI`, `FULL_DESCRIPTION`, `AUTHOR`, ...). These are an SDMX **3.0** construct:
a DSD-side declaration that data conforming to the DSD MAY carry a
reference-metadata report for a metadata attribute defined in an MSD.

When a client requests `Accept: application/vnd.sdmx.structure+xml;version=2.1`,
the proxy converts JSON 2.0 -> XML 2.1. SDMX-ML 2.1 has **no**
`MetadataAttributeUsage` element (it exists only in the 3.0 schema,
`sdmx-core-2.3.9/fusion-sdmx-ml/src/main/resources/xsd/3_0/SDMXStructureDataStructure.xsd`).
IMF's own 2.1 endpoint resolves this by **folding each usage into the
`AttributeList` as a regular `DataAttribute`**. The proxy does not: it drops
them entirely.

Investigation record (queries run, raw evidence, the presence table):
`docs/discussions/metadata-attribute-usages-2.1-xml.md`.

Prior art:
- `docs/designs/027-remove-metadata-attribute-usage-to-attribute/DESIGN.md` --
  removed `MetadataAttributeUsageToAttributeJsonFixture`, which did exactly the
  fold-into-attributes rewrite this design revives. 027 removed it because it
  ran **unconditionally** (forging phantom data attributes on the *data*
  endpoint, and being redundant for JSON *structure* output). 027 never
  considered XML 2.1 *structure* output, which is the one case where the fold
  is correct.
- `docs/designs/031-...` / `MetadataAttributesPreserver` -- the data-endpoint
  analog, JSON-to-JSON only.

## Problem

### Symptom (production log, 2026-05-29)

Every structure request for an IMF DSD with `Accept: ...+xml;version=2.1`
produces a swallowed exception per DSD:

```
WARN  MetadataAttributeUsagePreserver: Failed to re-inject metadataAttributeUsages into converted JSON; returning unchanged output
com.fasterxml.jackson.core.JsonParseException: Unexpected character ('<' (code 60)): expected a valid value ...
    at com.epam.sdmxproxy.services.fixture.structure.MetadataAttributeUsagePreserver.inject(MetadataAttributeUsagePreserver.java:92)
    at com.epam.sdmxproxy.services.adapter.AdapterRouterImpl.lambda$getStructuresConversion$0(AdapterRouterImpl.java:198)
```

17 occurrences in the captured window; 0 successful restores; all 92 conversions
in the session targeted XML 2.1.

### Root cause

`AdapterRouterImpl.getStructuresConversion` (`AdapterRouterImpl.java:168-211`)
runs the JSON-only `MetadataAttributeUsagePreserver` whenever the
`PRESERVE_METADATA_ATTRIBUTE_USAGES` marker is configured, **without checking
the output format**:

```java
boolean preserveUsages = metadataAttributeUsagePreserver.isEnabled(fixtures); // ignores output format
...
if (preserveUsages && !capturedUsages.isEmpty()) {
    convertedBytes = metadataAttributeUsagePreserver.inject(convertedBytes, capturedUsages); // readTree() on XML -> throws
}
```

`inject()` calls `objectMapper.readTree(convertedJson)` (`MetadataAttributeUsagePreserver.java:92`);
on XML output the bytes start with `<` and Jackson throws. The class Javadoc
already states "JSON-to-JSON path only. XML output is left untouched" -- the
call site does not honor it.

### Two distinct defects

1. **Noise / wasted work** -- the JSON preserver should never run for XML
   output.
2. **Data loss (the real gap)** -- there is no mechanism that carries
   `metadataAttributeUsages` into XML 2.1 output at all. Empirically:

   | Representation | metadata present? |
   |----------------|-------------------|
   | IMF native 3.0 JSON | yes (25, as `metadataAttributeUsages`) |
   | IMF native 3.0 XML | yes (25, as `<str:MetadataAttributeUsage>`) |
   | IMF native **2.1** XML | yes (25, folded into `AttributeList` -> 18+25 = 43 `<str:Attribute>`) |
   | **proxy** JSON 2.0 -> XML 2.1 | **no** (only the 18 real attributes survive) |

## Current Architecture

```
GET /structure/dataflow/IMF.STA/QNEA/7.0.0?references=datastructure
  Accept: application/vnd.sdmx.structure+xml;version=2.1
        |
  QueryTranslatorImpl.translateStructureQuery
        |  references passed through verbatim (line 191) -> "datastructure"
        |  registryReturnFormat = JSON_STRUCTURE_2_0_0, contentType = xml 2.1
        v
  AdapterRouterImpl.getStructures -> getStructuresConversion
        |  rawStream = genericRegistryAdapter.getStructures(query)   (IMF JSON 2.0; DSD only, no MSD)
        |  [preserveUsages] capture metadataAttributeUsages from raw JSON
        |  fixtureService.applyFixtures(raw, JSON_STRUCTURE_2_0_0, fixtures)  -- chain, keyed by SOURCE format
        |  streamingStructureConversionService.convert(.., XML 2.1)          -- sdmx-core 2.1 writer drops usages
        |  [preserveUsages] inject(convertedXmlBytes, captured)  <-- readTree('<...') THROWS, swallowed
        v
  XML 2.1 with the 18 real attributes only
```

Key facts established during research:

- `TranslatedStructureQuery` (`sdmx-proxy/src/main/java/com/epam/sdmxproxy/common/data/TranslatedStructureQuery.java`)
  carries both `contentType` (client output media type) and
  `registryReturnFormat` (source format) -- the full gating condition is
  expressible there.
- The DSD JSON carries a `metadata` URN linking it to its MSD:
  `"metadata":"urn:sdmx:org.sdmx.infomodel.metadatastructure.MetadataStructure=IMF:MSD_REF_IMF_DATASET(2.0+.0)"`.
- The MSD can be fetched standalone (7 KB):
  `GET /structure/metadatastructure/IMF/MSD_REF_IMF_DATASET/+?detail=full`
  returns `data.metadataStructures[0].metadataStructureComponents.metadataAttributeList.metadataAttributes[]`,
  each with `id`, `conceptIdentity` (a Concept URN), and `minOccurs`.
- `references=datastructure` does NOT pull the MSD; `references=descendants`
  does, but also drags 54 codelists + 7 concept schemes (3.2 MB). IMF's native
  2.1 DSD response contains only the DSD, so we must not leak those.
- The data path already demonstrates the exact gating shape we need
  (`AdapterRouterImpl.java:347-349`):
  `isEnabled(...) && returnFormat == JSON_DATA_2_0_0 && isJson20Output(requestedMediaType)`.

## Design decisions (resolved with reviewer)

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

## Solution

Split the single `PRESERVE_METADATA_ATTRIBUTE_USAGES` marker into two
output-format-gated mechanisms, orchestrated in `AdapterRouterImpl`:

- **JSON output** -> existing `MetadataAttributeUsagePreserver` capture/inject
  (now gated to JSON output, which also fixes the WARN bug).
- **XML 2.1 output** -> revive the deleted `MetadataAttributeUsageToAttributeJsonFixture`
  (fold usages into attributes, `conceptIdentity` from the MSD). The MSD is
  obtained by a side-fetch in `AdapterRouterImpl` and spliced into the raw JSON
  before the fixture chain runs; the fixture consumes and removes the spliced
  `metadataStructures[]` so it never reaches the converter.

The fold logic is recovered verbatim from git
(`git show f5c98e4^:sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/fixture/structure/MetadataAttributeUsageToAttributeJsonFixture.java`),
which is already validated against IMF data; the only change is gating.

## Implementation Plan

### Step 1: Stop the JSON preserver from running on XML output (bug fix)

**File:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/adapter/AdapterRouterImpl.java`

In `getStructuresConversion` (line ~175), gate `preserveUsages` on JSON output,
mirroring the data path's `isJson20Output` check (line 396). Add an
`isXml21Output` helper alongside it.

```java
boolean jsonOutput = isJsonOutput(requestedMediaType);
boolean preserveUsages = jsonOutput
        && metadataAttributeUsagePreserver.isEnabled(fixtures);
```

```java
private static boolean isJsonOutput(MediaType mediaType) {
    return mediaType != null && mediaType.getSubtype().toLowerCase().contains("json");
}

private static boolean isXml21Output(MediaType mediaType) {
    if (mediaType == null || !mediaType.getSubtype().toLowerCase().contains("xml")) {
        return false;
    }
    String version = mediaType.getParameter("version");
    return version != null && version.startsWith("2.1");
}
```

**Key points:**
- This alone removes the 17 WARNs. Ship-able independently of Steps 2-5.
- `isJson20Output` (line 396) is data-specific (requires version 2.0). Structure
  JSON output can be 2.0 only today, but use `isJsonOutput` (no version pin) to
  match the preserver's actual capability and avoid coupling.

### Step 2: Make the structure fixture chain output-format aware

**File:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/fixture/structure/StructureFixture.java`

Add a default-true predicate so a fixture can opt into specific client outputs.
Default keeps all existing fixtures unchanged.

```java
import org.springframework.http.MediaType;

public interface StructureFixture {

    StructureFixtureType getType();

    Set<ReturnFormat> supportedFormats();

    /**
     * Whether this fixture applies for the given client output media type.
     * Default true: most fixtures patch the source representation and are
     * agnostic to the eventual output format. Override when a fixture is only
     * correct for a specific output (e.g. fold-to-attribute only for XML 2.1).
     */
    default boolean appliesToOutput(MediaType clientOutput) {
        return true;
    }

    InputStream apply(InputStream input, Map<String, String> config);
}
```

**File:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/fixture/structure/StructureFixtureService.java`

Thread the client output media type into selection.

```java
public InputStream applyFixtures(
        InputStream input,
        ReturnFormat format,
        MediaType clientOutput,
        List<FixtureConfiguration<StructureFixtureType>> fixtureConfigs
) {
    if (fixtureConfigs == null || fixtureConfigs.isEmpty()) {
        return input;
    }
    InputStream current = input;
    for (FixtureConfiguration<StructureFixtureType> fc : fixtureConfigs) {
        StructureFixture fixture = findFixture(fc.getType(), format, clientOutput);
        if (fixture != null) {
            log.debug("Applying fixture {} for format {} -> output {}", fc.getType(), format, clientOutput);
            current = fixture.apply(current, fc.getConfig());
        } else {
            log.debug("No fixture impl for type {}, format {}, output {}, skipping", fc.getType(), format, clientOutput);
        }
    }
    return current;
}

private StructureFixture findFixture(StructureFixtureType type, ReturnFormat format, MediaType clientOutput) {
    return fixtures.stream()
            .filter(f -> f.getType() == type
                    && f.supportedFormats().contains(format)
                    && f.appliesToOutput(clientOutput))
            .findFirst()
            .orElse(null);
}
```

**Key points:**
- Update the two existing callers in `AdapterRouterImpl`
  (`getFixedStructureStream` line ~160 and `getStructuresConversion` line ~187)
  to pass `query.getContentType()`.
- Note `getFixedStructureStream` is also reached from data/availability
  pre-fetch via `getSdmxBeans` -> `getStructureBytes` -> `getFixedStructureStream`.
  Those pass through `getStructureQuery(...)` whose `contentType` is null
  (Accept null in `translateStructureQuery`). `appliesToOutput(null)` must
  therefore return **false** for the fold fixture so it never fires on the
  data/availability DSD pre-fetch (which is JSON-bean-only and must not get
  phantom attributes -- exactly the 027 regression). `isXml21Output(null)`
  returns false, so this is satisfied.

### Step 3: Revive the fold fixture, gated to XML 2.1 output

**File (New):** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/fixture/structure/MetadataAttributeUsageToAttributeJsonFixture.java`

Restore the deleted class verbatim (recover with
`git show f5c98e4^:.../MetadataAttributeUsageToAttributeJsonFixture.java`) with
two changes: reuse the existing marker, and gate to XML 2.1 output. The body
(`buildMetadataStructureIndex`, `convertUsages`, `resolveMsd`,
`buildAttribute`, `URN_AGENCY_ID_PATTERN`) is unchanged and already validated
against IMF -- the synthesized attribute matches a real JSON 2.0 attribute
(`annotations`, `id`, `conceptIdentity` from MSD, `isMandatory` from
`minOccurs`, `conceptRoles: []`, `attributeRelationship` copied from the usage).

```java
@Override
public StructureFixtureType getType() {
    return StructureFixtureType.PRESERVE_METADATA_ATTRIBUTE_USAGES; // reuse marker (was METADATA_ATTRIBUTE_USAGE_TO_ATTRIBUTE)
}

@Override
public Set<ReturnFormat> supportedFormats() {
    return Set.of(ReturnFormat.JSON_STRUCTURE_2_0_0); // source format
}

@Override
public boolean appliesToOutput(MediaType clientOutput) {
    return isXml21(clientOutput); // fold is correct ONLY for XML 2.1 output
}
```

(`isXml21` = same logic as `AdapterRouterImpl.isXml21Output`; factor into a
shared util or duplicate the 3-line check.)

**Key points:**
- `convertUsages` is a no-op when `data.metadataStructures` is absent (it reads
  the index, finds nothing, skips every DSD). So if the side-fetch in Step 4
  fails or finds no MSD, the fixture safely leaves the DSD unchanged (usages
  dropped, same as today) instead of erroring.
- The fixture removes `metadataAttributeUsages` after folding, so the converter
  never sees the 3.0-only field.

### Step 4: Side-fetch + splice the MSD (AdapterRouter orchestration)

**File:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/adapter/AdapterRouterImpl.java`

In `getStructuresConversion`, when output is XML 2.1 and the marker is enabled,
fetch the MSD(s) referenced by the DSD(s) in the raw JSON and splice their
`metadataStructures[]` into the raw bytes before `fixtureService.applyFixtures`
runs. Sketch:

```java
boolean foldUsages = isXml21Output(requestedMediaType)
        && metadataAttributeUsagePreserver.isEnabled(fixtures); // same marker

InputStream forFixtures;
if (preserveUsages) {            // JSON output path (Step 1) -- unchanged
    byte[] rawBytes = rawStream.readAllBytes();
    capturedUsages = metadataAttributeUsagePreserver.capture(rawBytes);
    forFixtures = new ByteArrayInputStream(rawBytes);
} else if (foldUsages) {         // XML 2.1 output path (new)
    byte[] rawBytes = rawStream.readAllBytes();
    byte[] withMsd = spliceReferencedMsds(rawBytes, query); // side-fetch + splice
    forFixtures = new ByteArrayInputStream(withMsd);
} else {
    forFixtures = rawStream;
}
```

```java
/**
 * For each DSD in the raw JSON that declares a {@code metadata} URN, side-fetch
 * the referenced MSD and merge it into {@code data.metadataStructures[]} so the
 * fold fixture can resolve conceptIdentity. Best-effort: any failure leaves the
 * raw bytes unchanged (the fixture then drops the usages, same as today).
 */
private byte[] spliceReferencedMsds(byte[] rawJson, TranslatedStructureQuery dsdQuery) {
    try {
        JsonNode root = objectMapper.readTree(rawJson);
        JsonNode dsds = root.path("data").path("dataStructures");
        if (!dsds.isArray()) {
            return rawJson;
        }
        Set<String> urns = new LinkedHashSet<>();
        for (JsonNode dsd : dsds) {
            String urn = dsd.path("metadata").asText("");
            if (!urn.isEmpty()) {
                urns.add(urn);
            }
        }
        if (urns.isEmpty()) {
            return rawJson;
        }
        ArrayNode msds = objectMapper.createArrayNode();
        for (String urn : urns) {
            JsonNode msd = fetchMsd(urn, dsdQuery); // returns data.metadataStructures[0] or null
            if (msd != null) {
                msds.add(msd);
            }
        }
        if (msds.isEmpty()) {
            return rawJson;
        }
        ((ObjectNode) root.path("data")).set("metadataStructures", msds);
        return objectMapper.writeValueAsBytes(root);
    } catch (IOException e) {
        log.warn("Failed to splice MSD into raw structures; usages will be dropped", e);
        return rawJson;
    }
}
```

`fetchMsd` parses the URN (agency / id / version) and issues a structure query
for the metadata structure, returning the first `data.metadataStructures[]`
node from the raw registry JSON:

```java
private JsonNode fetchMsd(String metadataUrn, TranslatedStructureQuery dsdQuery) {
    UrnParts p = parseMaintainableUrn(metadataUrn); // agency, id, version (version may be a wildcard)
    if (p == null) {
        return null;
    }
    TranslatedStructureQuery msdQuery = queryTranslator.translateStructureQuery(
            "metadatastructure", p.agency(), p.id(), p.version(),
            "none", "full",
            SdmxMediaType.STRUCTURE_SDMX_JSON_2_0_0_VALUE, // force JSON 2.0 so we can read conceptIdentity
            null
    );
    try (InputStream in = genericRegistryAdapter.getStructures(msdQuery)) {
        if (in == null) {
            return null;
        }
        JsonNode root = objectMapper.readTree(in.readAllBytes());
        JsonNode arr = root.path("data").path("metadataStructures");
        return (arr.isArray() && !arr.isEmpty()) ? arr.get(0) : null;
    } catch (Exception e) {
        log.warn("MSD side-fetch failed for {}: {}", metadataUrn, e.getMessage());
        return null;
    }
}
```

**Key points:**
- `genericRegistryAdapter`, `queryTranslator`, `objectMapper` are already
  injected into `AdapterRouterImpl`.
- Version from the URN may be a wildcard (`2.0+.0`); requesting it (or `+`)
  resolves to the latest. The IMF registry already has `VERSION_WILDCARD`
  configured. The side-fetch confirmed working: `.../metadatastructure/IMF/MSD_REF_IMF_DATASET/+` -> 200, 7 KB.
- Verify `queryTranslator.translateStructureQuery` accepts structure type
  `"metadatastructure"` (`QueryTranslatorImpl.getStructure`) -- see Open
  Questions. If a dedicated lookup is needed, add `metadatastructure` to the
  supported structure types.
- The fold fixture (Step 3) removes both `metadataAttributeUsages` and the
  spliced `metadataStructures[]` is consumed only for lookup; ensure the fixture
  also strips `data.metadataStructures` after folding so it never reaches the
  converter. (The deleted fixture removed `metadataAttributeUsages` per DSD but
  left `metadataStructures` -- add a final `((ObjectNode) data).remove("metadataStructures")`.)

### Step 5: Cache-key correctness

**File:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/cache/CacheKeyGenerator.java` (verify only)

The ready-response cache key already includes the requested media type
(`generateResponseKey(query, requestedMediaType, ...)`, `AdapterRouterImpl:138`),
so XML 2.1 and JSON 2.0 outputs cache separately. The raw-structures cache key
(`generateStructureKey`) is keyed on the DSD query and is shared; the splice
happens after the raw fetch/cache, so no key change is required. Confirm the
raw-structures cache stores the *unspliced* upstream bytes (it does -- splice is
in the conversion path, not `getStructureBytes`).

## No Changes Required

- **`CustomStaxDsdWriterEngineV21.java`** -- this is a faithful sdmx-core
  monkey-patch. Metadata handling is a fixture-layer concern; the writer must
  stay format-correct and untouched.
- **`StructureFixtureType` enum** (`sdmx-proxy-config/.../StructureFixtureType.java`)
  -- the existing `PRESERVE_METADATA_ATTRIBUTE_USAGES` marker is reused. No new
  value, so `sdmx-proxy-config/README.md` needs no schema-table change.
- **`sdmx_registries_config.json` / `imf_3_0_registry_config.json`** -- IMF
  already lists `PRESERVE_METADATA_ATTRIBUTE_USAGES`. Behavior changes without a
  config edit.
- **`MetadataAttributeUsagePreserver.java`** -- unchanged; only its invocation
  is gated (Step 1).
- **`StreamingStructureConversionService.java`** -- conversion is unchanged; the
  fold happens upstream on the JSON.

## Files Affected

| File | Change Type | Description |
|------|-------------|-------------|
| `services/adapter/AdapterRouterImpl.java` | Modified | Gate JSON preserver to JSON output; add XML-2.1 fold path with MSD side-fetch + splice; `isJsonOutput`/`isXml21Output`/`spliceReferencedMsds`/`fetchMsd` helpers |
| `services/fixture/structure/StructureFixture.java` | Modified | Add `appliesToOutput(MediaType)` default method |
| `services/fixture/structure/StructureFixtureService.java` | Modified | Thread client output media type into fixture selection |
| `services/fixture/structure/MetadataAttributeUsageToAttributeJsonFixture.java` | New (revived) | Fold `metadataAttributeUsages` -> `DataAttribute`s using spliced MSD; gated to XML 2.1 output; reuses `PRESERVE_METADATA_ATTRIBUTE_USAGES` |
| `common/data/SdmxMediaType.java` | Verify | `STRUCTURE_SDMX_JSON_2_0_0_VALUE` exists for the side-fetch Accept (confirmed referenced in tests) |

## Edge Cases

1. **DSD has no `metadata` URN** -- `spliceReferencedMsds` adds nothing; fold
   fixture finds no MSD and skips; usages dropped (same as today). No error.
2. **MSD side-fetch fails (404/timeout)** -- best-effort: raw bytes returned
   unchanged; usages dropped; the main response still succeeds. Logged at WARN.
3. **Usage references an attribute absent from the MSD** -- the fold fixture
   logs WARN and skips that single usage (existing behavior in `convertUsages`).
4. **Multiple DSDs referencing different MSDs** (e.g. `references=datastructure`
   on a dataflow returning one DSD, or fan-out) -- `spliceReferencedMsds`
   collects the distinct `metadata` URNs and side-fetches each.
5. **Data / availability DSD pre-fetch** (`getSdmxBeans(getStructureQuery(...))`)
   -- `contentType` is null there, `appliesToOutput(null)` is false, so the fold
   fixture never fires. This is the explicit guard against the 027 regression.
6. **JSON 3.0 output** (`+json;version=3.0.0`) -- not XML, `foldUsages` false;
   not JSON-2.0-preserver-applicable either (the preserver targets the 2.0
   shape). Usages flow through the normal 3.0 path unchanged. (Confirm the 3.0
   JSON writer emits usages natively; out of scope here.)
7. **Bypass path** -- when format matches and bypass is enabled, no conversion
   occurs and neither mechanism runs. Correct: a registry already speaking the
   client's format needs no fold.

## Verification

### Unit Tests

**File (New):** `sdmx-proxy/src/test/java/com/epam/sdmxproxy/services/fixture/MetadataAttributeUsageToAttributeJsonFixtureTest.java`

Recover the deleted test
(`git show f5c98e4^:sdmx-proxy/src/test/java/com/epam/sdmxproxy/services/fixture/MetadataAttributeUsageToAttributeJsonFixtureTest.java`,
331 lines) and add output-gating cases.

| Test Method | Description |
|-------------|-------------|
| `foldsUsagesIntoAttributesUsingMsd()` | 25 usages + MSD -> 25 extra attributes with conceptIdentity from MSD; `metadataAttributeUsages` removed |
| `skipsWhenMsdAbsent()` | No `metadataStructures[]` -> DSD unchanged |
| `appliesToOutputTrueForXml21()` / `falseForJsonAndNull()` | Gating predicate |
| `copiesAttributeRelationshipVerbatim()` | `none`/`dimensions` relationship preserved on the synthesized attribute |

**File:** `sdmx-proxy/src/test/java/com/epam/sdmxproxy/services/fixture/MetadataAttributeUsagePreserverTest.java`

| Test Method | Description |
|-------------|-------------|
| (existing) | unchanged |

**File:** `sdmx-proxy/src/test/java/com/epam/sdmxproxy/services/adapter/StreamingStructureConversionServiceTest.java`

Add a JSON-2.0 -> XML-2.1 case asserting the converted XML contains
`<str:Attribute id="DOI">` (folded) and no thrown `JsonParseException`. Capture
a DSD fixture for `IMF.STA:DSD_QNEA(7.0.0)` (the JSON from the curl below) plus a
stubbed MSD response.

### Manual Verification

```bash
# Bug fix (Step 1): no WARN, response is XML 2.1
curl -s -H "Accept: application/vnd.sdmx.structure+xml;version=2.1" \
  "http://localhost:8050/.../api/v0/sdmx/3.0/structure/dataflow/IMF.STA/QNEA/7.0.0?references=datastructure" \
  | grep -c "str:Attribute"   # expect 43 after full implementation (18 real + 25 folded), 18 before

# Compare against IMF's own native 2.1 (ground truth):
curl -s -H "Accept: application/vnd.sdmx.structure+xml;version=2.1" \
  "https://api.imf.org/external/sdmx/2.1/datastructure/IMF.STA/DSD_QNEA/7.0.0" \
  | grep -o 'str:Attribute [^>]*id="[^"]*"' | wc -l   # 43
```

Expected after implementation: proxy 2.1 XML contains all 43 attributes
including `DOI`, `AUTHOR`, ... each with a `<str:ConceptIdentity>` `Ref`,
matching IMF's native 2.1.

### E2E Tests

Per project convention (memory: generic, toggleable cross-registry pins in
`BaseRegistryTestSuite`, not registry-specific `@Test`s), add an optional
assertion block: for a DSD that declares `metadataAttributeUsages`, when output
is XML 2.1, assert the `AttributeList` count equals (real attributes + usages)
and that a known metadata attribute id (e.g. `DOI`) appears as `<str:Attribute>`.
Gate it behind a config toggle so registries without metadata usages skip it.
IMF 3.0 (`imf_3_0_registry_config.json`) already has the marker.

## SDMX Standard References

- SDMX-JSON 2.0 structure schema: `AttributeType` vs `MetadataAttributeUsageType`
  -- `sdmx-core-2.3.9/fusion-sdmx-json/src/test/resources/schema/v2-sdmx-json-structure-schema.json`.
- `MetadataAttributeUsage` is 3.0-only:
  `sdmx-core-2.3.9/fusion-sdmx-ml/src/main/resources/xsd/3_0/SDMXStructureDataStructure.xsd`
  (absent from any 2.1 schema).
- Metadata endpoint / metadata vs data attribute semantics:
  `sdmx-rest-2.2.0/doc/metadata.md` (see design 027 for the conflation analysis).

## Implementation notes (as built — supersedes the plan above where they differ)

The fold was implemented as a **dedicated `@Service`** (`MetadataAttributeUsageFolder`) invoked
directly by `AdapterRouterImpl` on the XML 2.1 path, rather than as a chained `StructureFixture` with
an `appliesToOutput` gate. Rationale:

- A `StructureFixture` is a stream-only transform with no registry access; the fold needs to
  side-fetch the MSD, so it never fit that abstraction. Housing the side-fetch + splice + fold in one
  service keeps the registry dependency where it belongs and removes the need to widen the generic
  `StructureFixture`/`StructureFixtureService` contract (no `appliesToOutput` default-true footgun on
  the other fixtures).
- `AdapterRouterImpl` gates by output: `SdmxMediaType.isJson(...)` -> JSON preserver,
  `SdmxMediaType.isXmlV21(...) && returnFormat == JSON_STRUCTURE_2_0_0` -> `folder.foldForXml21(...)`.
  Folding never runs for JSON output, so the design-027 SDMX-PLUS regression cannot recur.
- `MetadataAttributeUsageFolder` builds its MSD index from any `metadataStructures` already in the
  response and side-fetches the rest; it then strips `metadataStructures` so the MSD never reaches the
  converter. URN parsing uses jsdmx `SdmxUrn.getUrnComponents(...)` (not a regex). `SdmxMediaType`
  gained `isJson` / `isXmlV21` helpers.

## Original implementation notes (resolved during build)

- **`metadatastructure` structure-type routing — resolved.** `metadatastructure` is NOT in any
  registry's `supportedStructures`, so routing the side-fetch through
  `QueryTranslatorImpl.translateStructureQuery` would throw `checkStructureTypeIsSupported`.
  Instead `fetchMsd` builds the `TranslatedStructureQuery` directly (reusing the DSD query's
  resolved registry/version config + `registryReturnFormat`), so the MSD fetch stays a purely
  internal raw-JSON call and is never exposed as a client-facing structure type.
  `GenericRegistryAdapterImpl.getStructures` reads only `structure`/`references`/`detail`/format
  off the query and performs no type validation, so the directly-built query works.
- **Verified end-to-end** against live IMF via `bootRun` + the E2E pin
  (`testDsdMetadataUsagesFoldedToAttributesXml21`): `IMF.RES:DSD_WEO(9.0.0)` at XML 2.1 folds DOI /
  AUTHOR / METHODOLOGY / LICENSE into `<str:Attribute>` with no `<str:MetadataStructure>` leak.

## Open Questions / Deferred
- **`assignmentStatus` default.** The fold derives `isMandatory` from
  `minOccurs > 0`; IMF native 2.1 marks these `Conditional`. With `minOccurs: 0`
  the fold produces `isMandatory: false` -> `Conditional` in the writer, which
  matches. Confirm no metadata attribute has `minOccurs > 0` that should still
  be `Conditional`.
- **Non-IMF registries.** Only IMF 3.0 wires the marker today. Other registries
  with JSON 2.0 + metadata usages would benefit automatically once configured;
  no audit done here.
- **`metadataAttributeUsages` annotations.** The fold copies `usage.annotations`
  onto the synthesized attribute (existing behavior). Verify the 2.1 writer
  emits them as expected.
