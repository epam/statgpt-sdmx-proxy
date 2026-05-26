# IMF Reference-Metadata Retrieval over SDMX 3.0 — Open Questions

## 1. What we are trying to do

We are extending StatGPT's data-query to surface reference metadata (provenance, methodology,
citation, scope, …) attached to an IMF dataflow such as `IMF.RES:WEO(9.0.0)`.
We route every upstream registry behind an SDMX 3.0 facade,
so we must retrieve reference-metadata values through the canonical SDMX 3.0
paths.

## 2. What data.imf.org returns today (SDMX-PLUS behaviour)

A `POST` to `https://data.imf.org/platform/rest/v1/registry/sdmx/3.0/dataflow`
for the WEO dataflow returns the data **plus 39 reference-metadata values
embedded inside `data.structures[0].attributes`**:

| Level            | Total attributes | Defined in DSD `attributes` | Defined as `metadataAttribute` in MSD |
|------------------|-----------------:|----------------------------:|--------------------------------------:|
| `dataSet`        | 18               | 0                           | **18** (DOI, AUTHOR, LICENSE, …)      |
| `dimensionGroup` | 37               | 17                          | **20** (TOPIC, METHODOLOGY, …)        |
| `series`         | 4                | 4                           | 0                                     |
| `observation`    | 3                | 2                           | **1** (SOURCE)                        |

Every "metadata" entry above resolves to a `metadataAttribute` declared in
`IMF.RES:MSD_WEO_METADATA_EXTERNAL(2.0.0)`. Values are clearly present in the
upstream system (we see them returned), and the agent could use them
verbatim — but the transport is non-standard.

## 3. What SDMX 3.0 prescribes

Per [SDMX-REST 2.2 — Metadata queries](https://github.com/sdmx-twg/sdmx-rest/blob/master/doc/metadata.md),
reference-metadata values live in **MetadataSets**, are queried through
the `/metadata/…` endpoints, and are serialised with
`application/vnd.sdmx.metadata+json;version=2.0.0` — they are **not**
carried in a data response.

Starting from a Dataflow, the chain is: the DSD declares
`metadataAttributeUsages` that reference an MSD; a Metadataflow is
published over that MSD; the values are then fetched with
`GET /metadata/metadataflow/{agency}/{id}/{version}/{provider}`.

## 4. What api.imf.org actually exposes (SDMX 3.0 endpoint)

Direct probes against `https://api.imf.org/external/sdmx/3.0` on
2026-05-26:

| Probe                                                                                                                                                | Result                                                                                                                       |
|------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------|
| `GET /structure/datastructure/IMF.RES/DSD_WEO/9.0.0`                                                                                                 | DSD carries **39 `metadataAttributeUsages`**, each referencing `IMF.RES:MSD_WEO_METADATA_EXTERNAL(2.0.0)` — discovery side OK |
| `GET /structure/metadatastructure/IMF.RES/MSD_WEO_METADATA_EXTERNAL/2.0.0`                                                                           | MSD is published, 39 `metadataAttribute` definitions present                                                                 |
| `GET /structure/dataflow/IMF.RES/WEO/9.0.0?references=DESCENDANTS`                                                                                   | Response contains `dataflows`, `dataStructures`, `conceptSchemes`, `codelists` only. **No `metadataflows`, no `metadataStructures`, no `metadataProvisionAgreements`, no MSD-side artefacts at all.** The DSD's MSD reference is not traversed by DESCENDANTS. |
| `GET /structure/metadataflow/IMF.RES`                                                                                                                | HTTP **204 No Content** — IMF.RES has zero Metadataflows                                                                     |
| `GET /structure/metadataflow`                                                                                                                        | Returns exactly **one** Metadataflow registry-wide: `IMF:EXTERNAL_DATASET_CARDS(1.0.0)` over `IMF:MSD_REF_EXT_DATASET(3.1.0)` — unrelated to WEO |

Net effect: **no Metadataflow targets `IMF.RES:MSD_WEO_METADATA_EXTERNAL`**,
no MetadataProvisionAgreement publishes against it, and nothing on the
Dataflow / DSD side links to a Metadataflow that would. The canonical
SDMX 3.0 retrieval call —
`GET /metadata/metadataflow/{agency}/{id}/{version}/{provider}` — therefore
cannot be constructed: we have no Metadataflow identifier to plug in. The
39 metadata-attribute values that data.imf.org demonstrably has today
have no discoverable SDMX-3.0 retrieval path.

## 5. Questions for IMF

**For a Dataflow whose DSD declares `metadataAttributeUsages` — how do
you intend a client of `api.imf.org/external/sdmx/3.0` to discover the
Metadataflow / MetadataProvisionAgreement / MetadataSet that delivers
those values?**

The spec gives us the artefact model and the
`GET /metadata/metadataflow/{agency}/{id}/{version}/{provider}` query
(see §3). What we are missing is the convention by which a client of
*your* registry obtains the `{agency}:{id}({version})/{provider}` tuple
for a given Dataflow.

Concretely, for `IMF.RES:WEO(9.0.0)`:

- The DSD declares 39 `metadataAttributeUsages` referencing
  `IMF.RES:MSD_WEO_METADATA_EXTERNAL(2.0.0)` — discovery side is wired.
- `?references=DESCENDANTS` on the Dataflow does not surface the MSD or
  any Metadataflow.
- `/structure/metadataflow/IMF.RES` returns HTTP 204; the registry-wide
  list contains only one Metadataflow
  (`IMF:EXTERNAL_DATASET_CARDS(1.0.0)`), unrelated to WEO.
- The values themselves *are* delivered — but only by the SDMX-PLUS
  data response on `data.imf.org`, which is not SDMX-3.0-conformant.

So either the Metadataflow does not yet exist on the SDMX-3.0 side, or
it exists but is not discoverable from the Dataflow. Could you tell us
which it is, and what the convention-conformant retrieval path is
expected to be — today, or once published?

## References

- [SDMX-REST 2.2 — Metadata queries](https://github.com/sdmx-twg/sdmx-rest/blob/master/doc/metadata.md)
- [SDMX-JSON 2.0 metadata-message schema](https://github.com/sdmx-twg/sdmx-json/blob/master/metadata-message/tools/schemas/2.0.0/sdmx-json-metadata-schema.json)
- SDMX 3.0 Information Model — sections on `MetadataAttributeUsage`,
  `MetadataStructure`, `Metadataflow`, `MetadataProvisionAgreement`,
  `MetadataSet`
- WEO artefacts referenced in this document:
  `IMF.RES:WEO(9.0.0)`, `IMF.RES:DSD_WEO(9.0.0)`,
  `IMF.RES:MSD_WEO_METADATA_EXTERNAL(2.0.0)`
