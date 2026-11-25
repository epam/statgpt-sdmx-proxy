# Review Log

### Iteration 1 — 2026-07-31

Two critics run in parallel (correctness/feasibility emphasis; completeness/spec/tests emphasis),
findings deduped. **Every finding below was independently re-verified by the main agent against the
real code before being accepted** — all cited line numbers in the original design also checked out.

**Critic findings (verified):**

1. **[High] Step 2 / `Resilience4jComponentFactory.java:53-59`** — Excluding 429 from
   `recordException` does not stop 429 from opening the breaker, because slow-call detection is on by
   default and the Step 3 sleep happens inside the breaker's timed span.
   - Evidence: `javap -constants resilience4j-circuitbreaker-2.3.0.jar` →
     `DEFAULT_SLOW_CALL_RATE_THRESHOLD = 100`, `DEFAULT_SLOW_CALL_DURATION_THRESHOLD = 60` (seconds).
     `CircuitBreakerConfig.custom()` at line 53 sets neither. A 75-135 s retry budget exceeds 60 s, so
     a 429 storm makes 100 % of calls "slow" and opens the breaker.
   - Resolution: **Accepted** — Step 2 now mandates `.slowCallDurationThreshold(...)` above the 429
     budget, with a unit test (`circuitBreakerShouldNotOpenOnSlowRateLimitedCalls`). This defeated the
     design's central invariant and was the most valuable finding of the round.

2. **[High] Step 3 / Edge case 9** — `max(backoff, Retry-After)` applies `maxIntervalMillis` only to
   the exponential term, so a hostile or buggy `Retry-After: 3600` blocks a Tomcat thread for an hour
   per attempt; the "bounded by the geometric series, ~75 s" claim was false.
   - Evidence: design-internal inconsistency; nothing in the stated algorithm clamps the header. The
     proxy streams on the request thread (`StreamingResponseBody`), so each sleep holds a container thread.
   - Resolution: **Accepted** — rule is now `min(max(backoff, retryAfter), maxIntervalMillis)` plus a
     new `maxTotalWaitMillis` per-invocation budget; edge case 9 rewritten.

3. **[High] Solution A** — "keeps the two families exactly independent" is wrong: the outer
   resilience4j `Retry` restarts the inner 429 cycle on `IOException`, so attempts multiply.
   - Evidence: `RateLimitRetryClient.execute` declares `throws IOException` and does not catch;
     the outer retry predicate matches `IOException` (`Resilience4jComponentFactory.java:161-163`).
     With the Step 8 OECD config that is 18 upstream calls and ~405 s of sleeping, not ~75 s.
   - Resolution: **Accepted** — claim narrowed to "attempt caps separate", multiplication quantified
     in Step 3, bounded by `maxTotalWaitMillis`.

4. **[High] New problem P4 — `version` / `resourceID` path slots never translated** — a
   spec-conforming 3.0 client sending the documented default `version=*` produces the 2.1 flowRef
   `AGENCY,FLOW,*`.
   - Evidence: `QueryTranslatorImpl:541` and `:374` set `.version(version)` verbatim;
     `normalizePathSlot` is called only from `SdmxStructure30Controller:62-64` and the structure
     translators. `GenericRegistryAdapterImpl.getFlowRef:136` concatenates raw slots. Spec:
     `availability.md:17` — `version` default `*`. Internally inconsistent, since the DSD lookup for
     the same request goes through `translateStructureQuery`, which *does* map `*` → `all`.
   - Resolution: **Accepted** — added as problem P4; `toPathSlot` added to the normalizer and applied
     in `getFlowRef` (Step 6). This was a genuine scope gap, not a nitpick.

5. **[High] Verification / `SdmxApiClientProviderImplTest.java:60`** — routing `.client(...)` through
   a collaborator breaks ~20 existing tests with NPE; the suite was not listed in the design.
   - Evidence: line 60 `mock(Resilience4jComponentFactory.class)`; an unstubbed mock returns `null`
     and feign's `SynchronousMethodHandler$Factory` calls `Util.checkNotNull(client, "client")`.
   - Resolution: **Accepted** — added to Files Affected and to the "what actually breaks" table.

