# Review Log

### Iteration 1 — 2026-10-26

Two critics ran in parallel: one on steps 1-10, one on steps 11-15 and Verification. Their
duplicate findings are merged. A live probe on 2026-10-26 confirmed finding 1.

**Critic findings (verified):**

1. **[Critical] B4, Step 9 / `GenericRegistryAdapterImpl.java:122`, `Sdmx21DataClient.java:28`** — `getData21` always appends `/all` as the provider segment, and StatCan answers `404` to it.
   - Evidence: `providerRef = "all"` feeds `GET /{flowRef}/{key}/{providerRef}`. Probe: `data/DF_17100005/1.1.1/all` and `.../StatCan` return `404` with both XML 2.1 `Accept` values. `data/DF_17100005/1.1.1` returns `200`, and the `Content-Type` matches the request. The critic rated this Medium (unprobed). The probe raised it to Critical.
   - Resolution: **Accepted** — new `omitProviderRef` flag, a new `Sdmx21DataClient` overload `GET /{flowRef}/{key}` with `@QueryMap`, probe rows added, config and tests updated.
2. **[High] Step 4, Step 7 / `AdapterRouterImpl.java:247-253`** — `getStructuresConversion` wraps every non-Feign exception in `StructureConversionException` (`500`), so the source's `404` and `501` reach the client as `500`.
   - Evidence: read the catch blocks. StatCan has bypass off, so every structure request takes this path.
   - Resolution: **Accepted** — rethrow `BaseException` before the generic catch. Router-level status tests added.
3. **[High] Step 7 / `exception/NotImplementedException.java:7`** — `NotImplementedException` is abstract and has protected constructors.
   - Evidence: read the class. It has three concrete subclasses.
   - Resolution: **Accepted** — new `StructureListNotSupportedException`.
4. **[High] Step 10 / `QueryTranslatorImpl.java:582`** — the `MapUtils.isEmpty(query.getFilters())` guard skips the expansion for key `*` plus a time filter, which is StatGPT's normal request. StatCan answers `400`.
   - Evidence: `.filters(filters)` keeps the original map after the 2.1 key folding. Both critics found this one.
   - Resolution: **Accepted** — the decision rests on the key alone. `emptyTimeRange` pin and `shouldExpandMatchAllKeyWithTimeOnlyFilter` added.
5. **[High] Step 11 / `SDMXStructureDataStructure.xsd` (`PrimaryMeasureType`)** — writing `String` on a component without `LocalRepresentation` overrides an inherited concept representation, so the fixture isn't generic.
   - Evidence: the XSD text says a component may take its representation from the concept.
   - Resolution: **Accepted** — the component stays unchanged when its concept has a `CoreRepresentation`. Inheritance test added.
6. **[Medium] Step 11 collect pass** — collecting only non-empty local representations sends `TIME_PERIOD`'s concept to `String`, which contradicts the test.
   - Resolution: **Accepted** — the collect pass records effective representations.
7. **[Medium] Step 7 selection table / `SdmxStructure30Controller.java:51`** — there are no rows for null `references` (what StatGPT sends) or for `ancestors` (in the E2E matrix).
   - Resolution: **Accepted** — null counts as `none`, `ancestors` as `parents`, and unlisted type names add nothing. Tests added.
8. **[Medium] Step 1, Step 8 / `ConfigValidator` usage** — the validator runs only in the config server, not on the proxy's own config load or `POST /config`.
   - Evidence: grep finds it only under `sdmx-proxy-config-server`.
   - Resolution: **Accepted** — the validator section says so, the source fails fast at runtime, and the format rule is listed as the third rule.
9. **[Medium] Step 10 bypass routing / `AdapterRouterImpl.java:376-383`** — routing bypass through `fetchData` would leak `RegistryNoDataException` as `500` for other registries.
   - Resolution: **Accepted** — the bypass branch calls only `expandMatchAllKey(query)`.
10. **[Medium] Step 14 / `BaseRegistryTestSuite.java:545`** — the DSD fidelity request uses `references=none`, so a representation pin on it never sees concepts.
    - Resolution: **Accepted** — the pin requests `references=children`, and `expectedConceptEnumerations` was added.
11. **[Medium] Step 12** — `supportsLimit: true` silently drops a client's `limit` on 2.1. The claim that StatCan behaves like Eurostat was wrong, because Eurostat is 3.0.
    - Evidence: `getData21` has no `limit` parameter.
    - Resolution: **Accepted** — recorded as edge case 11.
12. **[Medium] B5, edge case 4** — the whole-table size figures contradicted each other.
    - Evidence: the 354,498-byte file is `.1.` (one `Geography`). A re-probe of `.1+2+3.` returned 14,012,593 bytes.
    - Resolution: **Accepted** — figures corrected.
