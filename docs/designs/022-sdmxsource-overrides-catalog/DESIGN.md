# Design 022: sdmx-core overrides catalog

**Status:** reference document; no code changes.

## Context

The proxy uses EPAM's fork of sdmx-core (`io.sdmx:*` + `com.epam.jsdmx:*`). The
runtime dependency was upgraded from fusion v2.3.9 to 2.3.21 to 2.4.0 in
commits `5e73890` and `6969259`; the read-only reference tree under
`C:/derzhstat/projects/statgpt/sdmx-core-2.3.9` is the 2.3.9 source, which is
the baseline most of these overrides were originally written against.
`docs/sdmx-core-2.3.21-upgrade.md` and `docs/sdmx-core-2.4.0-upgrade.md`
document the upstream deltas between the three releases.

This document catalogs every class under
`sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/sdmxsource/` plus
the related overrides under `common/mapping/` and
`services/fixture/structure/` that exist solely to work around the same
class of upstream limitations (sdmx-core IM gaps, spec-noncompliant
emission, subclass-hostile APIs). For each entry we record: what
upstream class it targets, how it is wired into the application, what
the behavioural delta is, what bug or limitation motivated it, the
blast radius, the status against 2.4.0, and the risks of removing it.

The intended audience is anyone needing to (a) decide whether a given
workaround is still required when bumping sdmx-core, (b) reproduce or extend
the workaround on a different format, or (c) push a fix upstream.

## Non-goals

- This is not an audit: we do not claim correctness of any individual override
  versus the SDMX specs; we only describe what is there and why.
- We do not propose code changes. Removal candidates are flagged but require
  separate design work plus an E2E pass before action.
- We do not enumerate every public method of every class; the goal is to
  describe the *delta* from upstream.

## Table of contents