6. **[High] Step 4 / `KeyParserImpl.java:13-16`** — "reuse the constants" will not compile; they are
   package-private in `services.limit`, the normalizer lives in `services.translator`.
   - Evidence: `static final String POSITION_SEPARATOR = ".";` etc., no access modifier.
   - Resolution: **Accepted** — reuse `QueryTranslatorImpl.SDMX_30_ALL_WILDCARD` /
     `SDMX_21_ALL_WILDCARD` (public, same package) instead; `No Changes Required` updated.

7. **[Medium] Step 4 / `availability.md:19`** — collapsing *any* comma-joined componentId to `all`
   silently widens a legitimate client request.
   - Evidence: spec marks `componentId` "Multiple values? Yes", default `*` (verified by extracting
     the table column). The design justified the `references` substitution as behaviour-preserving but
     offered no such argument for componentId.
   - Resolution: **Accepted** — `toComponentId` now maps only `*` / `null` to `all` and passes lists
     through; proxy-generated comma lists are fixed at their producers instead. New edge case 10.

8. **[Medium] Step 7 / `LimitEmulationServiceImpl.java:554,567`** — Step 7 did not make 2.1 probes
   "correct on their own": the probe and shrink keys still carry `*`.
   - Evidence: both call `keyParser.buildKey(...)`, which emits `WILDCARD` per unconstrained dim
     (`KeyParserImpl.java:57`) → `USA.*.*.*.*`, the form OECD 404s. The shrunk key is also what is
     cached (`AdapterRouterImpl:473`), so cache and wire form would diverge.
   - Resolution: **Accepted** — Step 7 now routes both call sites through `toKey`.

9. **[Medium] Step 7 / `LimitEmulationServiceImpl.java:190-192,457`** — the `toAvailabilityQuery30`
   version guard is dead code.
   - Evidence: `getShrunkQuery` dispatches `version == SDMX_2_1 ? runBisect21 : runBisect30`;
     `toAvailabilityQuery30` is reachable only from `runBisect30`.
   - Resolution: **Accepted** — bullet deleted, replaced with an explicit "needs no change" note.

10. **[Medium] Step 3 / `SdmxApiClientProviderImpl.java:216`** — "each cached target gets its own
    settings" is false; the cache is keyed on `(class, baseUrl)` with no eviction.
    - Evidence: `buildKey(Class<?> c, String baseUrl)`; `builtApiClients` has only get/put in the
      whole codebase. So registries sharing a base URL share settings, and `POST /config` hot-reload
      has no effect once a client exists — relevant because `BaseRegistryTestSuite:878-888` re-pushes
      mutated configs between tests.
    - Resolution: **Accepted** — limitation documented and adding the registry name to `buildKey`
      recommended.

11. **[Medium] Verification** — the claim that `QueryTranslatorImplTest` and
    `GenericRegistryAdapterImplTest` "assert the current pass-through" was wrong.
    - Evidence: `GenericRegistryAdapterImplTest` contains **zero** occurrences of `SDMX_2_1` (all
      seven tests are 3.0); in `QueryTranslatorImplTest` every key assertion uses `SDMX_3_0`, and the
      one 2.1 assertion `assertEquals("all", query.getKey())` (line 559) still passes. Only
      constructor arity breaks in both.
    - Resolution: **Accepted** — claim corrected; the real gap (no 2.1 adapter coverage at all) is now
      called out as required new work.

12. **[Medium] Step 8 vs Step 3** — backoff arithmetic inconsistent (75 s for `maxAttempts: 5` vs
    135 s for the Step 8 `maxAttempts: 6`), and `limitTestSuitConfiguration.key` was left in 2.1 form
    so the limit path would not pin the translation.
    - Evidence: `oecd_2_1_test_config.json:90` is `"USA.A...."`; Step 8 only changed the data and
      availability blocks.
    - Resolution: **Accepted** — limit key switched to `"USA.A.*.*.*"`, both figures reconciled,
      expected suite duration (~15 min) stated.