13. **[Low] Step 13 / `StructureTypeAndUrn`, `QueryShapeTestSuitConfiguration`** — the test-config sketch didn't match the framework's shapes.
    - Resolution: **Accepted** — the table now uses `{type, urn}`, `dataflowUrn`, `mediaTypes`, and `registryReturnFormats`, and the availability matrix is kept small.
14. **[Low] Step 12, open question 2** — the production config is `sdmx-proxy-config/src/main/resources/sdmx_registries_config.json`.
    - Resolution: **Accepted** — added to step 12 and Files affected, and the question is closed.
15. **[Low] Step 10 / `AvailabilityQueryCanonicalizer.isWildcardKey`** — the match-all rule already exists.
    - Resolution: **Accepted** — the canonicalizer is reused, and the normalizer change is dropped.
16. **[Low] Step 11** — the time-rule rationale was inaccurate, the `LocalRepresentation` insertion point was unspecified, and three tests were missing: renamer input, conflict, and null config.
    - Resolution: **Accepted** — all of them are fixed.
17. **[Low] B1 table, Step 15** — StatGPT's availability call is a `POST`, and it swallows `400`/`404` into empty constraints.
    - Resolution: **Accepted** — the table is corrected, and step 15 checks for the log line.
18. **[Low] README scope, constructor call sites, `parseStructures` signature, `X-Source-Artefact-Urn` wording, worked-example key order (`2+3+1`)**
    - Resolution: **Accepted** — all of them are fixed.
19. **[Low] Concurrent cache misses aren't single-flighted.**
    - Resolution: **Deferred** — one extra download per race. Recorded as edge case 12.

**Discarded (unverified):** none.

### Iteration 2 — 2026-10-26

One critic reviewed the changes from iteration 1. A live probe on 2026-10-26 turned finding 4 into a
new Critical (finding 1 here).

**Critic findings (verified):**

1. **[Critical] New `B7` / live probe** — every StatCan empty result is malformed XML: `<message:DataSet ...></Series> </message:DataSet>` (`</generic:Series>` in generic).
   - Evidence: `data/DF_17100005/.2+3+1.?startPeriod=1800&endPeriod=1801` and `data/DF_17100005/999.1.1` return `200` with that body, in both XML 2.1 formats. A Python XML parse fails with "mismatched tag". The probe context (`AdapterRouterImpl.RouterAvailabilityEmulationContext.fetchData`) applies no data fixtures.
   - Resolution: **Accepted** — step 11a adds the streaming `REPAIR_EMPTY_DATASET` data fixture and applies it on the probe through an `onlyTypes` overload. E2E key `999.1.1` and the `emptyTimeRange` pin cover it.
2. **[High] B5, step 5 of the worked example / `ProbeQueryBuilder.build`, `QueryTranslatorImpl.translateAvailabilityQuery`** — on SDMX 2.1 the availability probe drops the time filter.
   - Evidence: `build` sets `.key(key).filters(filters)` and never sets the period. `translateAvailabilityQuery` sets `startPeriod` only from the controller, which passes `null` on POST. `getData21` ignores `filters`.
   - Resolution: **Accepted** — new `B8` section and step 11b: derive the window for emulated 2.1 availability, and copy it in `ProbeQueryBuilder`. Tests and the `timeWindowedAvailability` pin are added. Widening the fix to real 2.1 availability (IMF 2.1, OECD) became open question 2.
3. **[Medium] Step 11** — the fixture wrote `String` when a component's concept was absent from the document, and it filled a concept scheme fetched alone differently from one fetched with its DSD.
   - Resolution: **Accepted** — the fixture leaves a document unchanged unless both sides are present. Two tests are added.
4. **[Medium] Step 4 / `AdapterRouterImpl.java:247-253`** — the `BaseException` rethrow changes statuses for every registry: `429`, `501`, and `503` instead of `500`.
   - Evidence: grep found `RateLimitExceededException`, `UnsupportedConversionException`, and `CacheUnavailableException` throw sites on that path. No test pins `500`.
   - Resolution: **Accepted** — the design states the change as intended, and two tests are added.
5. **[Medium] Step 13 `emptyTimeRange`** — StatCan's empty-range answer had never been probed.
   - Resolution: **Accepted** — probed: `200` with the malformed body. That became finding 1.
6. **[Medium] Step 9 / `GenericRegistryAdapterImpl.java:120,208`** — `getFlowRef` is shared by the data and availability paths.
   - Resolution: **Accepted** — the branch moves to `getData21`, and an availability test is added.
7. **[Low] Step 4** — `StructureNotFoundException` broke the per-status exception family convention.
   - Resolution: **Accepted** — an abstract `NotFoundException` is added.
8. **[Low] Edge case 7 / `Resilience4jComponentFactory.java:87-96`** — the retry timing was wrong.
   - Resolution: **Accepted** — two retries, after 2 and 4 seconds.
