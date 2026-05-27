# Design 029: E2E recovery session — bug catalog (2026-05-26)

**Status:** reference document; records the bugs cleared during the
"get E2E green" session of 2026-05-26. No code changes proposed here --
the individual fixes have their own designs (023, 027, 028) or live as
inline diffs documented in this catalog.

Grouped by where the bug lived; each entry covers symptom, root cause, the
change that fixed it, and the gap in the safety net that let it ship. For
background on the broader designs, see the cross-links into
`docs/designs/`.

## E2E test bugs (`sdmx-proxy-e2e/`)

### 1. `StructureWildcardE2ETest` — toggle-off contract test was never toggled off

- **What:** The "fan-out disabled" contract test was passing for the wrong reason on
  pipelines where the runtime config had fan-out left enabled.
- **Root cause:** `sdmx-proxy-e2e/src/test/java/com/epam/sdmxproxy/e2e/tests/StructureWildcardE2ETest.java:60-66`
  read the JSON-on-classpath registry config and pushed it as-is. The classpath default for
  `structureFanOutEnabled` is currently `true` in production config, so the suite silently
  relied on whatever the production default happened to be on the day rather than asserting
  the toggle-off semantics. Compounded by the second bug (#5 below), every `@BeforeAll`
  was also discarding the proxy's 404 response when the test config endpoint was disabled.
- **Fix:** Set `config.setStructureFanOutEnabled(false)` explicitly before the push so the
  contract is independent of the classpath default.
- **Why it slipped through:** Test wrote in terms of the implicit default rather than the
  asserted behaviour. See `docs/designs/023-structure-fan-out-feature-toggle/DESIGN.md`
  for why the toggle exists.

### 2. `BaseRegistryTestSuite.testSpecificStructureTypes` — `hierarchy` substring assertion

- **What:** Specific-structure parametrized cases failed for the `hierarchy` artefact type
  on every registry.
- **Root cause:** `sdmx-proxy-e2e/src/test/java/com/epam/sdmxproxy/e2e/tests/framework/BaseRegistryTestSuite.java:236-241`
  used `containsIgnoringCase(artefact.getType())` against the response body. That works for
  most types but not for `hierarchy`: SDMX-JSON 2.0 emits the plural under the irregular
  `hierarchies` key (y -> ies, so "hierarchy" is not a substring), and SDMX-ML 2.1 downgrades
  a 3.0 Hierarchy into `<str:HierarchicalCodelist>` (different stem entirely).
- **Fix:** Special-case `hierarchy` and accept either `hierarchies` (JSON) or
  `hierarchicalcodelist` (ML 2.1) as the marker.
- **Why it slipped through:** Generic substring assertions implicitly assumed singular and
  plural share a stem.

### 3. `StructureSmokeTests` / `AvailabilitySmokeTests` — no config push in `@BeforeAll`

- **What:** BIS-targeted smoke tests returned HTTP 400 whenever they ran after a suite that
  had pushed a different runtime config (typically IMF).
- **Root cause:** `sdmx-proxy-e2e/src/test/java/com/epam/sdmxproxy/e2e/tests/smoke/StructureSmokeTests.java:30-43`
  and `sdmx-proxy-e2e/src/test/java/com/epam/sdmxproxy/e2e/tests/smoke/AvailabilitySmokeTests.java:30-43`
  built a `RestClient` but never POSTed a `ProxyConfiguration`. The proxy kept the previous
  suite's overrides, so BIS smoke requests ran against an IMF-only routing table.
- **Fix:** Both `@BeforeAll` methods now load the BIS 3.0 registry config from the classpath
  and push it via `ProxyConfigPusher.push(...)`.
- **Why it slipped through:** Order dependence between suites is invisible until you reorder
  them in CI.

### 4. `ReviewEnvironmentPlaceholderE2ETest` — hard-asserted env var instead of skipping

- **What:** Failed locally with an assertion error rather than being skipped when
  `E2E_PASSWORD` was not set.
- **Root cause:** `sdmx-proxy-e2e/src/test/java/com/epam/sdmxproxy/e2e/tests/review/ReviewEnvironmentPlaceholderE2ETest.java:43-45`
  used `assertThat(apiKey).isNotBlank()`. The test only exercises a DIAL-fronted review
  deployment, so absent the env var the right behaviour is to skip, not fail.
- **Fix:** Replaced the assertion with `Assumptions.assumeTrue(...)` carrying an explanatory
  message about which deploy shape this test targets.
- **Why it slipped through:** Pattern was copy-pasted from suites that genuinely require the
  env var; this one does not.

### 5. New helper: `ProxyConfigPusher.push(...)` — fail fast on missing test endpoint

- **What:** Before this helper, every `@BeforeAll` did
  `restClient.postResponse(CONFIG_PATH, ...)` and ignored the result. When
  `SDMXPROXY_TEST_CONFIG_ENDPOINT_ENABLED` was not set on the target proxy, the POST
  silently 404'd and the suite then ran against an unrelated default registry config for
  the next 22 minutes before producing a confusing failure.
- **Root cause:** Missing status-code assertion at every push site.
- **Fix:** New utility at
  `sdmx-proxy-e2e/src/test/java/com/epam/sdmxproxy/e2e/support/util/ProxyConfigPusher.java:1-29`
  POSTs the config, asserts HTTP 200, and surfaces a clear "set
  `SDMXPROXY_TEST_CONFIG_ENDPOINT_ENABLED=true`" message on anything else. Wired into
  `BaseRegistryTestSuite`, `LimitEmulationE2ETest`, `StructureFanOutE2ETest`,
  `StructureWildcardE2ETest`, both smoke suites, and the new multi-registry fan-out test.
- **Why it slipped through:** "Push config and ignore response" is the kind of harness code
  that only bites when the env contract changes; no test exercised the missing-endpoint
  case.

### 6. New `StructureFanOutMultiRegistryE2ETest` — `n=2` merge path was uncovered

- **What:** The structure fan-out merge code path was only exercised with a single registry
  by `StructureFanOutE2ETest`, so the actual merge (n>=2) had no regression coverage.
- **Root cause:** `StructureFanOutE2ETest` configures BIS only; a one-registry "fan-out" is
  indistinguishable from a passthrough, so the merge logic was never invoked.
- **Fix:** New suite at
  `sdmx-proxy-e2e/src/test/java/com/epam/sdmxproxy/e2e/tests/StructureFanOutMultiRegistryE2ETest.java:1-108`
  loads both `bis_3_0_registry_config.json` and `imf_3_0_registry_config.json`, merges
  their `configs`/`agencies` lists into a single `ProxyConfiguration`, sets
  `structureFanOutEnabled=true`, and asserts the wildcard dataflow response carries
  `agencyID` values from both `BIS*` and `IMF*` artefacts. See
  `docs/designs/023-structure-fan-out-feature-toggle/DESIGN.md` for the feature spec.
- **Why it slipped through:** Coverage was scoped to the toggle, not to the merge behaviour
  the toggle gates.

## Proxy bugs (`sdmx-proxy/`)

### 7. `HeaderWriterFilter` race — sporadic `Invalid header: : ` `ClientProtocolException`

- **What:** ~5% flakiness in the BIS+IMF structure suites (32 of 579 cases) with Apache
  HTTP client throwing `ClientProtocolException: Invalid header: : ` on cache-hit fast-path
  responses.
- **Root cause:** Spring Security 7.0.5 (Spring Boot 4.0.6) ships a `HeaderWriterFilter`
  that races with `StreamingResponseBody` writes on the cache-hit fast path. Tomcat's
  `MimeHeaders` byte buffer occasionally gets corrupted, producing a malformed header line
  on the wire. Tracked upstream as
  [spring-projects/spring-security#15510](https://github.com/spring-projects/spring-security/issues/15510).
  The DSL in this Spring Security version does not expose
  `setShouldWriteHeadersEagerly(...)`.
- **Fix:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/web/config/ResourceServerConfig.java:37-55`
  builds the chain, then walks `chain.getFilters()`, finds every `HeaderWriterFilter`
  instance and flips `setShouldWriteHeadersEagerly(true)`. Writing security headers before
  the controller starts streaming avoids the race entirely. Flake rate dropped from ~5% to
  0.
- **Why it slipped through:** Race only manifests under concurrent streaming responses and
  was easy to dismiss as flaky network until repro frequency stabilised.

### 8. `DataStructure not found: DSD_WEO` 500 on IMF limit emulation

- **What:** IMF limit-emulated requests for the WEO dataflow returned HTTP 500.
- **Root cause:** `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/misc/DimensionServiceImpl.java:42-50`
  and `:73-78` (pre-fix) matched a DSD inside `SdmxBeans` by literal string equality on
  agency, id, and `version.toString()`. IMF's WEO dataflow references its DSD with the
  SDMX 3.0 wildcard `IMF.RES:DSD_WEO(9.0+.0)` (meaning "any specific 9.0.x"), while the
  DSD bean itself carries the concrete version `9.0.0`. A literal `equals` between
  `9.0+.0` and `9.0.0` never matches, so the lookup threw and bubbled up as a 500.
- **Fix:** Extracted a `resolveDsd(beans, agency, id, versionRef)` helper at
  `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/misc/DimensionServiceImpl.java:78-129`.
  It parses the requested version as a jsdmx `VersionReference`; for a specific version it
  still requires an exact match, and for a wildcard reference it uses
  `WildcardReferenceMatcher` plus `VersionReference.getComparator()` to pick the latest
  specific candidate that satisfies the wildcard scope. Falls back to literal string match
  when either side fails to parse (legacy two-part versions). Applied to both
  `getDsdFromDataflow` and `getDimensionIdsFromDsd`.
- **Why it slipped through:** No real IMF dataflow with a wildcarded structure ref ran
  through limit emulation in unit-test coverage; previous data-only paths consume the DSD
  directly from the conversion pipeline, not by re-looking-up the bean set.

### 9. `JsonDataV20SeriesLimitTruncator` was a no-op on real responses

- **What:** `testLimitEmulationStrict` against IMF with `JSON_DATA_2_0_0` returned 12 series
  for `limit=10` — the truncator left the body unchanged.
- **Root cause:** `JsonDataV20SeriesLimitTruncator.transform()` only handled the case where
  the JSON `series` token is a `START_ARRAY` (SDMX-JSON 1.x layout). SDMX-JSON 2.0 emits
  `series` as a `START_OBJECT` keyed by dimension positions, so the START_OBJECT branch
  fell through without truncating. Sibling `JsonDataV10SeriesLimitTruncator` already
  handles the object form via `copySeriesMap`.
- **Fix:** Added the missing `START_OBJECT` branch at
  `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/limit/truncate/JsonDataV20SeriesLimitTruncator.java:87-95`,
  delegating to `copySeriesObject` (already present in the class) and closing the object.
  Full rationale and behaviour notes in
  `docs/designs/028-json-data-v20-truncator-series-object/DESIGN.md`.
- **Why it slipped through:** No unit test covered the v2.0 truncator at all. The v1.0
  sibling has tests for both shapes; the asymmetry meant the v2.0 class compiled fine and
  silently no-op'd in production.

## How E2E was failing before this session

The full E2E run was sitting at 37+ failures on a clean checkout: the smoke suites were
mis-routed when reordered (#3), the fan-out toggle test was non-deterministic against the
production default (#1), the hierarchy assertion failed on every registry (#2), the limit
emulation suite blew up on IMF WEO (#8) and overshot for SDMX-JSON 2.0 (#9), the
review-env placeholder failed locally (#4), and the BIS+IMF structure suites flaked at
roughly 5% on the security filter race (#7). On top of that, the `@BeforeAll` config push
was silently dropping a 404 when the test config endpoint was disabled, which led to a
22-minute run reporting downstream failures against the wrong runtime config — that false
negative was the direct motivation for `ProxyConfigPusher` (#5). After this batch, the
suite is at 0 failures, including the new multi-registry fan-out coverage (#6).
