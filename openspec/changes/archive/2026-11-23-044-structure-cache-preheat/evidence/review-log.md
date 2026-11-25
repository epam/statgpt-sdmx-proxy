# Review Log

### Iteration 1 — 2026-11-17

**Critic findings (verified):**

1. **[High] Configuration, step 2 / `ProxyConfigurationProviderImpl.java` `setRuntimeOverride`, `ConfigServerConfigExtractor.java` `pollForUpdate`** — `ProxyConfiguration` is `@Data`, so the new `prehit` field joins `equals`. Any edit of the list runs `invalidateAll()` on every registry's caches, and phase 2 would do so on every dataset change.
   - Evidence: both sites run `if (!Objects.equals(previous, config)) { ... invalidateAll(); }`.
   - Resolution: **Accepted** — step 2 adds `ProxyConfigurationComparator.sameForCaching`, which ignores `prehit`. A prehit-only change publishes the event with `cachesInvalidated=false` and keeps the caches. Comparator tests and a provider test were added.
2. **[High] Step 6, step 8 / `sdmx-proxy/src/test/resources`** — with prehit on by default and the OECD list in the bundled configuration, every `@SpringBootTest` context calls live OECD.
   - Evidence: `sdmx-proxy/src/test/resources` holds only `com/`, with no test `application.yaml`. Nine test classes use `@SpringBootTest`.
   - Resolution: **Accepted** — `sdmxproxy.prehit.enabled` defaults to `false` (`${SDMXPROXY_PREHIT_ENABLED:false}`). Deployments enable it. The manual verification sets the variable.
3. **[Medium] Step 4 `pointQuery` / `QueryTranslatorImpl.selectRegistryAndVersion`, `determineStructureReturnFormat`** — point keys copy the SDMX version and return format of the null-Accept descendants query. They equal live keys only for a single-version registry without bypass.
   - Evidence: the return format depends on Accept when bypass is on. Registry selection prefers the Accept's SDMX version. OECD is 2.1-only with `bypassEnabled=false`, so OECD is correct.
   - Resolution: **Accepted** — `isSplitSafe` in `supports()` and the config-server validator require no fixtures, no bypass, and one SDMX version. Prehit paces upstream point fetches on a split miss, counts `split_misses`, and refreshes descendants at most once per dataflow per run.
4. **[Medium] Verification** — nothing tests key equality end to end. E2E point requests always send `references`, so they never read a split entry.
   - Evidence: `BaseRegistryTestSuite.java:229` builds `?references=%s&detail=%s`.
   - Resolution: **Accepted** — `SplitKeyEqualityTest` builds live keys through a real `QueryTranslatorImpl`. A generic E2E block, `DescendantsSplitTestSuitConfiguration`, requests artefacts with no `references`, enabled in the OECD test config.
5. **[Medium] E2E tests** — the E2E `bootRun` loads the bundled configuration and would run prehit against live OECD until the first `POST /config`.
   - Evidence: same as finding 2.
   - Resolution: **Accepted** — resolved by the off-by-default switch. The E2E section says to leave it unset.
6. **[Medium] Step 4 `split` / `AdapterRouterImpl.getStructuresConversion`** — the splitter parses raw bytes and doesn't guard against fixtures, which later run on sdmx-core-rewritten bytes.
   - Evidence: every other parse path applies fixtures first.
   - Resolution: **Accepted** — covered by `isSplitSafe` (no fixtures).
7. **[Low] Step 5 / `RawStructureFetcherImpl.java:98-99`** — a non-null zero-byte body is stored in the raw cache for 24 hours.
   - Evidence: `putRawStructures(key, bytes)` runs for any non-null stream.
   - Resolution: **Accepted** — empty bytes go to the negative cache only. A test was added.
8. **[Low] Step 5 / `InProcessRequestCoalescer.coalesce`** — `refresh` can join a live `fetch` flight and split without overwrite.
   - Evidence: `putIfAbsent` on the in-flight map.
   - Resolution: **Accepted** — documented. The next prehit run repairs it.
9. **[Low] Step 4 `store`** — the existence check reads the whole value, which downloads it in REDIS mode.
   - Evidence: `getRawStructures(key).isPresent()`.
   - Resolution: **Accepted** — the check uses `getRawStructuresTimeToLive(key).isPresent()`.
10. **[Low] Step 7** — `Q2` passed the dataflow URN as source URN, while StatGPT's availability call sends none.
    - Evidence: `AvailabilityQuery30Controller` forwards the absent header as null.
    - Resolution: **Accepted** — `Q2` passes `null`.
11. **[Low] Edge cases** — sdmx-core normalizes dotless versions (`1` to `1.0`).
    - Evidence: `SdmxVersion.getInstance`.
    - Resolution: **Accepted** — edge case 13.
12. **[Low] Files affected / `ProxyConfigurationProviderImplTest.java:34`** — the constructor change breaks an unlisted test.
    - Evidence: `new ProxyConfigurationProviderImpl(Set.of(), cacheServiceProvider)`.
    - Resolution: **Accepted** — listed.