13. **[Medium] Edge cases / `AdapterRouterImpl.java:520-522,563-578`** — `unwrapStarComponentId`
    would be silently neutralised on 2.1 and was undocumented.
    - Evidence: it sets a comma-joined list post-translation; also its guard
      `"*".equals(componentId) || == null` (line 576) never fires once Step 5 rewrites `*` → `all`.
      Latent only — no 2.1 registry sets the flag.
    - Resolution: **Accepted** — new edge case 11, mirroring the `convertKeyToFilters` entry.

14. **[Low] Out of Scope / `QueryTranslatorImpl.java:214`** — agency-scheme discovery passes agency
    `"*"` unnormalized, unlike the fan-out path (line 255).
    - Evidence: confirmed at line 214; OECD sets `allowSubAgencies: true`. Degrades quietly —
      `AgencySchemeService` catches and falls back.
    - Resolution: **Deferred** — documented under Out of Scope with evidence; outside the agreed
      three-item scope and non-blocking.

15. **[Low] SDMX references / CLAUDE.md** — the design asserted "Per SDMX-REST 2.1 the `componentID`
    segment is one component id or `all`" with no citable source.
    - Evidence: only `sdmx-rest-2.2.0/` (the **3.0** spec) exists in the workspace; no 2.1 REST doc.
      CLAUDE.md explicitly forbids fabricating documented-pattern claims.
    - Resolution: **Accepted** — all 2.1 grammar claims reworded as empirically established against
      OECD NSI 8.19, with an explicit caveat block in SDMX Standard References.

16. **[Low] Assorted** — `application.yaml` is camelCase, not kebab-case (lines 44-67); the
    `sdmxBeans` **parameter** of `toAvailabilityQuery21` (line 649) becomes fully unused, not just a
    local; `RateLimitRetryClient` placement in `registry/api/client/` sits oddly beside the six
    `SdmxApiBase` interfaces and a factory-shaped wrapper cuts against the project's
    `Provider`-over-`Factory` preference; the `e2e-report.md` pointer is a gitignored build artifact.
    - Resolution: **Accepted** — yaml key corrected; parameter removal specified with its call site;
      wrapper moved to a new `registry/api/http/` package behind a `RateLimitRetryClientProvider`
      `@Service`; report pointer annotated.

**Closed as already-satisfied (verified, no design change needed):** edge case 6 asked to "confirm
`GlobalExceptionHandler` maps 429 sensibly" — `GlobalExceptionHandler.java:105` already does
`HttpStatus.resolve(ex.status())`. Both critics independently confirmed the final (non-discarded) 429
response cannot leak, since feign's `ResponseHandler` closes the body on the exception path
regardless of `doNotCloseAfterDecode`; the requirement to close *discarded* 429s inside the loop
stands. Both also confirmed no regression risk to BIS/IMF: `replaceEmptyDimensionsWithWildcard`,
`mergeAllWildcardKey`, `convertKeyToFilters` and `unwrapStarComponentId` are set only on `SDMX_3_0`
blocks, and every change here is version-gated. Cache safety was verified independently: data and
availability responses are not cached, and `CacheKeyGenerator.generateLimitEmulationKey` is computed
from the post-translator key.

**Discarded (unverified):** none — every finding raised by either critic held up against the code.

### Iteration 2 — 2026-07-31

Two fresh critics, told what iteration 1 changed and instructed not to re-raise resolved findings.
One targeted the newly added material, one targeted internal coherence after the heavy editing.
Findings deduped; all re-verified by the main agent.

**Critic findings (verified):**

1. **[High] Verification / `LimitEmulationServiceImplTest.java:61,318,366`** — a **fourth** test suite
   breaks, and it was absent from the iteration-1 breakage table (which had presented itself as the
   verified, corrected list).
   - Evidence: line 61 constructs the service with 6 collaborators (Step 7 adds a 7th); line 318
     asserts `getComponentId()).isEqualTo("FREQ,REF_AREA")`, which Step 7 replaces with `"all"`;
     line 366 asserts `shrunk.getKey()).isEqualTo("*")` in a `baseQuery21` test, which becomes
     `"all"`. Both critics found this independently. I confirmed the discrimination they drew:
     line 145 carries the identical assertion but uses `baseQuery30`, and line 303 is unaffected
     because both key positions are concrete.
   - Resolution: **Accepted** — added to Files Affected and to the breakage table with the exact
     assertions, expected new values, and the unaffected lines called out.

