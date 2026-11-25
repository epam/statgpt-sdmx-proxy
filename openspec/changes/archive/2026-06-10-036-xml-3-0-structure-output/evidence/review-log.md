# Review Log

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