Wiring / factory classes (no upstream override per se, but plug the custom
parsers/writers into Spring or sdmx-core's `FusionBeanStore`):

1. [`SdmxSourceConfig` (wiring summary)](#0-wiring-summary--sdmxsourceconfig)
1. [`JsonDataWriterFactoryProducer`](#1-jsondatawriterfactoryproducer)
1. [`JsonV1StructureReaderFactory`](#2-jsonv1structurereaderfactory)
1. [`JsonV2StructureReaderFactory`](#3-jsonv2structurereaderfactory)
1. [`CustomSdmxMLStructureWriterFactory`](#4-customsdmxmlstructurewriterfactory)
1. [`CustomSdmxJsonStructureReaderManagerV2`](#5-customsdmxjsonstructurereadermanagerv2)
1. [`CustomSdmxCsvDataReaderFactoryV2`](#6-customsdmxcsvdatareaderfactoryv2)
1. [`CustomSdmxJsonDataReaderFactory`](#7-customsdmxjsondatareaderfactory)
1. [`CustomSdmxJsonDataWriterFactory`](#8-customsdmxjsondatawriterfactory)

Reader-engine overrides (per-format parsing):

1. [`CustomSdmxJsonHierarchicalCodelistReaderEngineV2`](#9-customsdmxjsonhierarchicalcodelistreaderenginev2)
1. [`CustomSdmxJsonDataStructureReaderEngineV2`](#10-customsdmxjsondatastructurereaderenginev2)
1. [`CustomSdmxJsonDataReaderEngineV2`](#11-customsdmxjsondatareaderenginev2)
1. [`CustomSdmxJsonMetadataIteratorV2`](#12-customsdmxjsonmetadataiteratorv2)
1. [`CustomSdmxStructureIterator`](#13-customsdmxstructureiterator)
1. [`CustomSdmxCsvDataReaderEngineV2`](#14-customsdmxcsvdatareaderenginev2)

Writer-engine overrides (XML 2.1 output):

1. [`CustomStaxAbstractStructureWriterEngineV21`](#15-customstaxabstractstructurewriterenginev21)
1. [`CustomStaxStructureWriterEngineV21`](#16-customstaxstructurewriterenginev21)
1. [`CustomStaxDsdWriterEngineV21`](#17-customstaxdsdwriterenginev21)

Writer-engine overrides (SDMX-JSON 2.0 data output — design 026):

1. [`CustomSdmxJsonDataWriterEngineV2`](#23-customsdmxjsondatawriterenginev2)
1. [`CustomSdmxJsonSeriesDataWriterV2` and `CustomSdmxJsonFlatDataWriterV2`](#24-customsdmxjsonseriesdatawriterv2-and-customsdmxjsonflatdatawriterv2)
1. [`SdmxJsonV2WriterOverrides`](#25-sdmxjsonv2writeroverrides)

Other supporting workarounds:

1. [`CustomSdmxSuperBeanRetrievalManagerImpl`](#18-customsdmxsuperbeanretrievalmanagerimpl)
1. [`CustomDataTransformationUtil`](#19-customdatatransformationutil)
1. [`QuotedNewlineCanonicalizingInputStream`](#20-quotednewlinecanonicalizinginputstream)
1. [`TimeDimensionTextTypePatcher`](#21-timedimensiontexttypepatcher)
1. [`HierarchyMapper` (related — under `common/mapping/`)](#22-hierarchymapper-related--common-mapping)

Related overrides outside `services/sdmxsource/` (design 025):

1. [`DataStructureMapper` MSD reference and conceptRoles (related — under `common/mapping/`)](#26-datastructuremapper-msd--conceptroles-related--common-mapping)
1. [`AnnotationValueToTextJsonFixture` (related — under `services/fixture/structure/`)](#27-annotationvaluetotextjsonfixture-related--services-fixture-structure)
1. [`MetadataAttributeUsagePreserver` (related — under `services/fixture/structure/`)](#28-metadataattributeusagepreserver-related--services-fixture-structure)

Related overrides outside `services/sdmxsource/` (designs 028 + the wildcard
version fix):

1. [`JsonDataV20SeriesLimitTruncator` path-aware match (related — under `services/limit/truncate/`)](#29-jsondatav20serieslimittruncator-path-aware-match-related--services-limit-truncate)
1. [`DimensionServiceImpl` wildcard version resolution (related — under `services/misc/`)](#30-dimensionserviceimpl-wildcard-version-resolution-related--services-misc)

After the per-file catalog, see the [Cross-cutting analysis](#cross-cutting-analysis) section.

---

## Per-file catalog

### 0. Wiring summary — `SdmxSourceConfig`

`sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/adapter/config/SdmxSourceConfig.java`
is the Spring `@Configuration` that binds the custom factories and parsers
into the application context. It is the single most useful file to read when
trying to understand how the overrides reach the SDMX runtime; every entry
below cross-references it where applicable.

| Bean                                       | Type                                                                                          | Replaces / wraps                     |
|--------------------------------------------|-----------------------------------------------------------------------------------------------|--------------------------------------|
| `customSdmxJsonStructureReaderManagerV2`   | `CustomSdmxJsonStructureReaderManagerV2`                                                      | `SdmxJsonStructureReaderManagerV2`   |
| `sdmxJsonDataReaderFactory`                | `CustomSdmxJsonDataReaderFactory`                                                             | `SdmxJsonDataReaderFactory`          |
| `sdmxCsvDataReaderFactoryV2`               | `CustomSdmxCsvDataReaderFactoryV2`                                                            | `SdmxCsvDataReaderFactoryV2`         |
| `JsonDataWriterFactoryProducer` (separate) | producer that constructs `CustomSdmxJsonDataWriterFactory` with `CustomSdmxSuperBeanRetrievalManagerImpl` | replaces direct use of `SdmxJsonDataWriterFactory.getInstance()` on the conversion path |

Upstream singletons such as `SdmxMLStructureReaderFactory`,
`SdmxMLDataReaderFactory`, `SdmxCsvDataReaderFactoryV1`,
`SdmxJsonDataWriterFactory`, `SdmxJsonStructureReaderManagerV1`,
`V3BeansBuilder`, and `SdmxSourceReadableDataLocationFactory` are exposed
unchanged — only the V2 JSON path, the V2 CSV reader, and the V21 XML writer
are intercepted.

The custom XML structure writer (`CustomSdmxMLStructureWriterFactory` +
`CustomStaxStructureWriterEngineV21`) is wired implicitly via Spring's
component scan (the classes carry `@Service`) and consumed by
`StreamingStructureConversionService`.

`File: sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/adapter/config/SdmxSourceConfig.java`

---

### 1. `JsonDataWriterFactoryProducer`

**Override target:** none directly. It is a thin Spring `@Service` that
constructs `CustomSdmxJsonDataWriterFactory` instances per call (lazy
construction, not a singleton), paired with
`CustomSdmxSuperBeanRetrievalManagerImpl` (see §18) and the caller-supplied
`SdmxBeanRetrievalManager`.

**Mechanism:** producer / factory-of-factories.

**Wiring:** `StreamingDataConversionService` line 58 injects the producer;
line 256 calls `getDataWriterFactory(...)` for every JSON data conversion.

**Behavioural difference vs upstream:** sdmx-core's
`SdmxJsonDataWriterFactory` is a static singleton (`getInstance()`) that
pulls super-bean and bean-retrieval managers from
`MetadataAwareDataWriterFactory`'s setters at registration time. The producer
makes the factory request-scoped instead so that each conversion uses a
fresh `InMemoryRetrievalManager` rooted in the SdmxBeans of that request —
avoiding shared mutable state between concurrent conversions and side-stepping
the static `SingletonStore` registration path.

**Bug / limitation:** the proxy multiplexes structures from many registries
through the same JVM. A singleton `SdmxJsonDataWriterFactory` would have its
super-bean manager replaced on every request, causing races under load. Wrap
it instead.

**Severity / blast radius:** affects every SDMX-JSON v1/v2 data response on
the conversion path.

**Status against 2.4.0:** upstream still uses a singleton; producer must
stay.

**Risks of removal:** races / cross-tenant data leakage on JSON data
conversion.

`File: sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/sdmxsource/JsonDataWriterFactoryProducer.java`

---

### 2. `JsonV1StructureReaderFactory`

**Override target:** wraps `io.sdmx.format.json.manager.SdmxJsonStructureReaderManagerV1`
so it can be exposed as a `StructureReaderFactory` (Spring-injectable bean).
Not a behavioural override; it adapts the upstream manager to the
`StructureReaderFactory` interface used by the proxy's conversion service.

**Mechanism:** adapter.

**Wiring:** `StreamingStructureConversionService` line 44 injects this for
the SDMX-JSON 1.0 reader path.

**Behavioural difference vs upstream:** none beyond the interface adapter.
Calls `reader.readJson(location, null)`.

**Bug / limitation:** upstream's `StructureReaderFactory` contract isn't
implemented by the V1 manager directly.

**Severity / blast radius:** every SDMX-JSON v1.0 structure parse.

**Status against 2.4.0:** unchanged.

**Risks of removal:** would have to inline upstream `readJson(...)` into
the conversion service. No upstream fix is needed; this is just glue.

`File: sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/sdmxsource/JsonV1StructureReaderFactory.java`

---

### 3. `JsonV2StructureReaderFactory`

**Override target:** same role as §2 for the V2 manager — wraps
`CustomSdmxJsonStructureReaderManagerV2` (§5) as a `StructureReaderFactory`.

**Mechanism:** adapter.

**Wiring:** `StreamingStructureConversionService` line 45.

**Behavioural difference vs upstream:** none beyond the interface adapter.

**Bug / limitation:** as §2, plus the inner manager carries V2-specific
overrides (hierarchies + DSD reader). See §5.

**Severity / blast radius:** every SDMX-JSON v2.0 structure parse.

**Status against 2.4.0:** unchanged.

**Risks of removal:** none directly; inner overrides (§5) are what matters.

`File: sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/sdmxsource/JsonV2StructureReaderFactory.java`

---

### 4. `CustomSdmxMLStructureWriterFactory`

**Override target:** `io.sdmx.format.ml.factory.structure.SdmxMLStructureWriterFactory`
(the upstream `StructureWriterFactory` for SDMX-ML structure output).

**Mechanism:** full replacement — does not extend upstream; implements
`StructureWriterFactory` directly.

**Wiring:** `@Service` (Spring), injected into
`StreamingStructureConversionService` line 42. Returns the custom V2.1
writer engine (`CustomStaxStructureWriterEngineV21`, §16) for the
`SDMX_V21_STRUCTURE_DOCUMENT` branch; delegates to upstream singletons for
V1 / V2 / V3 / registry-submit branches.

**Behavioural difference vs upstream:** the V2.1 branch is the only one
swapped — upstream returns its own `StaxStructureWriterEngineV21` singleton;
this returns the Spring-managed custom one (so the custom DSD writer
(§17) is in scope and so `afterHeader` / `getDocumentRoot` overrides apply).
Priority is set to `11` so the proxy's factory wins over upstream's default
priority on the `FactoryStore` lookup.

**Bug / limitation:** the proxy needs the custom DSD writer (§17) and the
ability to inject `SchemaLocationManager` / `HeaderRetrievalManager` per
context. Upstream's singleton doesn't permit either.

**Severity / blast radius:** all SDMX-ML v2.1 structure output. SDMX-ML v3
output is unaffected.

**Status against 2.4.0:** behaviourally compatible; per
`docs/sdmx-core-2.4.0-upgrade.md` upstream added a separate
`HIERARCHICAL_CODELIST` write path, which is *not* mirrored here (the proxy
still routes hierarchical codelists through `HIERARCHY`). Flagged as a
follow-up in that doc.

**Risks of removal:** loss of DSD `End MeasureList` fix (§17), loss of
custom 2.1 namespace wiring.

`File: sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/sdmxsource/CustomSdmxMLStructureWriterFactory.java`

---

### 5. `CustomSdmxJsonStructureReaderManagerV2`

**Override target:** `io.sdmx.format.json.manager.SdmxJsonStructureReaderManagerV2`.

**Mechanism:** full replacement — extends `AbstractJsonStructureReaderManager`
directly (not the upstream singleton, which is package-private friendly to
its own `getInstance()`). Registers the same reader engines, with two
substitutions.

**Wiring:** `@Service` (Spring), exposed via the
`customSdmxJsonStructureReaderManagerV2` `@Bean`. Consumed by
`JsonV2StructureReaderFactory` (§3).

**Behavioural difference vs upstream:**

| Reader registered                                 | Upstream                                          | Override                                          |
|---------------------------------------------------|---------------------------------------------------|---------------------------------------------------|
| `dataStructures` reader                           | `SdmxJsonDataStructureReaderEngineV2`             | `CustomSdmxJsonDataStructureReaderEngineV2` (§10) |
| `hierarchies` reader                              | `SdmxJsonHierarchicalCodelistReaderEngineV2`      | `CustomSdmxJsonHierarchicalCodelistReaderEngineV2` (§9) |

`skipToData` is a verbatim copy of the upstream method (no functional
difference; copied because the upstream class isn't subclass-friendly).

**Bug / limitation:** the upstream V2 manager hard-codes singleton
references to readers we need to replace.

**Severity / blast radius:** every SDMX-JSON 2.0 structure response.

**Status against 2.4.0:** upstream class unchanged between 2.3.9 and 2.4.0
(per the 2.4.0 upgrade doc); manager would still need to be replaced to
swap in the two custom readers.

**Risks of removal:** the two replacement readers (§9, §10) would no
longer be used — hierarchies with non-formal levels would crash on the URN
parser, and DSDs with `metadataAttributeUsages` would parse as zero-component
structures (see §10).

`File: sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/sdmxsource/CustomSdmxJsonStructureReaderManagerV2.java`

---

### 6. `CustomSdmxCsvDataReaderFactoryV2`

**Override target:** `io.sdmx.format.csv.factory.v2.SdmxCsvDataReaderFactoryV2`.

**Mechanism:** full replacement of the upstream factory; the upstream
factory is a singleton with private constructor and cannot be cleanly
subclassed. Implements `DataReaderFactory` + `IFusionSingleton` directly.

**Wiring:** registered as a Spring `@Bean` (`sdmxCsvDataReaderFactoryV2`)
that calls `getInstance()` (registers itself in `FusionBeanStore` too).

**Behavioural difference vs upstream:** identical control flow except it
instantiates `CustomSdmxCsvDataReaderEngineV2` (§14) instead of the upstream
engine.

**Bug / limitation:** the only way to inject the labels=both header fix
(§14) without forking the upstream factory.

**Severity / blast radius:** every SDMX-CSV v2 read path. SDMX-CSV v1 is
unaffected.

**Status against 2.4.0:** upstream factory unchanged; per the 2.4.0 doc,
the *engine* it instantiates now handles `IntentionallyMissingKeyValue` —
which our custom engine does *not* mirror. The factory itself remains
required as a wiring hook.

**Risks of removal:** labels=both CSV breaks on every column lookup (DSDs
report "no component found"). See §14 for the test that covers it.

`File: sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/sdmxsource/CustomSdmxCsvDataReaderFactoryV2.java`

---

### 7. `CustomSdmxJsonDataReaderFactory`

**Override target:** `io.sdmx.format.json.factory.data.SdmxJsonDataReaderFactory`
(the upstream factory that produces SDMX-JSON 1.0 / 2.0 data reader
engines).

**Mechanism:** full replacement — extends nothing; implements
`DataReaderFactory` + `IFusionSingleton`.

**Wiring:** Spring `@Bean` `sdmxJsonDataReaderFactory`; also registers
itself into `FusionBeanStore` via `getInstance()`.

**Behavioural difference vs upstream:** SDMX-JSON 1.0 returns the upstream
`SdmxJsonDataReaderEngine`; SDMX-JSON 2.0 returns `CustomSdmxJsonDataReaderEngineV2`
(§11) instead of the upstream engine.

**Bug / limitation:** the upstream factory hard-codes the V2 engine; can't
be subclassed cleanly.

**Severity / blast radius:** every SDMX-JSON v2 data conversion.

**Status against 2.4.0:** upstream V2 engine grew
`IntentionallyMissingKeyValue` handling and negative-index tolerance that
our V2 engine does *not* mirror — flagged in the 2.4.0 upgrade doc.

**Risks of removal:** inline (non-indexed) attribute values in SDMX-JSON
2.0 data crash the reader; see §11.

`File: sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/sdmxsource/CustomSdmxJsonDataReaderFactory.java`

---

### 8. `CustomSdmxJsonDataWriterFactory`

**Override target:** `io.sdmx.format.json.factory.data.SdmxJsonDataWriterFactory`.

**Mechanism:** extends `MetadataAwareDataWriterFactory` (the parent class
the upstream singleton extends) but is constructed per request rather than
as a JVM-wide singleton.

**Wiring:** never constructed directly — `JsonDataWriterFactoryProducer`
(§1) builds one per conversion request, passing in a request-scoped
`SdmxSuperBeanRetrievalManager` (the custom one, §18) and the request
SdmxBeans.

**Behavioural difference vs upstream:** two deltas:

1. *Construction.* It takes the super-bean / bean retrieval managers as
   constructor parameters via Lombok `@RequiredArgsConstructor` rather
   than relying on `SingletonStore` registration. That allows the
   producer (§1) to scope them per request.
2. *V2 engine swap.* The SDMX-JSON 2.0 branch instantiates
   `CustomSdmxJsonDataWriterEngineV2` (§23) instead of upstream's
   `SdmxJsonDataWriterEngineV2`, so the `roles`-plural and non-coded
   TIME_PERIOD overrides (issue #80 #3, #5) take effect. The
   1.0 branch is unchanged.

**Bug / limitation:** upstream factory singleton with statically-bound
managers cannot serve multiple registries concurrently safely.

**Severity / blast radius:** every SDMX-JSON 1.0 / 2.0 data write on the
conversion path.

**Status against 2.4.0:** unchanged. (Upstream still ships a singleton.)

**Risks of removal:** race conditions when concurrent conversions across
different registries trigger different SdmxBeans contexts; the singleton's
managers would last-writer-win.

`File: sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/sdmxsource/CustomSdmxJsonDataWriterFactory.java`

---

### 9. `CustomSdmxJsonHierarchicalCodelistReaderEngineV2`

**Override target:**
`io.sdmx.format.json.engine.structure.reader.sdmx.v2.SdmxJsonHierarchicalCodelistReaderEngineV2`.

**Mechanism:** full replacement — extends
`AbstractSdmxJsonReaderEngine<HierarchyMutableBean>` directly (the upstream
class is final-like in the sense that its singleton can't be subclassed
without copy-paste).

**Wiring:** registered in `CustomSdmxJsonStructureReaderManagerV2.<init>`
(§5) for the `hierarchies` container element.

**Behavioural difference vs upstream:**

1. `level` field on each `hierarchicalCode`: upstream parses it via
   `StructureReferenceBeanImpl.buildAndVerify(value, SDMX_STRUCTURE_TYPE.LEVEL)`
   and then strips a prefix. Per SDMX-JSON 2.0.0 spec the field is an idType
   (a plain string like `"0"` or `"L1"`); the URN-style parse crashes with
   `URN '0' is not well formed, missing '='`. Override stores the raw value
   via `hRefBean.setLevelReference(value)`.
2. `validFrom` / `validTo`: upstream (2.3.9) parsed both as
   `DateUtil.formatDate(value, true)` (always start-of-period). Override —
   matching what upstream 2.3.21 / 2.4.0 now do — parses `validFrom` at
   start-of-period and `validTo` at end-of-period via
   `SdmxDateImpl.getSdmxDate`. (See `docs/sdmx-core-2.3.21-upgrade.md`.)

**Bug / limitation:** upstream spec-compliance bug on `level` parsing. The
`validTo` parser was a latent upstream bug that has since been fixed
upstream.

**Severity / blast radius:** any SDMX-JSON 2.0 hierarchical codelist
response whose `level` is not URN-formatted (which, per spec, is all of
them) — i.e. every BIS / IMF hierarchy.

**Status against 2.4.0:** still required. Per the 2.4.0 upgrade doc, the
`level`-as-idType bug remains in 2.4.0.

**Risks of removal:** hierarchies regress to `SdmxSemmanticException` on
every `level` field.

**Related E2E:** the IMF hierarchy artefact
`IMF.STA:H_BOP_BOP_AGG_ANALYTIC_PRESENTATION(1.13.0)` from design
007-enable-hierarchies.

`File: sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/sdmxsource/CustomSdmxJsonHierarchicalCodelistReaderEngineV2.java`

---

### 10. `CustomSdmxJsonDataStructureReaderEngineV2`

**Override target:**
`io.sdmx.format.json.engine.structure.reader.sdmx.v2.SdmxJsonDataStructureReaderEngineV2`.

**Mechanism:** full replacement (same parent class
`AbstractSdmxJsonReaderEngine<DataStructureMutableBean>`).

**Wiring:** registered in `CustomSdmxJsonStructureReaderManagerV2` (§5)
for the `dataStructures` container element.

**Behavioural difference vs upstream:**

1. `getAttributes(...)` adds a "skip the `metadataAttributeUsages` array"
   short-circuit. Upstream walks the array as if it were `attributes`,
   reads zero items, and returns an empty AttributeList — causing parsed
   DSDs to look like they have zero components. The fix is annotated
   `//TODO CONSIDER COMMITING THIS TO SDMXSOURCE`.
2. `readAttributeRelationship`: adds a `case "none"` that maps to
   `ATTRIBUTE_ATTACHMENT_LEVEL.DATA_SET` (upstream handles only `dataflow`
   for the same level).

Rest of the file (dimensions, measures, groups) is a verbatim copy.

**Bug / limitation:** upstream lacks support for the
`metadataAttributeUsages` SDMX-JSON v2 field (the IM does not model it),
and lacks the `attributeRelationship.none` branch.

**Severity / blast radius:** every SDMX-JSON 2.0 DSD that uses either
field. IMF 3.0 DSDs use both.

**Status against 2.4.0:** upstream class byte-identical between 2.3.9 and
2.4.0 per the upgrade doc; workaround still required.

**Risks of removal:** unit test `StreamingStructureConversionServiceTest`
.`shouldConvertStructures_Imf_3_0_Dsd_NoDimension` regresses; downstream
DSD-dependent flows (data reader needs a DSD with non-zero dimensions)
fail.

**Upstream contribution candidate.** The `metadataAttributeUsages` skip is
a clear spec adherence improvement.

`File: sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/sdmxsource/CustomSdmxJsonDataStructureReaderEngineV2.java`

---

### 11. `CustomSdmxJsonDataReaderEngineV2`

**Override target:**
`io.sdmx.format.json.engine.data.reader.SdmxJsonDataReaderEngineV2`.

**Mechanism:** full replacement — copy of upstream's
`AbstractDataReaderEngine` subclass; most methods are renamed-mirror copies
of upstream, with two structural changes.

**Wiring:** instantiated from `CustomSdmxJsonDataReaderFactory` (§7) for
SDMX-JSON 2.0 inputs.

**Behavioural difference vs upstream:**

1. **Inline attribute support.** The upstream reader assumes every entry in
   a dataset / series / observation `attributes` array is an integer index
   into a structures-section component values list. SDMX-JSON 2.0 spec
   allows inline string / localized-text / array forms when the component
   has no `values`. The override introduces `AttributeValue` (a private
   class with index, indices, and direct-values variants) plus
   `readMixedAttributeArray` / `readMixedAttributeElement` /
   `decodeMixed` / `decodeNested` / `decodeNestedMixed` — these accept
   either indexed or inline forms.
2. **Dataset-level `attributes` skip fix.** Upstream's
   `moveNextDatasetInternal` (2.3.9) calls `jReader.readIntegerArray()`
   which assumes integers and fails on inline. The override replaces it
   with a guarded `jReader.moveToEndArray()` — but had to be careful about
   `JsonReader.moveNext()`'s position invariant. See design 010 for the
   subtle bug fixed in `cf4e5a0` where calling `moveNext()` before
   `moveToEndArray()` would step into the array body and break depth
   tracking.
3. **Wires `CustomSdmxStructureIterator`** (§13) / `CustomSdmxJsonMetadataIteratorV2`
   (§12) instead of the upstream iterators — needed because
   `SdmxStructureIterator` is a public class but its `AttraMapping` and
   `JsonDatasetStructuralMetadata` inner classes are package-private to the
   upstream package.
4. **Observation parsing tolerates fewer columns than DSD measures** (line
   753): `i < obsValues.size() ? obsValues.get(i) : null` avoids `IndexOutOfBoundsException`
   when JSON omits obs-level attribute columns.
5. **Empty-series leakage fix (issue #80 #1).** Upstream's `lazyLoadKey`
   loop only breaks on the `"observations"` `START_OBJECT`. For a series
   that has no observations sub-object — IMF WEO emits three such empty
   series per response — the loop walks past the current series's
   `END_OBJECT` into the next series, overwriting `attributes` with the
   next series's values and then breaking at *its* `"observations"`. The
   outer iteration then attributes the next series's observations to the
   empty series's positional key, and silently drops the next series
   from the output. **Three of 8200 IMF WEO series went to the wrong
   INDICATOR before the fix.** Override adds an `END_OBJECT` branch that
   detects the popped stack landing at the parent `"series"` map, sets
   an instance flag `currentSeriesIsEmpty`, and breaks. The flag is read
   by `moveNextObservationInternal` (series branch) — when set, it
   returns `false` without consuming a token, leaving the cursor at the
   empty series's `END_OBJECT` so `moveNextKeyableInternal` finds the
   next series's `START_OBJECT` normally. The flag is reset at every
   `lazyLoadKey` entry. Non-empty series flow through the original
   `"observations"`-break path with no change.

**Bug / limitation:** upstream spec-compliance bug — the SDMX-JSON 2.0
spec explicitly allows inline attribute values.

**Severity / blast radius:** every SDMX-JSON 2.0 data response with at
least one inline (non-indexed) attribute, which covers IMF FSIC and most
IMF 3.0 dataflows with dataset attributes.

**Status against 2.4.0:** upstream added `IntentionallyMissingKeyValue`
handling (per the 2.4.0 upgrade doc). Override does *not* mirror it —
flagged as a follow-up. Inline-attribute, dataset-skip, and empty-series
fixes all remain required (the empty-series bug is still present in
2.4.0 — verified by tracing the same `lazyLoadKey` loop unchanged).

**Risks of removal:** the failing scenario from design 010 returns —
every series-keyed SDMX-JSON 2.0 response with non-empty dataset-level
`attributes` mis-iterates and misreads `observations` fields. Additionally,
silent data corruption returns for any response that contains an
attribute-only / empty series.

**Related designs:** 010-fix-skip-inline-dataset-attributes,
026-data-conversion-fidelity-sdmx-json-2-0 (stage 4 empty-series fix).

`File: sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/sdmxsource/CustomSdmxJsonDataReaderEngineV2.java`

---

### 12. `CustomSdmxJsonMetadataIteratorV2`

**Override target:**
`io.sdmx.format.json.engine.data.reader.SdmxJsonMetadataIteratorV2`.

**Mechanism:** copy of upstream class (no inheritance — `AbstractIterator` is
the shared parent). Almost byte-identical except references to
`SdmxStructureIterator` are swapped for `CustomSdmxStructureIterator` (§13).

**Wiring:** instantiated from `CustomSdmxJsonDataReaderEngineV2.reset()`
(§11 line 249).

**Behavioural difference vs upstream:** none semantically — it exists only
to refer to the custom structure iterator class which itself differs in
package and visibility.

**Bug / limitation:** upstream's metadata iterator is hard-coupled to
upstream's `SdmxStructureIterator`. To reach `JsonDatasetStructuralMetadata`
(which is a public inner class on the iterator) the proxy needs its own
iterator with public inner classes too.

**Severity / blast radius:** every SDMX-JSON 2.0 data read (since §11 uses
it).

**Status against 2.4.0:** upstream class byte-identical 2.3.9 → 2.4.0; the
override is a pure visibility / packaging duplicate.

**Risks of removal:** §11 stops compiling.

**Maintenance note:** this and §13 together are roughly 500 lines that
exist purely to escape upstream's package-private visibility. A clean way
out is upstream contribution to widen the iterator's visibility (or to
expose the metadata model directly).

`File: sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/sdmxsource/CustomSdmxJsonMetadataIteratorV2.java`

---

### 13. `CustomSdmxStructureIterator`

**Override target:**
`io.sdmx.format.json.engine.data.reader.SdmxStructureIterator`.

**Mechanism:** copy of upstream class. The inner `JsonDatasetStructuralMetadata`,
`AttraMapping`, and `ComponentIterator` classes are public / inner
where upstream had them package-private friendly.

**Wiring:** §12 instantiates it; §11 reads its `JsonDatasetStructuralMetadata`.

**Behavioural difference vs upstream:** semantically identical. The
critical difference is *visibility* of inner classes so that
`CustomSdmxJsonDataReaderEngineV2` (§11) — which is in
`com.epam.sdmxproxy.services.sdmxsource` rather than the upstream
`io.sdmx.format.json.engine.data.reader` package — can `import` and
reference `AttraMapping` and `JsonDatasetStructuralMetadata`.

**Bug / limitation:** package-private visibility of upstream inner types.

**Severity / blast radius:** every SDMX-JSON 2.0 data read.

**Status against 2.4.0:** upstream byte-identical; override is pure
visibility / packaging duplicate.

**Risks of removal:** §11, §12 stop compiling.

**Upstream contribution candidate.** Widening visibility of
`SdmxStructureIterator.JsonDatasetStructuralMetadata` and
`AttraMapping` would let the proxy drop §12 and §13 entirely.

`File: sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/sdmxsource/CustomSdmxStructureIterator.java`

---

### 14. `CustomSdmxCsvDataReaderEngineV2`

**Override target:** `io.sdmx.format.csv.engine.v2.SdmxCsvDataReaderEngineV2`.

**Mechanism:** extends upstream class; overrides exactly two methods —
`createCopy()` (so the copy is also custom) and `getHeaderColumnComponent(String)`.

**Wiring:** instantiated from `CustomSdmxCsvDataReaderFactoryV2` (§6).

**Behavioural difference vs upstream:** upstream's
`getHeaderColumnComponent` does `currentDsd.getComponent(columnId)`. The
override first strips a `:Name` suffix when present
(`columnId.indexOf(":") > 0`). When CSV headers use `labels=both`, columns
look like `FREQ:Frequency`; the DSD only knows `FREQ`, so upstream lookup
returns null and the row is dropped.

**Bug / limitation:** upstream CSV reader does not parse `labels=both`
headers correctly (the spec for SDMX-CSV 2.0 says label suffix is allowed
in the header column id).

**Severity / blast radius:** every SDMX-CSV v2 response with `labels=both`
parameter. IMF CSV in `labels=both` mode failed before the fix.

**Status against 2.4.0:** upstream class added
`IntentionallyMissingKeyValue` handling (per upgrade doc) but did *not*
fix the labels=both header parsing. Override still required.

**Risks of removal:** `labels=both` CSV reads return empty / wrong data.
E2E suite covers labels-both via the IMF / BIS CSV cases.

**Related design:** 018-imf-csv-conversion-bugs (root-causes the IMF CSV
failures, of which the labels=both header was one).

**Upstream contribution candidate.** This is a clean four-line
spec-compliance fix.

`File: sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/sdmxsource/CustomSdmxCsvDataReaderEngineV2.java`

---

### 15. `CustomStaxAbstractStructureWriterEngineV21`

**Override target:**
`io.sdmx.format.ml.engine.structure.writer.v21.StaxAbstractStructureWriterEngineV21`.

**Mechanism:** copy of upstream abstract class with two diffs. Not an
extension because upstream's `writerEngines` map is private and `getWriterEngine`
is protected — the only way to substitute a specific engine is to maintain
a parallel class.

**Wiring:** §16 (`CustomStaxStructureWriterEngineV21`) extends this; §4
returns §16 from its factory.

**Behavioural difference vs upstream:**

1. `writerEngines.put(SDMX_STRUCTURE_TYPE.DSD, CustomStaxDsdWriterEngineV21.getInstance())`
   instead of `StaxDsdWriterEngineV21.getInstance()` — uses the custom DSD
   writer (§17).
2. The `writeStructures(StaxWriter, String, SdmxBeans, SDMX_STRUCTURE_TYPE)`
   helper does *not* call `ItemValidityPeriodHelper.potentiallyMutateIt(...)`.
   Upstream 2.3.9 called this to collapse codelists / concept schemes /
   structure sets to a "valid today" view; upstream 2.3.21 removed it
   (`ItemValidityPeriodHelper` was removed entirely), so the override now
   matches upstream 2.3.21+ — but it diverges from the *original* 2.3.9
   baseline behaviour. See `docs/sdmx-core-2.3.21-upgrade.md` §1.

**Bug / limitation:** (1) needed to inject the custom DSD writer; (2) was
a temporary divergence that is now upstream-aligned.

**Severity / blast radius:** all SDMX-ML v2.1 structure output.

**Status against 2.4.0:** upstream further added a `HIERARCHICAL_CODELIST`
write block in 2.4.0 (per the 2.4.0 upgrade doc) — *not* mirrored in this
override. Currently hierarchical codelists still route through `HIERARCHY`.
Flagged as a follow-up.

**Risks of removal:** §17 disconnected; `End MeasureList` regression
returns; future 2.4.0 alignment would also need to be redone.

**Removal candidate.** Now that 2.3.21+ no longer uses
`ItemValidityPeriodHelper`, the only remaining reason this class exists is
to swap the DSD writer. A thinner extension that overrides just
`getWriterEngine(SDMX_STRUCTURE_TYPE)` (if upstream made it package-friendly)
would eliminate ~300 lines of copy-paste.

`File: sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/sdmxsource/CustomStaxAbstractStructureWriterEngineV21.java`

---

### 16. `CustomStaxStructureWriterEngineV21`

**Override target:** `io.sdmx.format.ml.engine.structure.writer.v21.StaxStructureWriterEngineV21`.

**Mechanism:** extends `CustomStaxAbstractStructureWriterEngineV21` (§15).

**Wiring:** `@Service`; injected by §4. Construction passes the upstream
namespace constants and pulls `SchemaLocationManager` / `HeaderRetrievalManager`
from `SingletonStore`.

**Behavioural difference vs upstream:** semantically identical to the
upstream concrete class — same constants, same `afterHeader` (no-op), same
`getDocumentRoot` ("Structure"). Only exists because it must extend §15
rather than the upstream abstract class.

**Bug / limitation:** none of its own; pure plumbing.

**Severity / blast radius:** all SDMX-ML v2.1 structure output.

**Status against 2.4.0:** unchanged.

**Risks of removal:** loses the custom abstract parent and therefore §17.

`File: sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/sdmxsource/CustomStaxStructureWriterEngineV21.java`

---

### 17. `CustomStaxDsdWriterEngineV21`

**Override target:**
`io.sdmx.format.ml.engine.structure.writer.v21.dsd.StaxDsdWriterEngineV21`.

**Mechanism:** copy of upstream class (extends `AbstractV21Writer<DataStructureBean>`).

**Wiring:** §15 puts this engine into the writer map for
`SDMX_STRUCTURE_TYPE.DSD`.

**Behavioural difference vs upstream:** one structural bug fix —
`writeMeasureList(...)`:

| Upstream (2.3.9 line 167-176)                | Override (line 166-175)                          |
|----------------------------------------------|--------------------------------------------------|
| writes `End PrimaryMeasure` **outside** the `if(pm != null)` block, then `End MeasureList` after | writes `End PrimaryMeasure` **inside** the `if(pm != null)` block, then `End MeasureList` |

When the DSD has no `PrimaryMeasure` (3.0 DSDs typically don't — they use
`MeasureList` with explicit measures), upstream emits an unbalanced
`</PrimaryMeasure>` close tag. Override balances the brackets correctly.

The override carries a `//TODO CONSIDER COMMITING THIS TO SDMXSOURCE`
comment on the affected line.

**Bug / limitation:** clear upstream bug. Easy upstream PR.

**Severity / blast radius:** every XML 2.1 DSD output for a 3.0 DSD (which
is the common conversion path: IMF / BIS 3.0 → client requesting 2.1 XML).

**Status against 2.4.0:** upstream class byte-identical 2.3.9 → 2.4.0;
bug still present.

**Risks of removal:** malformed XML on every cross-version DSD conversion.

**Upstream contribution candidate.** Two-line fix.

`File: sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/sdmxsource/CustomStaxDsdWriterEngineV21.java`

---

### 18. `CustomSdmxSuperBeanRetrievalManagerImpl`

**Override target:** `io.sdmx.core.sdmx.manager.structure.SdmxSuperBeanRetrievalManagerImpl`.

**Mechanism:** extends upstream class; overrides `getConceptSchemeSuperBean`
and `getDataStructureSuperBean`; adds private `safeResolveReference`.

**Wiring:** constructed inside `JsonDataWriterFactoryProducer.getDataWriterFactory(...)`
(§1) per JSON data conversion.

**Behavioural difference vs upstream:** upstream
`io.sdmx.utils.sdmx.structure.SuperBeanRefUtil.resolveReference` (the
helper both `get*SuperBean` methods route through) initialises
`latestVersion = null` and then calls
`maintVersion.isLater(latestVersion)` on the first matching bean. The
null-guard at the upstream line 53 checks `maintVersion` — but the variable
that's actually null on the first iteration is `latestVersion`. The result
is an NPE inside `SdmxVersion.compareTo(null)`.

The bug was dormant in 2.3.9; sdmx-core 2.3.21's
`SdmxJsonSeriesDataWriterV2.startDataset` newly calls
`DataStructureUtil.obtainNumericComponents(dsd, superBeanRetrieval)`, which
funnels into `getConceptSchemeSuperBean` and triggers the NPE.

Override re-implements the loop with the correct guard
(`latestVersion == null || (maintVersion != null && maintVersion.isLater(latestVersion))`).
Only the two methods reached on the failing path are overridden; the other
five `get*SuperBean(IURNSingle)` methods on the parent class are left
untouched because their sibling set-returning getters are package-private,
so reaching them from `com.epam.sdmxproxy.services.sdmxsource` would
require reflection or copy-paste.

**Bug / limitation:** clear upstream bug. Confirmed still present in 2.4.0
per `docs/sdmx-core-2.4.0-upgrade.md` §"Workaround status".

**Severity / blast radius:** every SDMX-JSON 1.0/2.0 data write through
the conversion path.

**Status against 2.4.0:** still required (upstream unchanged).

**Risks of removal:** NPE on every JSON data conversion.

**Upstream contribution candidate.** This is exactly the kind of
production-confirmed null-guard fix EPAM should push upstream.

`File: sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/sdmxsource/CustomSdmxSuperBeanRetrievalManagerImpl.java`

---

### 19. `CustomDataTransformationUtil`

**Override target:** `io.sdmx.core.data.util.DataTransformationUtil#copyData(DataReaderEngine, IFlatDataWriterEngine, boolean, boolean, boolean)`.

**Mechanism:** Lombok `@UtilityClass` static helper. Does not extend or
register with sdmx-core; replaces the upstream call at the proxy's CSV-write
call site only.

**Wiring:** `StreamingDataConversionService#processAsCsv` (line 221) calls
`CustomDataTransformationUtil.copyDataToFlatWriter(reader, writer)`. Other
call sites (`processAsXml`, etc.) still use upstream `DataTransformationUtil.copyData`.

**Behavioural difference vs upstream:** the upstream method has the
concept/code arguments swapped on the measure put (2.3.9 line 192):

```java
rowData.put(measure.getCode(), measure.getConcept());     // upstream: WRONG
rowData.put(measure.getConcept(), measure.getCode());     // proxy: correct
```

Upstream uses the obs value as the *column key* and the component id as the
*value*, which means the CSV writer fails to find a column matching
"OBS_VALUE" and silently drops every measure value. Override puts the
column id (concept) as the key and the actual value (code) as the value —
matching `CollectionUtil.keyValuesToFlatMap`'s convention for dimensions
and attributes elsewhere in the same method.

**Bug / limitation:** unambiguous upstream bug.

**Severity / blast radius:** every CSV data write that has at least one
measure with single value (i.e. every data CSV response).

**Status against 2.4.0:** upstream unchanged (we have not re-checked the
2.4.0 source, but no 2.4.0 changes were flagged in the upgrade doc for
`DataTransformationUtil`).

**Risks of removal:** OBS_VALUE column in CSV output is empty.

**Related issue:** #58 (cited in the file Javadoc).

**Upstream contribution candidate.** One-line fix.

`File: sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/sdmxsource/CustomDataTransformationUtil.java`

---

### 20. `QuotedNewlineCanonicalizingInputStream`

**Override target:** none directly. Acts as a byte-level stream filter
*before* `io.sdmx.utils.core.csv.CSVColumnReaderEngineImpl` reads CSV
input.

**Mechanism:** `InputStream` decorator. Tracks in-quote state; replaces
`\n`, `\r`, `\r\n` *inside* double-quoted CSV fields with a single space.
Honours doubled-quote escapes (`""`).

**Wiring:** `StreamingDataConversionService#getDataReader` (line 138)
wraps the input stream with this when `sourceFormat` is
`CSV_DATA_2_0_0` / `CSV_DATA_1_0_0`.

**Behavioural difference vs upstream:** upstream's
`CSVColumnReaderEngineImpl.moveNextRow` pulls one physical line at a time
via `BufferedReader.readLine()` and validates cell count against the
header. RFC 4180 allows quoted fields to span multiple physical lines;
upstream rejects them with `validateRowSize` errors. The filter normalises
the input so every record sits on a single physical line.

**Bug / limitation:** upstream CSV reader does not implement RFC 4180
multi-line quoted fields.

**Severity / blast radius:** every SDMX-CSV input. The trigger is any
attribute / measure value with an embedded newline; IMF's `FULL_DESCRIPTION`
attribute is the documented offender.

**Status against 2.4.0:** upstream unchanged.

**Risks of removal:** CSV reads break on IMF (and likely on any registry
that uses long descriptive attributes).

**Related issue:** #57 (cited in the file Javadoc).

**Upstream contribution candidate.** Best done as a fix to
`CSVColumnReaderEngineImpl` itself, not as a stream filter — but the
filter is a safe, reversible workaround in the meantime.

`File: sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/sdmxsource/QuotedNewlineCanonicalizingInputStream.java`

---

### 21. `TimeDimensionTextTypePatcher`

**Override target:** none directly. Acts on an already-parsed XmlBeans
`StructureDocument` (i.e. a sdmx-core XmlBeans output) after it is
produced.

**Mechanism:** static utility that walks an XML tree with `XmlCursor`,
finds `TimeDimension > LocalRepresentation > TextFormat`, and replaces the
`textType` attribute from `"String"` (or null) to `"ObservationalTimePeriod"`.

**Wiring:** no production-code caller currently references it. Only the
unit test `TimeDimensionTextTypePatcherTest` in
`sdmx-proxy/src/test/java/com/epam/sdmxproxy/services/sdmxsource/` exists.
Verified via `Grep` for `TimeDimensionTextTypePatcher` across the entire
repo. The file is therefore effectively **dead code in production** at the
time of writing (2026-05-19). It may have been authored as a candidate fix
for a registry-specific schema-validation issue that was solved another way.

**Behavioural difference vs upstream:** would patch malformed
`TimeDimension` text-type metadata on output. Status `unknown — needs verification`
on whether this was ever wired in production or whether the registry-side
fix made it redundant.

**Bug / limitation:** registries (which one is not documented) reporting
TimeDimension as `textType="String"` instead of `"ObservationalTimePeriod"`.
Per SDMX spec, TimeDimension's `textType` should be one of the
observational-time-period subtypes; `"String"` would let arbitrary text
through, which the downstream stack may not handle.

**Severity / blast radius:** dead code; no current blast radius.

**Status against 2.4.0:** not applicable (not used).

**Risks of removal:** none functional. Leaving it in place costs a small
maintenance tax but documents a potential workaround.

**Removal candidate.** If a follow-up confirms there is no out-of-tree
caller, this class and its test can be deleted.

`File: sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/sdmxsource/TimeDimensionTextTypePatcher.java`

---

### 22. `HierarchyMapper` (related — `common/mapping/`)

**Override target:** not an override of an sdmx-core class, but it
reflectively reaches into `io.sdmx.im.beans.codelist.HierarchicalCodeBeanImpl`'s
private `levelRef` field. Included in this catalog because it is the
direct counterpart of §9 and CLAUDE.md singles it out.

**Mechanism:** Spring-injected mapper that converts an sdmx-core
`HierarchyBean` to the proxy's jsdmx `Hierarchy` artefact. Uses reflection
to read a private field.

**Wiring:** instantiated in `StructureMapperImpl` (line 42).

**Behavioural difference vs upstream:** the sdmx-core public API exposes
`HierarchicalCodeBean.getLevel(false)` which returns the resolved `Level`
object. When a hierarchy has no formal Level objects (which IMF / BIS
hierarchies often don't — `hasFormalLevels=false`), `getLevel(false)`
returns null even though §9 successfully stored the raw level reference
string. The mutable round-trip (`getMutableInstance()` -> `CodeRefMutableBeanImpl`)
calls the same `getLevel(false)` and loses the raw reference too.

The mapper reads the private `levelRef` field of `HierarchicalCodeBeanImpl`
to recover the original level id.

**Bug / limitation:** sdmx-core IM does not expose the raw `levelRef`
string in any public method when there are no formal levels.

**Severity / blast radius:** every hierarchy with `hasFormalLevels=false`
whose hierarchical codes carry `level` annotations. This is most BIS / IMF
hierarchies in practice.

**Status against 2.4.0:** unverified; safer to assume still required.

**Risks of removal:** the `levelId` field on `HierarchicalCode` is null
in the JSON / XML 3.0 output, even though the source hierarchy carries
levels.

**Upstream contribution candidate.** Add a `getLevelReference()` /
`getRawLevelReference()` accessor on `HierarchicalCodeBean` that returns
the string regardless of whether formal levels exist.

`File: sdmx-proxy/src/main/java/com/epam/sdmxproxy/common/mapping/HierarchyMapper.java`

---

### 23. `CustomSdmxJsonDataWriterEngineV2`

**Override target:**
`io.sdmx.format.json.engine.data.writer.sdmxjson.SdmxJsonDataWriterEngineV2`.

**Mechanism:** thin subclass that overrides `startDataset` to install
`CustomSdmxJsonSeriesDataWriterV2` / `CustomSdmxJsonFlatDataWriterV2`
(§24) as the writer proxy instead of upstream's stock V2 series / flat
writers. Header writing, dimAtObs detection, and forceFlat handling are
copied verbatim from upstream (the upstream method does this work
inline; subclassing only the writer-proxy choice is not possible without
re-implementing the method).

**Wiring:** instantiated by `CustomSdmxJsonDataWriterFactory` (§8) on
the SDMX-JSON 2.0 branch.

**Behavioural difference vs upstream:** the only delta is the choice of
the inner series/flat writer. All other behaviour (the dataset
prologue, header writing, link emission) is identical.

**Bug / limitation:** upstream's `startDataset` hard-codes
`new SdmxJsonSeriesDataWriterV2(...)` / `new SdmxJsonFlatDataWriterV2(...)`
inside a `try` block — there is no extension point to plug in a
different writer without overriding the whole method.

**Severity / blast radius:** every SDMX-JSON 2.0 data write on the
conversion path.

**Status against 2.4.0:** required as long as upstream lacks the
writer-injection hook. Upstream `SdmxJsonDataWriterEngineV2` itself is
byte-identical 2.3.9 → 2.4.0 (per the upgrade doc).

**Risks of removal:** the `roles` / TIME_PERIOD fixes (§24, §25) stop
being applied — proxy regresses to issue #80 #3 and #5 behaviour.

**Related design:** 026-data-conversion-fidelity-sdmx-json-2-0 (stage 1).

**Upstream contribution candidate.** Add a protected factory method on
`SdmxJsonDataWriterEngineV2` (e.g. `createSeriesProxy(...)` /
`createFlatProxy(...)`) so subclasses can override only that. Would
delete this entire shim.

`File: sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/sdmxsource/CustomSdmxJsonDataWriterEngineV2.java`

---

### 24. `CustomSdmxJsonSeriesDataWriterV2` and `CustomSdmxJsonFlatDataWriterV2`

**Override target:** `io.sdmx.format.json.engine.data.writer.sdmxjson.SdmxJsonSeriesDataWriterV2`
and `SdmxJsonFlatDataWriterV2`.

**Mechanism:** two thin subclasses, one per writer mode. Each overrides
the protected `writeComponent(ComponentSuperBean, int)` and
`writeCode(String, boolean, ComponentSuperBean)` and delegates to the
shared static helper in `SdmxJsonV2WriterOverrides` (§25). The
subclasses are necessary because the two upstream writers don't share a
common ancestor that exposes both methods through the same hierarchy in
a way that can be patched once — `writeComponent` / `writeCode` live on
`AbstractJsonDataWriter`, but to take effect on SDMX-JSON 2.0 output the
custom logic has to be present in *both* the series and flat V2 writers
that `CustomSdmxJsonDataWriterEngineV2` (§23) instantiates.

**Wiring:** §23's overridden `startDataset` chooses between the two
based on `forceFlat` / `dimensionAtObservation == AllDimensions`.

**Behavioural difference vs upstream:** delegated to §25. Direct
methods on these classes (constructor, override stubs) carry no logic.

**Bug / limitation:** dual override required because upstream's
component / code writing methods exist on `AbstractJsonDataWriter` and
must be overridden in every concrete subclass that we instantiate.

**Severity / blast radius:** every SDMX-JSON 2.0 data write.

**Status against 2.4.0:** behaviour delta is on `AbstractJsonDataWriter`
(unchanged 2.3.9 → 2.4.0); shims still apply.

**Risks of removal:** §25 has no effect because the upstream classes
are instantiated instead.

**Related design:** 026-data-conversion-fidelity-sdmx-json-2-0 (stage 1).

`Files:`
- `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/sdmxsource/CustomSdmxJsonSeriesDataWriterV2.java`
- `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/sdmxsource/CustomSdmxJsonFlatDataWriterV2.java`

---

### 25. `SdmxJsonV2WriterOverrides`

**Override target:**
`io.sdmx.format.json.engine.data.writer.fusionjson.AbstractJsonDataWriter`
— specifically the protected `writeComponent` and `writeCode` methods.
Not a subclass; a `@UtilityClass` invoked from §24's two subclasses.

**Mechanism:** static helper that re-implements `writeComponent` and
`writeCode` to match the SDMX-JSON 2.0 data schema. Mirrors the
structure of the upstream methods (line-for-line where unchanged) so
future sdmx-core upgrades can be diffed and ported.

**Wiring:** called from `CustomSdmxJsonSeriesDataWriterV2` and
`CustomSdmxJsonFlatDataWriterV2` (§24) via static dispatch.

**Behavioural difference vs upstream:** two fixes:

1. **`roles` plural array instead of `role` singular field (issue
   #80 #3).** Upstream hardcodes
   `writeStringField("role", "time")` for time dimensions and
   `writeNullField("role")` for everything else, and never reads the
   component's `conceptRoles`. The SDMX-JSON 2.0 data schema
   (`sdmx-json-2.0.0/.../sdmx-json-data-schema.json:456,489,524`)
   defines the field as `roles` (plural array of strings matching
   `^[A-Za-z][A-Za-z0-9_-]*$`). Override emits `roles` as a JSON array
   containing `"time"` (for time dimensions) plus the
   `FullIdentifiableId` of every concept-role cross-reference on the
   bean (`DimensionBean.getConceptRole()` /
   `AttributeBean.getConceptRoles()`). Empty arrays are omitted entirely
   to match upstream IMF emission (only FREQUENCY and the time dimension
   carry roles in practice).
2. **Non-coded TIME_PERIOD value shape (issue #80 #5).** Upstream's
   `writeCode` always emits the coded shape
   `{"id","name","start","end"}` for any component whose id equals
   `TIME_PERIOD`, fabricating `start` / `end` bounds via
   `DateUtil.formatDate`. The SDMX-JSON 2.0 data schema
   (`sdmx-json-data-schema.json:651`) allows two shapes — coded
   (enumerated representation) and non-coded
   `ObservationalTimePeriod` (`{"value":"1999"}`). The IMF WEO DSD
   declares TIME_PERIOD with a text representation, so the non-coded
   shape applies; upstream's fabricated bounds are wrong there.
   Override detects `component.getCodelist(false) != null` to pick
   the coded vs non-coded branch.

**Bug / limitation:** clear upstream spec-compliance bugs.

**Severity / blast radius:** every SDMX-JSON 2.0 data response. Issue
#80 numbers (proxy emitted `role`/null 67 times vs upstream `roles` 27
times on IMF WEO) reflect the steady-state miss before the override.

**Status against 2.4.0:** upstream's `AbstractJsonDataWriter` is
unchanged 2.3.9 → 2.4.0; override still required.

**Risks of removal:** schema-noncompliant JSON, semantically incorrect
TIME_PERIOD values, and loss of `FREQ` and other concept-role
annotations on every component.

**Related design:** 026-data-conversion-fidelity-sdmx-json-2-0 (stage 1).

**Upstream contribution candidate.** `roles` and TIME_PERIOD `value`
shape are both 5-10 line spec-compliance fixes; the rewrite of
`writeComponent` / `writeCode` to read conceptRoles and codelist
presence would benefit every sdmx-core SDMX-JSON 2.0 consumer.

`File: sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/sdmxsource/SdmxJsonV2WriterOverrides.java`

---

### 26. `DataStructureMapper` MSD + conceptRoles (related — `common/mapping/`)

**Override target:** not an override of an sdmx-core class — fills gaps
in the proxy's own `DataStructureBean -> jsdmx DataStructureDefinition`
mapper that previously dropped fields sdmx-core preserves. Included in
this catalog because it is the direct counterpart of §10 (the structure
reader-side fix) and because design 025 introduced these mapping calls
in the same change.

**Mechanism:** plain mapper additions — three new lines plus two helper
methods (`mapDimensionConceptRoles`, `mapAttributeConceptRoles`). No
reflection, no overrides; uses the existing `ReferenceMapper` and the
sdmx-core bean accessors.

**Wiring:** the mapper itself is injected into `StructureMapperImpl`
(unchanged); the additions are part of its `map(DataStructureBean)`
implementation.

**Behavioural difference vs design 025 baseline:**

1. **DSD `metadata` URN preserved (issue #79 #3).** When
   `DataStructureBean.getMSDRef()` is non-null,
   `dsd.setMetadataStructure(referenceMapper.mapMaintainable(..., METADATA_STRUCTURE))`
   is now called. Without this, the
   `metadata: urn:sdmx:org.sdmx.infomodel.metadatastructure.MetadataStructure=...`
   link from the SDMX-JSON 2.0 source survived parsing into SdmxBeans
   but was dropped on the SdmxBeans -> jsdmx hop.
2. **Dimension `conceptRoles` preserved (issue #79 #4).** New
   `mapDimensionConceptRoles` helper reads
   `DimensionBean.getConceptRole()` (singular method, plural meaning)
   and emits the resolved `IDirectCrossReferenceBean<ConceptBean>` list
   as cross-references on the jsdmx `DimensionImpl.conceptRoles`. Wired
   into `mapDimension` after the existing `mapComponent` call.
3. **Attribute `conceptRoles` preserved (issue #79 #4, latent).** New
   `mapAttributeConceptRoles` helper reads
   `AttributeBean.getConceptRoles()` (plural). The IMF WEO sample has
   empty conceptRoles on every attribute, so this branch is exercised
   only by registries that populate them — but the fix is registry
   agnostic.

**Bug / limitation:** the proxy mapper was incomplete. sdmx-core's
SDMX-JSON 2.0 reader correctly populated all three fields on the
SdmxBeans side; the proxy just never read them back out. This is a
proxy bug, not an upstream bug.

**Severity / blast radius:** every SDMX-JSON 2.0 structure response
containing a DSD that references an MSD or that has concept-role
annotations on dimensions or attributes. IMF 3.0 DSDs hit both.

**Status against 2.4.0:** not applicable (this is the proxy's own
mapper).

**Risks of removal:** the FREQUENCY dimension's `conceptRoles:
["urn:.../FREQ"]` and the DSD-level `metadata` URN disappear from every
structure response again. The IMF 3.0 structure endpoint regresses
visibly.

**Related design:** 025-dsd-conversion-fidelity (stage 1).

`File: sdmx-proxy/src/main/java/com/epam/sdmxproxy/common/mapping/DataStructureMapper.java`

---

### 27. `AnnotationValueToTextJsonFixture` (related — `services/fixture/structure/`)

**Override target:** not an override of an sdmx-core class. Acts on
the raw upstream JSON byte stream *before* sdmx-core's annotation
reader sees it, similar in spirit to §20 (`QuotedNewlineCanonicalizingInputStream`)
but tree-aware (Jackson) rather than stream-level.

**Mechanism:** structure fixture (`StructureFixture` interface) that
parses the raw JSON into a `JsonNode` tree, recursively walks every
`annotations` array, and for each annotation that carries a `value`
field but neither `text` nor `texts`, renames `value` -> `text`.
Annotations with `text` / `texts` already present are left untouched.
Re-serialised back to a fresh `InputStream`.

**Wiring:** registered as Spring `@Component` with
`StructureFixtureType.ANNOTATION_VALUE_TO_TEXT`. Enabled in
`sdmx_registries_config.json` for IMF SDMX 3.0 structure endpoints
(after `PRESERVE_METADATA_ATTRIBUTE_USAGES`,
`METADATA_ATTRIBUTE_USAGE_TO_ATTRIBUTE`).

**Behavioural difference vs no-fixture baseline:** the rewriting
sidesteps the sdmx-core bug at
`io.sdmx.format.json.engine.structure.reader.util.SdmxJsonAnnotableUtil.buildAnnotation`,
which handles `title` / `id` / `type` / `links` / `texts` / `text` but
silently ignores `value`. The downstream `AnnotationBean` API has no
`getValue()` either, so the field is unrepresentable in sdmx-core.
Rewriting at the JSON level converts the non-localised string into a
shape the existing reader handles — preserving the data through the
roundtrip and re-emitting it as `text` in the output.

**Trade-off:** the wire field name shifts (`value` -> `text`). Per the
SDMX-JSON 2.0 structure schema, `text` is typed as
`localisedBestMatchText` (a plain string), so the substitution is
type-correct; the only semantic shift is from "explicitly non-localised"
to "localised default". Documented in the fixture javadoc.

**Bug / limitation:** sdmx-core's annotation reader is missing a field
that the SDMX-JSON 2.0 spec defines. Could be fixed upstream by adding
the `value` case to `buildAnnotation` *and* by adding a `getValue()`
accessor to `AnnotationBean`. Both are invasive; the JSON-level fixture
avoids touching sdmx-core entirely.

**Severity / blast radius:** every SDMX-JSON 2.0 structure response
where any annotation uses `value`. IMF DSDs carry `value` annotations
on the DSD itself (e.g. `{"id":"origin","value":"INTEGRATION"}`) and
the field was completely dropped before the fixture.

**Status against 2.4.0:** still required (upstream annotation reader
unchanged).

**Risks of removal:** annotation `value` content disappears from all
SDMX-JSON 2.0 structure responses again.

**Related design:** 025-dsd-conversion-fidelity (stage 2 step 1).

**Upstream contribution candidate.** Add `value` to the annotation
reader and a `getValue()` accessor to `AnnotationBean`. Bigger upstream
change than most catalog entries; the JSON-level workaround is a
reasonable indefinite-term solution.

`File: sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/fixture/structure/AnnotationValueToTextJsonFixture.java`

---

### 28. `MetadataAttributeUsagePreserver` (related — `services/fixture/structure/`)

**Override target:** not an override of an sdmx-core class; bypasses
sdmx-core's bean model gap entirely. The closest analog in this catalog
is §22 (`HierarchyMapper`), which uses reflection to recover a private
field; this preserver instead caches the raw JSON wire bytes and
re-injects them after the conversion has finished.

**Mechanism:** Spring `@Service` with two operations, used by
`AdapterRouterImpl.getStructuresConversion`:

1. **`capture(byte[] rawJson) -> Map<String, JsonNode>`** — parses the
   raw upstream JSON, walks `data.dataStructures[]`, and for each DSD
   stashes a deep copy of its `dataStructureComponents.attributeList.metadataAttributeUsages`
   array (if any) keyed by `agencyID|id|version`.
2. **`reinject(byte[] convertedJson, Map<String, JsonNode>) -> byte[]`** —
   parses the proxy's own converted JSON output, finds each DSD by the
   same composite key, and writes the cached `metadataAttributeUsages`
   array back onto the DSD's attribute list (creating the
   `attributeList` object if absent). Other fields are untouched.

`AdapterRouterImpl.getStructuresConversion` orchestrates the round
trip: it gates on
`isEnabled(StructureFixtureType.PRESERVE_METADATA_ATTRIBUTE_USAGES)`,
captures from the upstream bytes before fixtures mutate them, runs the
normal fixture chain + sdmx-core conversion, then calls `reinject` on
the converted output. Both calls are best-effort — parse failures log a
warning and the conversion is returned unmodified.

**Wiring:** new enum value `StructureFixtureType.PRESERVE_METADATA_ATTRIBUTE_USAGES`
(toggle, not a fixture-chain entry — the preserver runs in the router,
not via `StructureFixtureService.applyFixtures`). Enabled in
`sdmx_registries_config.json` for IMF SDMX 3.0 structure endpoint, listed
*before* `METADATA_ATTRIBUTE_USAGE_TO_ATTRIBUTE` so it captures the
unmutated usages.

**Behavioural difference vs no-preserver baseline:** sdmx-core's
SDMX-JSON 2.0 DSD reader does not read `metadataAttributeUsages` at all
(`SdmxJsonDataStructureReaderEngineV2.getAttributes` handles only
`attributes`), and even if it did, `AttributeListMutableBean` /
`AttributeListBean` have no slot for them on the DSD side — metadata
attribute usages are modelled on the MSD side of the IM. The existing
`METADATA_ATTRIBUTE_USAGE_TO_ATTRIBUTE` fixture papers over this by
rewriting usages into regular attributes when the referenced MSD is
inlined in the same response, but a bare
`GET /structure/datastructure/IMF.RES/DSD_WEO/9.0.0` returns the DSD
alone, so the fixture finds no MSD and skips the DSD entirely. The
preserver captures the wire bytes before that happens and re-attaches
the `metadataAttributeUsages` array verbatim on the way out.

**Bug / limitation:** double gap — sdmx-core's reader skips the field
*and* the bean model has no slot for it. Neither can be fixed cleanly
in the proxy short of (a) overriding the reader to carry a wrapper
bean (large) or (b) fetching the MSD as a side request to populate the
fixture path (registry round-trips, caching, error handling — own
design). The preserver is the only approach that doesn't touch
sdmx-core internals.

**Severity / blast radius:** every SDMX-JSON 2.0 structure response
whose DSD carries `metadataAttributeUsages`. IMF 3.0 DSDs use them
heavily (33 usages on `IMF.RES:DSD_WEO(9.0.0)`).

**Status against 2.4.0:** still required; upstream reader and bean
model unchanged.

**Risks of removal:** `metadataAttributeUsages: []` (empty) on every
IMF 3.0 DSD response.

**Trade-offs vs alternative designs (per design 025 §"Solution",
"Group B"):**

- The "fetch MSD side-request" path was rejected as a separate, larger
  design (registry round-trips, caching, partial-failure handling).
- The "extend the mutable bean" path was rejected as too invasive
  (touching sdmx-core's internal `AttributeListMutableBeanImpl`).
- The cache-and-re-inject path was chosen because it stays inside
  `sdmx-proxy/` source with no sdmx-core overrides, no bean wrappers,
  no reflection. Cost is one extra parse + serialize of the structure
  response when the toggle is enabled.

**Related design:** 025-dsd-conversion-fidelity (stage 2 step 2).

**Upstream contribution candidate.** Would require both an IM model
change (add `metadataAttributeUsages` to `AttributeListBean`) and a
reader update. Substantial; out of scope for a drive-by fix.

`File: sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/fixture/structure/MetadataAttributeUsagePreserver.java`

---

### 29. `JsonDataV20SeriesLimitTruncator` path-aware match (related — `services/limit/truncate/`)

**Override target:** not an override of an sdmx-core class. Streaming JSON
filter that caps an SDMX-JSON 2.0 data response at the requested series
count for the proxy-side limit-emulation path (design 014). Listed in
this catalog because (a) it is kin to §27 / §28 — a Jackson-level filter
that compensates for behaviour the proxy can't change inside sdmx-core, and
(b) its first revision triggered a deep sdmx-core writer fault that is
worth documenting here.

**Mechanism:** Spring `@Component`. Streams the registry response through
a Jackson `JsonParser` / `JsonGenerator` pair on a virtual thread, copying
tokens through unchanged except inside the data series container, where
the first `n` entries are emitted and the rest are skipped via
`parser.skipChildren()`. A small `Deque<String>` records the field name
that introduced each enclosing container — used to disambiguate the data
series from other `series`-named JSON fields.

**Wiring:** `SeriesLimitTruncatorProvider` selects this implementation for
`ReturnFormat.JSON_DATA_2_0_0`; `AdapterRouterImpl.resolveRawDataStream`
calls `truncator.truncate(rawData, n, sdmxBeans)` after the bisect /
shrunk-query stage produces a registry response.

**Behavioural difference vs no-truncator baseline:** two corrections layered
on the original component, both shipped during this session:

1. **`series` as JSON object handled.** The original implementation only
   matched `series` as a `START_ARRAY`. SDMX-JSON 2.0 emits the data series
   as a JSON object keyed by dimension positions (`"0:0:0"`, `"0:1:0"`,
   ...), so the array branch never fired and the truncator was a no-op on
   every real registry response. The sibling `JsonDataV10SeriesLimitTruncator`
   handled the object form correctly via `copySeriesMap`; v2.0 just inherited
   a copy-paste asymmetry. Symmetric `copySeriesObject` helper added and
   wired from the `START_OBJECT` case. (Design 028 §"Solution".)
2. **Path-aware match.** The first revision matched `lastField == "series"`
   anywhere in the tree. SDMX-JSON 2.0 carries a *second* `series`-named
   field at `data.structures[*].attributes.series` — a JSON array of
   series-level attribute *definitions*, not the data series. Once the
   data series counter had saturated, the truncator emptied that array
   too, corrupting the DSD-derived attribute table the writer relies on.
   Downstream effect: sdmx-core's `GroupDataWriterEngine` flushes buffered
   series on close and hands each one to
   `GroupAttributeValues.getAttributes(Keyable)`, which dereferences the
   group dimension positions via `series.getShortCode().split(":")`. With
   the definitions array emptied, the writer's internal state ends up
   with a length-0 `shortCodeSplit`, and the next `shortCodeSplit[i] = ""`
   throws `ArrayIndexOutOfBoundsException`. The exception surfaces from
   `GroupDataWriterEngine.close` → `flushSeriesAndAddGroups` →
   `DataTransformationUtil.copyData` and aborts the conversion with HTTP
   500. The fix tracks the enclosing-container chain in a `Deque<String>`
   (`ARRAY_ELEMENT` sentinel for anonymous array elements, the introducing
   field name otherwise) and gates truncation on
   `isInsideDataSetsElement(stack)` — i.e. the immediate parent is an
   array element of `dataSets`. Any other location for `series` flows
   through untouched. (Design 028 §"Path-aware match".)

**Bug / limitation:** (1) was an internal proxy bug; (2) is also an
internal proxy bug, but it exposed how brittle sdmx-core's
`GroupDataWriterEngine` is when fed a structure response with truncated
attribute metadata. See "Related sdmx-core finding" below.

**Severity / blast radius:** the limit-emulation path for every
SDMX-JSON 2.0 data response on a registry that doesn't honour native
`limit`. IMF WEO is the documented offender; before the path-aware fix,
`testLimitEmulationStrict(registryReturnFormat=JSON_DATA_2_0_0)` failed
with HTTP 500.

**Status against 2.4.0:** not applicable (proxy-side filter).

**Risks of removal:** the limit-emulation path stops capping series for
SDMX-JSON 2.0 (the failing test in design 028's "Verification" returns
12 series for `limit=10`).

**Related sdmx-core finding:** `GroupAttributeValues.getAttributes(Keyable)`
assumes `series.getShortCode().split(":")` has at least as many entries
as the DSD has dimensions. Empty / truncated keys trip
`ArrayIndexOutOfBoundsException` inside the writer's closing pass, surfacing
as `Failed to convert data` 500 from
`AdapterRouterImpl.lambda$getData$1`. The proxy avoids this entirely by
not corrupting `data.structures[*].attributes` in the first place, but
the writer would benefit from a defensive length check upstream.

**Related design:** 028-json-data-v20-truncator-series-object.

**Upstream contribution candidate.** None for the truncator (proxy-side
only). For sdmx-core: a length-guard in
`GroupAttributeValues.getAttributes` that returns null rather than
throwing on an undersized shortCode would convert silent breakage into a
recoverable miss, but the real fix is "don't feed truncated metadata to
the writer."

`File: sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/limit/truncate/JsonDataV20SeriesLimitTruncator.java`

---

### 30. `DimensionServiceImpl` wildcard version resolution (related — `services/misc/`)

**Override target:** not an override of an sdmx-core class. A proxy-side
service that resolves a DSD from a dataflow reference. Listed here because
the original implementation used a literal `.equals()` on
`dsdBean.getVersion().toString()`, which breaks against SDMX 3.0 wildcard
versions in the dataflow's `structure` URN, and the fix uses jsdmx
(`com.epam.jsdmx.infomodel.sdmx30.VersionReference` /
`WildcardReferenceMatcher`) to resolve correctly. Sister entry to §22 and
§26 — a proxy-side gap, not an upstream bug, but co-located with the
sdmx-core-shaped concerns in this catalog because callers reach it on the
SDMX 3.0 data emulation path.

**Mechanism:** Spring `@Service`. Two public methods (`getDimensionIds`,
`getTimeDimensionId`) and one previously-public method
(`getDimensionIdsFromDsd`) now route through a shared
`resolveDsd(beans, agency, id, versionRef)` helper.

**Wiring:** `LimitEmulationServiceImpl.getShrunkQuery` (line 170) calls
`getTimeDimensionId` during the bisect / shrunk-query stage of the
limit-emulation path.

**Behavioural difference vs prior proxy implementation:**

1. **Wildcard-aware lookup.** When the dataflow's structure URN carries
   a wildcard version (e.g.
   `urn:sdmx:org.sdmx.infomodel.datastructure.DataStructure=IMF.RES:DSD_WEO(9.0+.0)`,
   per SDMX 3.0 spec "any 9.0.x patch"), the helper parses the requested
   version as a `VersionReference`. If `isSpecific()`, an exact
   `VersionReference`-level equals is required; if wildcarded, a
   `WildcardReferenceMatcher` filters DSDs whose own version matches the
   wildcard scope and `VersionReference.getComparator()` selects the
   *latest* match. Unparseable versions (e.g. legacy two-part `1.0`) fall
   back to the original literal string equality, keeping the previously
   working path intact.
2. **Both lookup sites consolidated.** `getDsdFromDataflow` (used by
   `getTimeDimensionId`) and `getDimensionIdsFromDsd` (used by
   `getDimensionIds`) previously each did their own strict equals; both
   now call `resolveDsd`.

**Bug / limitation:** the proxy's own lookup did not honour SDMX 3.0
version-wildcard semantics. IMF emits the WEO dataflow with a
minor-wildcarded DSD reference; the matching DSD in the same
`references=descendants` payload carries a concrete version. Literal
equals never matched, so `LimitEmulationServiceImpl.getShrunkQuery` blew
up with `IllegalArgumentException: DataStructure not found: DSD_WEO`
inside the streamed-response lambda, surfacing as HTTP 500 on the
emulation path. (The same broken equals shipped in the proxy from before
SDMX 3.0 wildcard versions appeared in real registry responses; this
session is the first time a test exercised it end-to-end.)

**Severity / blast radius:** every SDMX 3.0 data emulation request whose
dataflow's `structure` URN carries a wildcard version. IMF WEO is the
documented offender. No effect on the native-`limit` (non-emulation) path
because `getTimeDimensionId` is not on that codepath.

**Status against 2.4.0:** not applicable (proxy-side service). jsdmx's
`VersionReference` / `WildcardReferenceMatcher` are public API; no
sdmx-core monkey-patching required.

**Risks of removal:** `IllegalArgumentException: DataStructure not found`
returns for any SDMX 3.0 dataflow with a wildcard `structure` URN on the
emulation path.

**Related findings:**

- The proxy's existing `VERSION_WILDCARD` fixture
  (`sdmx-proxy/.../services/fixture/structure/VersionWildcardJsonFixture.java`)
  normalises *invalid* wildcard URNs (trailing non-zero after a
  wildcarded part: `(1.5+.1)` -> `(1.5+.0)`) but does not help here —
  IMF's URN is already in valid wildcard form.
- jsdmx ships `WildcardReferenceMatcher` + `VersionReference.getComparator()`
  as a complete implementation of the SDMX 3.0 version-management rules.
  Any future proxy code that compares versions across artefact references
  should route through these rather than `String.equals`.

**Upstream contribution candidate.** None — jsdmx already provides the
matcher. The candidate is a *proxy-side hygiene rule*: scan for
`getVersion().toString().equals(...)` patterns elsewhere in the codebase
that may have the same latent bug. (Not the subject of this design.)

`File: sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/misc/DimensionServiceImpl.java`

---

## Cross-cutting analysis

### By category

| Category                           | Count | Files |
|------------------------------------|-------|-------|
| Wiring / factory glue              | 8     | §0 (config), §1, §2, §3, §4, §5, §6, §7, §8 |
| Reader engines (parsers)           | 6     | §9, §10, §11, §12, §13, §14 |
| Writer engines (XML 2.1 serializers) | 3   | §15, §16, §17 |
| Writer engines (JSON 2.0 data serializers) | 3 | §23, §24, §25 |
| Mapper additions (proxy-side mapping fixes) | 2 | §22, §26 |
| JSON-level fixtures and stream filters (raw-stream rewrites / truncations) | 3 | §27, §28, §29 |
| Proxy-side service fixes (model gaps not in sdmx-core)                     | 1 | §30 |
| Other (super-bean retrieval / data transform / stream filter / dead code) | 4 | §18, §19, §20, §21 |

### By upstream module touched

| Upstream module                                         | Overrides |
|---------------------------------------------------------|-----------|
| `fusion-sdmx-json` (`io.sdmx.format.json.*`)            | §5, §7, §8, §9, §10, §11, §12, §13, §23, §24, §25 |
| `fusion-sdmx-csv` (`io.sdmx.format.csv.*`)              | §6, §14 |
| `fusion-sdmx-ml` (`io.sdmx.format.ml.*`)                | §4, §15, §16, §17 |
| `fusion-core-data` (`io.sdmx.core.data.util.*`)         | §19 |
| `fusion-core-sdmx` (`io.sdmx.core.sdmx.manager.structure.*`) | §18 |
| `fusion-sdmx-im` (`io.sdmx.im.beans.*`)                 | §22 (reflection) |
| `fusion-utils-core` (`io.sdmx.utils.core.csv.*`)        | §20 (stream-level workaround) |
| (proxy-side mapper, no upstream class)                  | §26 |
| (raw-stream fixture, no upstream class)                 | §27, §28 |
| (raw-stream limit-emulation truncator, no upstream class) | §29 |
| (proxy-side service using jsdmx wildcard matcher, no upstream class) | §30 |

`fusion-sdmx-json` dominates by a wide margin — 11 of 28 files. The
JSON V2 data + structure parsing **and** writing surface is by far the
most patched area; the design 025 / 026 additions (§23–§28) extend the
JSON V2 surface from "reader-only patched" to "reader-and-writer
patched", and add two raw-JSON fixtures that sidestep sdmx-core's bean
model gaps entirely.

### By bug class

| Class                                                                                   | Examples |
|-----------------------------------------------------------------------------------------|----------|
| Upstream spec-violations (clear bug in upstream vs SDMX spec)                           | §9 (`level` as URN not idType), §10 (`metadataAttributeUsages` not skipped, `none` not handled), §11 (inline attribute values rejected, empty-series leakage), §14 (`labels=both` headers), §17 (`</PrimaryMeasure>` unbalanced), §18 (NPE in `SuperBeanRefUtil.resolveReference`), §19 (concept/code swap), §20 (RFC 4180 multi-line quoted), §25 (`role` vs `roles`, fabricated TIME_PERIOD bounds), §27 (annotation `value` dropped) |
| Silent data corruption (wrong output, no error)                                         | §11 (empty-series leakage misroutes observations to wrong INDICATOR — issue #80 #1), §19 (CSV measure dropped — issue #58) |
| Missing upstream extension points (subclass-hostile API)                                | §1, §5, §6, §7, §8, §12, §13, §15, §23, §24 |
| Workarounds for upstream singleton state (concurrency-unsafe)                           | §1, §8 |
| Workarounds for IM private-field accessibility                                          | §22 |
| Bean-model gaps (field exists on the wire but not in sdmx-core's IM)                    | §27 (annotation `value`), §28 (DSD `metadataAttributeUsages`) |
| Proxy-side mapping fixes (not upstream bugs — gaps in the proxy's own mapper)           | §22 (level reference via reflection), §26 (MSD URN and conceptRoles never read) |
| Proxy-side stream filters with path / shape pitfalls                                    | §29 (path-collision on `series` between `data.dataSets[*]` and `data.structures[*].attributes`) |
| Proxy-side equality bugs against SDMX 3.0 wildcard semantics                            | §30 (strict `.equals()` on version string vs SDMX 3.0 wildcards like `9.0+.0`) |
| sdmx-core writer brittleness exposed but not patched                                    | §29 (`GroupAttributeValues.getAttributes` AIOOBE on undersized shortCode — surfaces as HTTP 500 from `GroupDataWriterEngine.close`) |
| Format / version-drift (proxy ahead of or behind upstream)                              | §11 (intentionally-missing not mirrored), §15 (HIERARCHICAL_CODELIST not mirrored) |
| Dead code                                                                               | §21 |

### Candidates for upstream contribution

Ranked by maintenance benefit (most worthwhile first):

1. **§11 — empty-series leakage in `lazyLoadKey`.** Silent data corruption;
   one extra `END_OBJECT` branch in the loop fixes it. The most severe
   upstream bug currently patched in the proxy (issue #80 #1).
2. **§18 — `SuperBeanRefUtil.resolveReference` NPE.** Confirmed-still-present in
   2.4.0; trivial null-guard fix. EPAM owns the fork; a one-line patch would
   delete this entire class from the proxy.
3. **§17 — `StaxDsdWriterEngineV21` measure-list closing tag.** Two-line fix
   producing valid XML. Would delete §15 / §16 too (the only reason they
   exist is to inject this engine).
4. **§19 — `DataTransformationUtil.copyData` concept/code swap.** One-line fix.
5. **§14 — CSV `labels=both` header parsing.** Four-line spec-compliance fix.
6. **§25 — `roles` plural and non-coded TIME_PERIOD.** Two SDMX-JSON 2.0
   spec-compliance fixes in `AbstractJsonDataWriter.writeComponent` /
   `writeCode`. Would delete §23 / §24 / §25 as a unit (the three classes
   only exist to host the override).
7. **§9 — Hierarchical-code `level` as idType.** Per-spec fix; matches what
   our override does.
8. **§10 — `metadataAttributeUsages` skip and `attributeRelationship.none`
   support.** Spec-compliance fixes; clean two-method change upstream.
9. **§13 / §12 — widen visibility of `SdmxStructureIterator`'s inner classes.**
   Visibility-only change; would let us delete two copy-paste classes.
10. **§23 — add `createSeriesProxy` / `createFlatProxy` factory hooks on
    `SdmxJsonDataWriterEngineV2`.** Subclass-friendliness; would delete §23
    (but §24 and §25 still useful as long as the override exists).
11. **§22 — `HierarchicalCodeBean.getLevelReference()` accessor.** API
    addition; would let us drop reflection.
12. **§27 — add `value` to `SdmxJsonAnnotableUtil.buildAnnotation` and
    expose `getValue()` on `AnnotationBean`.** Adds a field that the
    SDMX-JSON 2.0 spec defines but sdmx-core ignores end-to-end. Removes
    the need for the JSON-level fixture (issue #79 #1).
13. **§28 — model `metadataAttributeUsages` on the DSD attribute list
    bean.** Bigger IM change; reader update plus a new model slot.
    Removes the cache-and-re-inject workaround (issue #79 #2). Largest
    upstream surface of the contribution candidates.
14. **§20 — RFC 4180 multi-line quoted fields in `CSVColumnReaderEngineImpl`.**
    Bigger change but worth doing right.
15. **§29 (sdmx-core side) — defensive length check in
    `GroupAttributeValues.getAttributes`.** A null / undersized shortCode
    should fail gracefully (return null and let the writer omit group
    attributes for that series) rather than throwing
    `ArrayIndexOutOfBoundsException` mid-flush. Two-line guard. Not a
    full fix for the upstream brittleness, but converts a 500 into a
    recoverable miss.

Of these, §11, §18, §17, and §19 are the highest-leverage: each is small,
clearly correct, and confirmed present in 2.4.0 production. §11 is the
only one that prevents silent data corruption (not just schema noise or
empty-output failures), so it tops the list.

### Candidates for removal (flag for verification, do not act on this)

- **§21 — `TimeDimensionTextTypePatcher`.** No production caller found.
  Confirm with a fresh `Grep` and the test author before deleting.
- **§15 — `CustomStaxAbstractStructureWriterEngineV21` simplification.** Now
  that `ItemValidityPeriodHelper` no longer exists upstream (2.3.21+), the
  *only* remaining reason this class exists is to swap the DSD writer (§17).
  If §17 is fixed upstream, §15 and §16 can be deleted in the same change.
- **§11 — Forward-port `IntentionallyMissingKeyValue` from upstream 2.4.0?**
  Flagged in the 2.4.0 upgrade doc. Decision needed: do we want SDMX-3.0
  intentionally-missing sentinels to flow through `IntentionallyMissingKeyValue`
  instead of plain string `KeyValueImpl`? Downstream impact analysis required.

### Most fragile (highest risk of silently breaking on next upstream bump)

1. **§11 — `CustomSdmxJsonDataReaderEngineV2`.** ~900 lines of copy-pasted
   upstream logic. Upstream changed it in 2.4.0 (new
   `IntentionallyMissingKeyValue` paths, negative-index tolerance) and we
   did not mirror it. The next time someone runs E2E against a registry that
   exercises those code paths, this is the first place to look. The
   empty-series fix (issue #80 #1) also sits in this file and is sensitive
   to changes in how `JsonReader` tracks stack items — a refactor of
   `JsonStackItem.getFieldName()` semantics would silently disable the
   fix.
2. **§25 — `SdmxJsonV2WriterOverrides`.** Mirrors the structure of
   upstream's `writeComponent` / `writeCode` line-for-line. Any sdmx-core
   change to the `AbstractJsonDataWriter` component / code emission path
   (e.g. additional schema fields, restructured value handling) needs to
   be ported here. The override carries a comment pointing at the
   upstream version it was written against.
3. **§13 / §12 — iterator copies.** Less risky because semantically
   identical, but any upstream change to `SdmxStructureIterator` won't
   propagate.
4. **§22 — reflective field access on `HierarchicalCodeBeanImpl.levelRef`.**
   A rename or refactor upstream silently nulls out all level references in
   converted hierarchies. The mapper catches the `NoSuchFieldException` and
   continues, so the failure is *silent*.
5. **§28 — `MetadataAttributeUsagePreserver` JSON pointer paths.**
   Hard-codes the `data.dataStructures[].dataStructureComponents.attributeList.metadataAttributeUsages`
   path. If sdmx-core ever starts emitting a different shape (e.g.
   moves usages to a different container), the preserver silently
   captures nothing and the field disappears again.
6. **§15 — copy-pasted abstract structure writer.** ~300 lines copied;
   anything upstream changes (e.g. the 2.4.0 HIERARCHICAL_CODELIST addition,
   already flagged) is missed.
7. **§29 — path-aware match in the v2.0 limit truncator.** The `ARRAY_ELEMENT`
   sentinel + `isInsideDataSetsElement` check assumes the wire shape stays
   `data.dataSets[*].series` and that the data series is the only `series`
   inside an `dataSets` array element. Future SDMX-JSON 2.0 schema additions
   that move the data series or introduce a third `series`-named field
   would need the predicate updated in lock-step. Failing to do so either
   passes the data series through untruncated (the first-revision bug) or
   clobbers something else (the second-revision bug). Both have failure
   modes that are visible only on the limit-emulation path.

### Maintenance burden

Three recurring patterns suggest a thin abstraction layer would pay off:

- **Singleton-with-baked-in-managers.** Five of our wiring files (§1, §6,
  §7, §8, §18) exist to escape upstream's `getInstance()` + `SingletonStore`
  pattern so that per-request managers can be supplied. A thin
  `RequestScopedSdmxRuntime` that wraps the `BeanRetrievalManager` /
  `SuperBeanRetrievalManager` per call would consolidate them.
- **Copy-paste iterators / readers / writers.** Three of the largest files
  (§11, §12, §13) are mechanical copies of upstream classes that exist
  purely because the upstream package made inner types package-private. The
  newer §23 / §24 / §25 trio also exists only because
  `SdmxJsonDataWriterEngineV2.startDataset` lacks a writer-proxy factory
  hook. An upstream PR to widen visibility / expose a stable factory in
  both reader and writer paths is the right long-term fix.
- **Raw-JSON fixtures sidestepping bean-model gaps.** §27 (annotation
  `value`) and §28 (DSD `metadataAttributeUsages`) both work around fields
  the SDMX-JSON 2.0 spec defines but sdmx-core's IM does not model. As more
  SDMX-JSON 2.0 fields land in real registry responses, expect this list to
  grow. A general "captured-bytes" infrastructure (a request-scoped store
  keyed by URN, with a clear contract for capture-on-input and re-inject-on-output)
  would let new fixtures of this kind ship without each one re-inventing the
  parse/round-trip pattern.

If none of these happen, the next sdmx-core upgrade should at a minimum add
a diff-check step: run a `diff` of each Custom* file against the
corresponding upstream class and fail the build if drift is detected. That
would force a conscious decision every time upstream changes a method we
copied.