9. **[Low] Step 10** — the `firstDataStructure` helper doesn't exist.
   - Resolution: **Accepted** — `expand(SdmxBeans)` looks up the DSD itself.
10. **[Low] Step 14** — three gaps: the JSON 2.0 series path, the short URN against the full URN, and a `longOrKey` of about 600 characters that never exercises the URL limit.
    - Resolution: **Accepted** — paths are given per version, the check uses `endsWith`, and a long-URL table is chosen at implementation with a minimum of 5,000 characters asserted.
11. **[Low] E2E cost** — key `*` in the pairwise data matrix downloads 14 MB per run.
    - Resolution: **Accepted** — `*` is removed from `dataflowConfigs`, and the dedicated pin covers it.

**Discarded (unverified):** none.

### Iteration 3 — 2026-10-26

One critic reviewed the iteration 2 changes. It confirmed that the repair runs before any parse on
the data path (`AdapterRouterImpl.getData`, around lines 395-429) and on the probe path (before
`XmlSeriesKeyHarvester`). It also confirmed that `translateAvailabilityQuery` has the beans and
`dimensionService` that B8 needs, and that the emulated-availability cache key includes the
filters.

**Critic findings (verified):**

1. **[High] Step 14 `timeWindowedAvailability` / `AvailabilityEmulationConfiguration.java:65`, `XmlSeriesKeyHarvester`** — the pin asserted a `TIME_PERIOD` range, but the emulated constraint never contains one.
   - Evidence: `includeTimePeriod = false`, and the harvester collects only non-time dimensions.
   - Resolution: **Accepted** — the pin is replaced by the gated `expectEmptyAvailabilityForEmptyTimeRange`, which extends `testAvailabilityWithEmptyTimeRangeReturnsOk`. It asserts empty key values, which also covers B7 on the probe path.
2. **[Medium] Step 11a** — a prefix match on `message:DataSet` can hit the header elements `DataSetID` and `DataSetAction`.
   - Resolution: **Accepted** — the match is now on the exact local name, and a test is added.
3. **[Medium] Step 11b / `AdapterRouterImpl.java:547`** — the derivation condition differed from the router's dispatch (`!isAvailabilityEnabled()`).
   - Resolution: **Accepted** — the condition matches the dispatch, and the test covers emulation together with `availabilityEnabled: true`.
4. **[Medium] Step 1 README** — the data fixture table needs a `REPAIR_EMPTY_DATASET` row.
   - Resolution: **Accepted** — the row is added.
5. **[Low] B5, edge case 4** — no main code reads `probeSplitThresholdBytes` or `probeTimeoutMillis`.
   - Evidence: grep finds no getter use.
   - Resolution: **Accepted** — the text names `maxProbeBytes`, `maxProbeSeries`, and the registry `readTimeout`.
6. **[Low] Step 11a** — fixture order: a StAX fixture listed first would fail on the malformed body.
   - Resolution: **Accepted** — the service always runs the repair first, and a test pins it.
7. **[Low] Step 11a** — the conversion of the repaired empty body wasn't unit-tested.
   - Resolution: **Accepted** — `shouldConvertRepairedEmptyBodyToJson20` is added.

**Discarded (unverified):** none.

**Exit:** iteration 3 produced one High finding, so the loop continues to iteration 4.

### Iteration 4 — 2026-10-26

A final pass over the whole design. The critic traced the extended availability pin end to end.
The test sends a GET, the key normalizes to `all`, the probe carries the derived window, and the
key is expanded. The harvester returns empty sets for a body with no series, and
`AvailabilityConstraintSynthesizer` writes one cube region whose keys have empty values. The
response is `200`, so the pin can be implemented.

**Critic findings (verified):**

1. **[Medium] No changes required** — `QueryTranslatorImpl.java` was listed as unchanged, which contradicts step 11b.
   - Resolution: **Accepted** — the entry is reworded.
2. **[Low] Step 14** — the empty-availability pin could pass with no constraint at all.
   - Resolution: **Accepted** — the pin also asserts one key per non-time dimension.
3. **[Low] Step 13 / `QueryTranslatorImpl.java:56-57`** — the `emptyTimeRange` value was unspecified, and only `ge`, `gt`, `le`, and `lt` derive a period.
   - Resolution: **Accepted** — the value is `ge:1800+le:1801`.
4. **[Low] Files affected** — the `GlobalExceptionHandler` row disagreed with step 4.
   - Resolution: **Accepted** — the row now matches step 4.
5. **[Low] Step 14 / `BaseRegistryTestSuite.java:~1030`** — a query string embedded in the path can be dropped silently.
   - Resolution: **Accepted** — the pins pass parameters through the params map.

**Discarded (unverified):** none.

**Exit:** converged. Iteration 4 produced no Critical or High findings. It is also the iteration cap.
