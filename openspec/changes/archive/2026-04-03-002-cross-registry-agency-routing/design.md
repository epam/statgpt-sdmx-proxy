# Cross-Registry Agency Routing

## Problem

BIS data structures reference artefacts from other agencies (IMF, ESTAT, ISO, SDMX). When the client fetches a BIS DSD
with `references=all` and then requests a referenced `codelist/IMF/CL_AREA/1.15`, the proxy currently routes to IMF
based on the agency ID. But the artefact lives in BIS. We need a routing mechanism that can resolve the correct registry
when the agency ID alone is ambiguous.

## Design

### New config: `agencies[]` array

Replaces `supportedAgencies` on each registry. Moved to the top-level `ProxyConfiguration` so routing is a cross-cutting
concern, not per-registry.

```json
{
  "configs": [
    ...
  ],
  "agencies": [
    {
      "name": "IMF",
      "primaryRegistry": "IMF",
      "secondaryRegistries": [
        "BIS"
      ]
    },
    {
      "name": "BIS",
      "primaryRegistry": "BIS"
    },
    {
      "name": "ESTAT",
      "primaryRegistry": null,
      "secondaryRegistries": [
        "BIS"
      ]
    },
    {
      "name": "ISO",
      "primaryRegistry": null,
      "secondaryRegistries": [
        "BIS",
        "IMF"
      ]
    },
    {
      "name": "SDMX",
      "primaryRegistry": null,
      "secondaryRegistries": [
        "BIS",
        "IMF"
      ]
    }
  ]
}
```

- `primaryRegistry` — the authoritative source for this agency's own artefacts. Nullable (e.g. ISO has no SDMX
  registry).
- `secondaryRegistries` — registries that host copies/references of this agency's artefacts. Checked in order.

### New header: `X-Source-Artefact-Urn`

The client sends this header when fetching a cross-referenced artefact. Value is the URN of the parent artefact that
contained the reference.

Example: client fetched `codelist/IMF/CL_AREA/1.15` but the reference came from a BIS DSD:

```
X-Source-Artefact-Urn: urn:sdmx:org.sdmx.infomodel.datastructure.DataStructureDefinition=BIS:BIS_DER(1.0)
```

### Routing algorithm (`AgencyRoutingService`)

Given `requestedAgency` (from URL path) and optional `sourceArtefactUrn` (from header):

**First path -- source agency routing (only when header is present):**

1. Parse URN -> extract `sourceAgency`
2. Resolve a **candidateRegistry** from `sourceAgency`'s config (try primaryRegistry first, then walk
   secondaryRegistries -- same sub-algorithm as standard routing below)
3. **Cross-check:** collect all registries for `requestedAgency` (primary + all secondaries). If `candidateRegistry` is
   in
   that set -> return it
4. If cross-check fails -> fall through to standard routing

**Second path -- standard routing (always runs if first path didn't return):**

1. Find `requestedAgency` in `agencies[]`
2. If `primaryRegistry` is set and found in `configs[]` -> return it
3. Walk `secondaryRegistries` in order -> return first found in `configs[]`
4. Throw `AgencyRoutingException` (agency not routable)

The sub-algorithm "resolve registry from agency config" (try primary, then walk secondaries) is the same in both paths.

See [routing flowchart](agency_routing_flowchart.puml) and [sequence diagram](agency_routing_sequence.puml) for PlantUML
diagrams.

### Worked example: the cross-reference problem

This is the scenario that motivates the entire design.

**Setup:**

- `configs[]` has two registries: `BIS` and `IMF`
- `agencies[]`:
    - `{ "name": "IMF", "primaryRegistry": "IMF", "secondaryRegistries": ["BIS"] }`
    - `{ "name": "BIS", "primaryRegistry": "BIS" }`

**Sequence of events:**

1. Client requests a BIS data structure with all references:
   `GET /structure/datastructure/BIS/BIS_DER/1.0?references=all` (no header).
   No URN -> skip first path. Standard routing: `"BIS"` in agencies -> primaryRegistry=`"BIS"` -> found in configs -> *
   *route to BIS**. Correct.

   BIS returns the DSD + all referenced artefacts, including:
   `urn:sdmx:org.sdmx.infomodel.codelist.Codelist=IMF:CL_AREA(1.15)`.
   The client sees this codelist is maintained by agency `IMF` but came from BIS.

2. Client wants the full codelist, sends:
   `GET /structure/codelist/IMF/CL_AREA/1.15`
   `X-Source-Artefact-Urn: urn:sdmx:org.sdmx.infomodel.datastructure.DataStructureDefinition=BIS:BIS_DER(1.0)`

   First path: parse URN -> sourceAgency=`"BIS"`. Resolve registry for `"BIS"`: primaryRegistry=`"BIS"` -> found ->
   candidateRegistry=**BIS**. Cross-check: is BIS in `"IMF"`'s registries? IMF has primary=`"IMF"`, secondary=[
   `"BIS"`] -> **yes**, BIS is in secondaries -> **return BIS**. Correct -- artefact lives in BIS.