2. **[High] Step 2 sizing** — `slowCallDurationThreshold > 429 wait budget` is short by roughly an
   order of magnitude, because the breaker times the sleeps **plus** the network time of every attempt.
   - Evidence: OECD sets `readTimeout: 120000`; `application.yaml:48` sets `defaultReadTimeout: 240000`
     (not the 30000 in the Java default). With `maxAttempts: 6` the span is ~135 s sleep + 6 x 120 s
     network. Also confirmed via disassembly that a non-recorded exception still reaches
     `onSuccess(duration)` and is slow-call accounted, and that `slowCallRateThreshold` is already at
     its maximum of 100 so duration is the only lever.
   - Resolution: **Accepted** — rule restated as
     `maxTotalWaitMillis + maxAttempts * readTimeout`, with the resolved-timeout caveat and a
     two-direction unit test.

3. **[High] Steps 2 and 3 are not version-gated, contradicting the iteration-1 no-regression
   conclusion** — both are installed in `buildClient`, which runs for every registry, so BIS and IMF
   would silently acquire a multi-minute retry loop and lose slow-call detection.
   - Evidence: `getOrCreateCircuitBreaker` and `rateLimitRetryClientProvider.wrap` are both called
     unconditionally from `SdmxApiClientProviderImpl.buildClient` (lines 153-163); there is no
     per-registry slow-call knob in `RegistryCircuitBreakerConfig`. The iteration-1 Review Log
     sentence "every change here is version-gated" is true of Steps 4-7 and false of Steps 2-3.
   - Resolution: **Accepted, with a design change** — introduced an opt-in `rateLimitRetry.enabled`
     flag defaulting to **false**. Both the wrapper and the relaxed threshold are now gated on it, so
     3.0 registries keep today's behaviour exactly. This also forced `rateLimitRetry` onto a dedicated
     `RegistryRateLimitRetryConfig` class rather than reusing `RegistryRetryConfig`, which resolves
     finding 4 as a side effect. The Solution section now states the gating for both families
     explicitly, and Step 8 adds `"enabled": true` to the OECD config.

4. **[Medium] Step 1 self-contradiction on `maxTotalWaitMillis`** — "the four fields are exactly what
   is needed" followed by "add a fifth"; owning class unspecified; absent from the Step 3 snippet and
   the Step 8 JSON; and its proposed default `maxAttempts * maxIntervalMillis` never binds (300-360 s
   against a real worst case of 75-135 s).
   - Evidence: `RegistryRetryConfig` has exactly four fields and `getOrCreateRetry` reads only those,
     so a fifth there would be settable-but-ignored on `retry`.
   - Resolution: **Accepted** — dedicated config class (see 3), exact defaults tabulated, default
     pinned to the **exact** geometric sum so it actually binds, added to the client snippet and to
     the OECD JSON.

5. **[Medium] `buildKey` rationale does not survive scrutiny** — adding the registry name does not fix
   the hot-reload case it was justified by.
   - Evidence: `BaseRegistryTestSuite` (lines 826-895) mutates only `supportedFormats` /
     `defaultFormat` and re-posts; the name stays `OECD` and the URLs are unchanged, so the key is
     identical and the stale client is still returned.
   - Resolution: **Accepted** — change kept for the shared-base-URL case, rationale corrected, and the
     hot-reload limitation explicitly recorded as **still open**.

6. **[Medium] Solution C stale after the iteration-1 edit** — claimed "four rules" and "all three
   rules" in adjacent sentences, and claimed dual placement that Step 5 does not implement for the
   path-slot rule.
   - Evidence: Step 5 applies only `toKey` / `toComponentId` / `toReferences`; `toSdmx21PathSlot` is
     applied only in Step 6's `getFlowRef`.
   - Resolution: **Accepted** — Solution C rewritten to state the split placement and why the
     translator deliberately keeps the untranslated version; Edge Case 1 and the Files Affected
     description updated.