13. **[Low] Edge cases** — the `FILESYSTEM` source publishes no change event.
    - Evidence: `FileSystemConfigExtractorProxy` rereads per call.
    - Resolution: **Accepted** — edge case 14.
14. **[Low] Edge case 11 / `ConfigServerFeignConfig.java:40`** — the proxy decodes with feign `new JacksonDecoder()`, which ignores unknown fields, so only the config server is strict.
    - Evidence: `.decoder(new JacksonDecoder())`. The config server uses `new ObjectMapper()`.
    - Resolution: **Accepted** — edge case 11 rewritten.
15. **[Low] Edge cases** — two startup runs in `CONFIG_SERVER` mode.
    - Evidence: the first poll can precede `ApplicationReadyEvent`.
    - Resolution: **Accepted** — edge case 15. It's harmless.

**Discarded (unverified):** none.

### Iteration 2 — 2026-11-17

**User-directed change before the iteration-2 fixes.** The user rejected the iteration-1
resolution of findings 3 and 6 (`isSplitSafe` as a hidden registry-safety gate). The prehit
configuration states what the client wants (media type, which carries the SDMX version), and the
split must follow it openly. The user chose to split into ready responses for the configured
media type, built from the fixture-applied descendants beans, under keys translated with that
media type. The design was rewritten accordingly:

- `splitDescendants`, `isSplitSafe`, and the validator's split checks were removed. Splitting is
  part of prehit (step 4, step 7) and no longer hooks into `RawStructureFetcherImpl`.
- Split entries are ready responses, so raw-byte fixtures never run on sdmx-core-rewritten bytes
  (resolves iteration-1 finding 6 at the root). Point keys come from the translator with the
  group's media type and the DSD URN (resolves iteration-1 finding 3 at the root).
- `CacheService` gained `getReadyResponseTimeToLive`. Split ready responses are rebuilt near
  expiry from cached descendants without registry calls.
- The split-miss metric and the generic E2E split block were dropped: prehit no longer fetches
  point artefacts, and E2E never runs prehit. `PrehitKeyEqualityTest` and the manual
  verification carry the key-equality proof.
- Iteration-1 finding 8 (refresh joining a live flight) no longer loses an overwrite, because
  the split doesn't run inside the fetch. The step 5 note was updated.

**Critic findings (verified):**

1. **[Medium] Step 7, edge case 13 / `sdmx-core-2.3.9/fusion-utils-sdmx/src/main/java/io/sdmx/utils/sdmx/xs/SdmxVersion.java:40-47`** — prehit's point queries and its split read the same normalized versions, so a dotless-version mismatch with StatGPT can't be detected by prehit. Edge case 13 claimed otherwise, and the same normalization affects `Q2`.
   - Evidence: `if(!version.contains(".")) { version += ".0"; }`. StatGPT passes the version it parsed itself (`dataflow_loader.py`).
   - Resolution: **Accepted** — edge case 13 rewritten: prehit can't detect it, `Q2` is affected too, and the manual verification checks the log for point calls after prehit.
2. **[Medium] Step 7 trigger table and `run()`** — `run()` had no top-level catch, and an exception in a `scheduleWithFixedDelay` task suppresses all later runs.
   - Evidence: `configurationProvider.getConfiguration()` can throw `UnsupportedOperationException` (`ProxyConfigurationProviderImpl.java:33-44`), and a group with null `dataflows` throws a `NullPointerException`, both outside the per-dataflow catch.
   - Resolution: **Accepted** — `run()` catches and counts every `RuntimeException` (`run_failures`). Null groups and lists count as empty. The validator rejects null groups and empty `dataflows`. Tests `shouldKeepPeriodicRunsAfterFailingRun` and `shouldTreatNullGroupsAndListsAsEmpty` were added.
3. **[Low] Step 7 `warmDataflow`** — the generation check ran only between dataflows, so a superseded run kept writing for one dataflow.
   - Evidence: the `run()` pseudocode checked `generation` only in the outer loop.
   - Resolution: **Accepted** — `checkActive` runs before every registry call and every split.
4. **[Low] Step 7 `@PreDestroy`, `pace()` / `InProcessRequestCoalescer.await`** — an interrupt from `shutdownNow()` surfaced as `UnexpectedStateException` and was treated as an ordinary dataflow failure.
   - Evidence: `catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new UnexpectedStateException(...) }`.
   - Resolution: **Accepted** — `checkActive` stops on an interrupted thread, and `warmWithRetry` rethrows as `PrehitCancelledException` when interrupted. Test `shouldStopOnInterrupt` was added.
5. **[Low] Verification** — a test can't call `AdapterRouterImpl.getStructureQuery` (private), and the golden test covered only a codelist.
   - Evidence: both `getStructureQuery` overloads are `private` (`AdapterRouterImpl.java:522, 535`).
   - Resolution: **Accepted** — `descendantsRawKeyMatchesAvailabilityController` uses the controller's argument shape. A concept scheme golden test was added.

**Discarded (unverified):** none.