3. WITHOUT the header (today's broken behavior), the same request:
   `GET /structure/codelist/IMF/CL_AREA/1.15` (no header).
   No URN -> skip first path. Standard routing: `"IMF"` -> primaryRegistry=`"IMF"` -> **route to IMF**. Wrong -- IMF may
   not have CL_AREA v1.15.

**Key insight:** The URN header tells the routing service "I found this reference inside a BIS artefact." The
cross-check then verifies "can IMF artefacts actually be fetched from BIS?" -- and since BIS is in IMF's secondary
registries, the answer is yes.

### More examples

**Example A -- Normal request (no cross-reference):**
`GET /structure/dataflow/IMF.STA/BOP/1.0` (no header).
No URN -> standard routing: `"IMF.STA"` -> primaryRegistry=`"IMF"` -> found -> route to IMF.

**Example B -- Agency with no own registry (ISO), header present:**
`GET /structure/codelist/ISO/CL_CURRENCY/1.0`
`X-Source-Artefact-Urn: ...=BIS:BIS_CBS(1.0)`
First path: sourceAgency=`"BIS"` -> candidateRegistry=**BIS**. Cross-check: `"ISO"` has primary=null, secondary=[
`"BIS"`,
`"IMF"`] -> BIS is in the set -> **return BIS**.

**Example C -- Agency with no own registry (ISO), no header:**
`GET /structure/codelist/ISO/CL_CURRENCY/1.0` (no header).
No URN -> standard routing: `"ISO"` -> primaryRegistry=null -> secondaryRegistries=[`"BIS"`,`"IMF"`] -> `"BIS"` found ->
route to BIS.

**Example D -- URN present but source agency unknown:**
`GET /structure/codelist/IMF/CL_AREA/1.15`
`X-Source-Artefact-Urn: ...=UNKNOWN_AGENCY:SOME_DSD(1.0)`
First path: sourceAgency=`"UNKNOWN_AGENCY"` -> not in agencies -> no candidate. Fall through to second path:`"IMF"` ->
primaryRegistry=`"IMF"` -> route to IMF. (Graceful fallback.)

**Example E -- Cross-check fails (candidate not in requested agency's registries):**
Hypothetical: source URN points to a registry that doesn't host the requested agency.
`GET /structure/codelist/ESTAT/CL_SOMETHING/1.0`
`X-Source-Artefact-Urn: ...=IMF:SOME_DSD(1.0)`
First path: sourceAgency=`"IMF"` -> candidateRegistry=**IMF**. Cross-check: `"ESTAT"` has primary=null, secondary=[
`"BIS"`] -> IMF is NOT in the set -> cross-check fails. Fall through to second path: `"ESTAT"` -> primary=null ->
secondary=[`"BIS"`] -> route to BIS.

### URN parsing

Extract agency from: `urn:sdmx:org.sdmx.infomodel.{pkg}.{class}={agencyId}:{resourceId}({version})`

Uses `SdmxUrn.getUrnComponents(urn).getAgency()` from `com.epam.jsdmx:sdmx30-infomodel` (already a project dependency).
Returns `null` on malformed URNs (catches `SdmxUrn.UrnFormatException`).

## Data class

```java
// sdmx-proxy-config module (no Spring dependency)
@Data
public class AgencyConfiguration {
    private String name;
    private String primaryRegistry;       // nullable
    private List<String> secondaryRegistries;  // nullable/empty
}
```

Added to `ProxyConfiguration`:

```java
private List<AgencyConfiguration> agencies;
```

`RegistryConfiguration.supportedAgencies` field is removed.

## Service interface

```java
public interface AgencyRoutingService {
    RegistryConfiguration resolveRegistry(String requestedAgency, @Nullable String sourceArtefactUrn);
}
```

Implementation: `AgencyRoutingServiceImpl` — `@Service`, injects `ProxyConfigurationProvider`, implements the two-path
algorithm above. Throws `AgencyRoutingException` (extends `RuntimeException`, handled as 400 Bad Request by
`GlobalExceptionHandler`) when an agency is not routable.

## API changes

All four API endpoints gain an optional request header parameter:

```java

@RequestHeader(value = "X-Source-Artefact-Urn", required = false)
@Nullable
String sourceArtefactUrn
```

Affected endpoints:

- `SdmxStructure30Api.getResources()`
- `DataQuery30Api.dataQuery()`
- `AvailabilityQuery30Api.availabilityQuery()`
- `AvailabilityQuery30Api.availabilityQueryPost()`

## QueryTranslator changes

All translate methods gain `@Nullable String sourceArtefactUrn` as their last parameter:

- `translateStructureQuery(..., sourceArtefactUrn)`
- `translateToFanOutStructures(..., sourceArtefactUrn)`
- `translateDataQuery(..., sourceArtefactUrn)`
- `translateAvailabilityQuery(..., sourceArtefactUrn)`

`requiresFanOut()` is unchanged (it only checks agency ID pattern, not routing).

Internally, `QueryTranslatorImpl`:

- Injects `AgencyRoutingService`
- Replaces `getRegistryConfigurationForAgency(agencyID, configuration)` calls with
  `agencyRoutingService.resolveRegistry(requestedAgency, sourceArtefactUrn)`
- Deletes the old `getRegistryConfigurationForAgency()` private method
- Fan-out methods (`checkCommaSeparatedAgenciesRequireFanOut`, `createCommaSeparatedFanOutQueries`) use
  `agencyRoutingService.resolveRegistry(agency, null)` -- `sourceArtefactUrn` is intentionally not passed because
  fan-out queries all registries by definition, so source agency routing does not apply
- `checkCommaSeparatedAgenciesRequireFanOut` catches `AgencyRoutingException` (not `IllegalArgumentException`)

## Agencies list (production config)

| Agency     | primaryRegistry | secondaryRegistries |
|------------|-----------------|---------------------|
| BIS        | BIS             | -                   |
| IMF        | IMF             | BIS                 |
| IMF.STA    | IMF             | -                   |
| IMF.FAD    | IMF             | -                   |
| IMF.RES    | IMF             | -                   |
| IMF.STA.DS | IMF             | -                   |
| IMF.MCM    | IMF             | -                   |
| ESTAT      | -               | BIS                 |
| ISO        | -               | BIS, IMF            |
| SDMX       | -               | BIS, IMF            |
| LBS        | -               | BIS                 |
| CBS        | -               | BIS                 |
| MEDIT      | -               | BIS                 |
| MDD        | -               | BIS                 |

## Files affected

| File                                                                | Action                                      |
|---------------------------------------------------------------------|---------------------------------------------|
| `sdmx-proxy-config/.../data/AgencyConfiguration.java`               | CREATE                                      |
| `sdmx-proxy-config/.../data/ProxyConfiguration.java`                | EDIT - add `agencies` field                 |
| `sdmx-proxy-config/.../data/RegistryConfiguration.java`             | EDIT - remove `supportedAgencies`           |
| `sdmx-proxy/.../services/routing/AgencyRoutingService.java`         | CREATE                                      |
| `sdmx-proxy/.../services/routing/AgencyRoutingServiceImpl.java`     | CREATE                                      |
| `sdmx-proxy/.../services/translator/QueryTranslator.java`           | EDIT - add param                            |
| `sdmx-proxy/.../services/translator/QueryTranslatorImpl.java`       | EDIT - inject service, delegate routing     |
| `sdmx-proxy/.../api/SdmxStructure30Api.java`                        | EDIT - add header param                     |
| `sdmx-proxy/.../api/DataQuery30Api.java`                            | EDIT - add header param                     |
| `sdmx-proxy/.../api/AvailabilityQuery30Api.java`                    | EDIT - add header param                     |
| `sdmx-proxy/.../controller/SdmxStructure30Controller.java`          | EDIT - pass header through                  |
| `sdmx-proxy/.../controller/DataQuery30Controller.java`              | EDIT - pass header through                  |
| `sdmx-proxy/.../controller/AvailabilityQuery30Controller.java`      | EDIT - pass header through                  |
| `sdmx-proxy/src/main/resources/sdmx_registries_config.json`         | EDIT - agencies[], remove supportedAgencies |
| `sdmx-proxy-e2e/.../bis/3_0/bis_3_0_registry_config.json`           | EDIT                                        |
| `sdmx-proxy-e2e/.../imf/3_0/imf_3_0_registry_config.json`           | EDIT                                        |
| `sdmx-proxy-e2e/.../imf/2_1/imf_2_1_registry_config.json`           | EDIT                                        |
| `sdmx-proxy-e2e/.../config/sdmx_registries_config.json`             | EDIT                                        |
| `sdmx-proxy/.../services/routing/AgencyRoutingServiceImplTest.java` | CREATE                                      |
| `sdmx-proxy/.../services/translator/QueryTranslatorImplTest.java`   | EDIT - mock routing, update calls           |

## Known constraints

### Fan-out does not propagate `sourceArtefactUrn`

In `QueryTranslatorImpl`, the `translateToFanOutStructures` method accepts `sourceArtefactUrn` but does not pass it to
`createWildcardFanOutQueries` or `createCommaSeparatedFanOutQueries`. Inside those methods,
`agencyRoutingService.resolveRegistry()` is called with `null` for `sourceArtefactUrn`.

This is intentional: fan-out by definition queries all registries (wildcard `*`) or multiple specific agencies
(comma-separated). Source agency routing is designed for single-agency requests where the caller knows which parent
artefact contained the cross-reference. In the fan-out case, the proxy resolves each agency independently via standard
routing.

## Test plan

### AgencyRoutingServiceImplTest (new)

First path (source agency routing):

1. URN present, candidate resolved, cross-check passes -> returns candidate registry
2. URN present, candidate resolved via secondary, cross-check passes -> returns candidate
3. URN present, candidate resolved, cross-check FAILS (candidate not in requestedAgency's registries) -> falls through
   to second path
4. URN present, source agency not in config -> falls through to second path
5. URN present but malformed -> falls through to second path

Second path (standard routing):

6. No header, requested agency has primary -> returns primary
7. No header, requested agency has no primary, has secondary -> returns first secondary
8. No header, requested agency not in agencies config -> throws
9. No header, agencies config is null/empty -> throws

Edge cases:

10. URN parsing: various URN formats, null, empty string
11. Cross-check with agency that has both primary and secondaries -- candidate matches primary
12. Cross-check with agency that has both primary and secondaries -- candidate matches a secondary

### QueryTranslatorImplTest (updated)

- Add mock `AgencyRoutingService`
- Update all `translate*()` calls to include `sourceArtefactUrn` param (pass `null` for existing tests)
- Mock `agencyRoutingService.resolveRegistry()` to return appropriate `RegistryConfiguration`
- Remove/update tests that tested `supportedAgencies` behavior

### Verification

1. `./gradlew clean build` - must compile cleanly
2. `./gradlew :sdmx-proxy:test` - all unit tests pass
3. Manual: verify JSON config deserializes via `GET /api/config`
4. E2E (optional): `./gradlew :sdmx-proxy-e2e:test`