7. **[Medium] Step 7 omitted the constructor injection** it depends on, and reintroduced the dead
   version guard that iteration-1 finding 9 removed.
   - Evidence: `LimitEmulationServiceImpl` is `@RequiredArgsConstructor` with six `private final`
     collaborators; `probe21`/`finalize21` are reachable only from `runBisect21`.
   - Resolution: **Accepted** — injection instruction added, guard dropped, `toKey` called
     unconditionally.

8. **[Medium] `componentId=all` rests on a status code, not on content** — nothing proves the `all`
   response carries the same per-dimension value lists as the comma-joined form, and a mismatch
   degrades limit emulation silently rather than loudly.
   - Evidence: `doProbe` (lines 596-604) only logs `Availability parser returned EMPTY projection`
     and returns; `BaseRegistryTestSuite:804` hardcodes the componentId segment to `all`, so E2E
     cannot catch it either.
   - Resolution: **Accepted** — added a concrete manual `KeyValue`-diff check and a recommendation to
     assert a non-empty projection in the 2.1 limit diagnostic.

9. **[Medium/Low] Package and naming inconsistencies introduced by the iteration-1 edit** — the
   design named `registry/api/client/` in the Step 3 header and the test path but
   `registry/api/http/` in the prose and Files Affected; and `toPathSlot` sits one word away from the
   existing `QueryTranslator.normalizePathSlot`, which is its exact inverse
   (`"all"` -> `"*"`, `QueryTranslatorImpl.java:72`).
   - Resolution: **Accepted** — `registry/api/http/` everywhere including the test; method renamed to
     `toSdmx21PathSlot` with a javadoc note and a clarifying paragraph in Step 4.

10. **[Low] Step 8 key edits under-enumerated** — the second data key `"USA.EMP.......A"` (9 positions,
    line 70) was never mentioned, "add `*` as a second key on one dataflow" did not say which, and the
    availability block has two `"all"` keys.
    - Evidence: keys confirmed at lines 63, 70, 90, 105, 112.
    - Resolution: **Accepted** — replaced with a line-by-line table covering all six edits.

11. **[Low] `application.yaml` missing from the plan** — every other resilience default is spelled out
    there (lines 44-67) even though the Java classes carry defaults.
    - Resolution: **Accepted** — added to Step 1 and to Files Affected.

**Discarded (unverified):** none. Both critics also re-verified large parts of the design and found
them sound — notably that `getFlowRef` has exactly two callers and is a strict no-op for 3.0, that
`toSdmx21PathSlot` on the agency slot cannot break routing (routing happens in
`selectRegistryAndVersion`, long before `getFlowRef`), that dropping the `SdmxBeans` parameter from
`toAvailabilityQuery21` compiles because line 556 still uses it for `doProbe`, that `KeyParserImpl.parseKey`
treats empty positions and `*` identically so the new 2.1-form key survives the limit-emulation
round-trip, that `DimensionServiceImpl` ignores the version argument so P4's `version=*` case cannot
break DSD resolution, and that the P1-P5 numbering and every sampled line citation are consistent.

### Loop ended after iteration 2 — stopped by the user, not by convergence

Iteration 3 was cancelled before its critics ran. The loop therefore exited on user instruction, **not**
on the convergence condition: iteration 2 still produced three verified High findings, and the fixes
applied for them have not themselves been adversarially reviewed.

Carry into implementation as the highest-risk, least-reviewed areas:

- The **opt-in `rateLimitRetry.enabled` flag** and the new `RegistryRateLimitRetryConfig` class were
  introduced late, in response to iteration-2 finding 3, and no critic has seen them. In particular
  the gating of `slowCallDurationThreshold` inside `Resilience4jComponentFactory.getOrCreateCircuitBreaker`
  needs checking against that method's real signature and available data while implementing.
- The `slowCallDurationThreshold` sizing formula (`maxTotalWaitMillis + maxAttempts * readTimeout`)
  is derived, not measured. Verify it empirically during the P1 burst test.
- The `componentId=all` content equivalence (iteration-2 finding 8) is still an open empirical
  question, with a manual check specified but not yet run.