**Exit:** no Critical or High findings. The loop continued one more iteration because the split
architecture was rewritten after this iteration's critic ran.

### Iteration 3 — 2026-11-17

Review of the rewritten split (ready responses keyed by the group media type).

**Critic findings (verified):**

1. **[Medium] "Why the split matches the live response" / `AdapterRouterImpl.java:204-207`, `FormatSupportChecker.java:48`** — with bypass on for the group media type, the live ready response is the registry's bytes verbatim, while the split stores sdmx-core output.
   - Evidence: `if (FormatSupportChecker.canBypassStructureFormat(versionConfig, requestedMediaType)) { return getStructuresBypass(query, responseKey); }`. No bundled structure endpoint enables bypass. The E2E configuration does.
   - Resolution: **Accepted** — the split skips such artefacts openly: `WARN` plus `split_skipped{reason=bypass}`. The condition is stated in "Why the split matches the live response". Test added.
2. **[Medium] Step 7 `Q2` vs point queries / `QueryTranslatorImpl.java:149-181`, `mapMediaType`** — `Q2` (null Accept, which becomes `application/json`, routed by the dataflow agency) and a point query (group media type, routed by the DSD URN) can resolve to different endpoints on a multi-version registry, or to different registries.
   - Evidence: `selectRegistryAndVersion(agencyId, parsedMediaType.getSdmxVersion(), sourceArtefactUrn)`. `if (acceptHeader == null || acceptHeader.isBlank()) { return MediaType.APPLICATION_JSON; }`.
   - Resolution: **Accepted** — the split writes only when registry name, SDMX version, structure URL, and return format equal `Q2`'s. Otherwise `split_skipped{reason=endpoint}`. Test added.
3. **[Low] "Why the split matches" / `CustomStaxAbstractStructureWriterEngineV21.java:203-206`** — a header-less container makes the writer fall back to a default sender.
   - Evidence: the writer reads `beans.getHeader()`.
   - Resolution: **Accepted** — the split passes `closure.getHeader()`. `store` takes a `HeaderBean`.
4. **[Low] Step 7 `warmReadyResponse(Q1)`** — the `Q1` ready response isn't refreshed ahead of expiry.
   - Evidence: `getStructures` returns a hit unchanged.
   - Resolution: **Accepted** — documented as a known gap. It costs one local conversion from a warm raw entry, with no registry call. Adding a cache remove operation isn't worth it.
5. **[Low] Step 7, edge case 7 / `AdapterRouterImpl.java:287-293`** — an empty descendants answer counted as warmed.
   - Evidence: `getSdmxBeans` returns `new SdmxBeansImpl()` for empty bytes.
   - Resolution: **Accepted** — an empty closure fails the dataflow. Test added.
6. **[Low] Step 7 `run()`** — a group with a null or blank `mediaType` (possible through `POST /config`) failed every dataflow on every run.
   - Evidence: a null Accept becomes `application/json`, which the JSON writer rejects.
   - Resolution: **Accepted** — such groups are skipped with a `WARN`.
7. **[Low] Step 5 `refresh`** — skipping the cooldown let prehit call a key that just ended in 429.
   - Evidence: the step 5 text skipped the cooldown read.
   - Resolution: **Accepted** — `refresh` keeps `throwIfCoolingDown`. Test added.
8. **[Low, note] Step 7 triggers** — the synchronization of event-driven rescheduling wasn't specified.
   - Evidence: the event arrives on HTTP or config-poll threads.
   - Resolution: **Accepted** — rescheduling runs on the calling thread under a private lock, and `generation` is an `AtomicLong`.

**Discarded (unverified):** none.

**Exit:** converged. No Critical or High findings survived verification.

### Rename after iteration 3 — 2026-11-17

The term "prehit" was a mishearing of "preheat", the term design 021 uses. Everything outside
this log was renamed: the root configuration field (`preheat`), `PreheatConfiguration`,
`preheatEnabled`, the `services/preheat/` package and its classes, the `sdmxproxy.preheat.*`
properties, `SDMXPROXY_PREHEAT_ENABLED`, the `sdmxproxy.preheat.*` metrics, and the design
directory (`044-structure-cache-preheat`). The iteration entries in this log keep the old
spelling because the log is append-only. Read "prehit" in them as "preheat".

### User-directed change after the rename — 2026-11-17

The user asked how refresh avoids a new stampede when preheated entries expire together. Entries
written in one startup run would otherwise reach the refresh window in the same hourly run, every
day, because Caffeine applies no jitter and each refresh realigns them. The design adds:

- a stable per-key offset on the refresh threshold (`refreshSpread`, PT6H), so refreshes spread
  over several runs and stay spread;
- a refresh budget per run (`maxRefreshesPerRun`, 10) that doesn't limit fetches of missing
  entries;
- `refreshAhead` raised from PT3H to PT12H, which gives at least 12 attempts before expiry;
- a separate `readyRefreshAhead` (PT2H) for split ready responses, whose TTL is 6 hours;
- startup validation of the value constraints, and tests for all of the above.

Stale-on-error was discussed and left out of scope by the user.
