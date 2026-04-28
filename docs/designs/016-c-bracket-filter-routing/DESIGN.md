# Design 016: Route all SDMX 3.0 filters through `c[]`, never mix with the path key (BIS bug workaround)

**Status:** investigation complete; implementation deferred to next iteration.

**Affected endpoints:** `/availability` and `/data`. Both inbound forwarding and the
internal limit-emulation probe path.

## Notation

Symbols and SDMX terms used in this document. See `017-bisect-proportional-restep/
ALGORITHM_GUIDE.md` for the broader algorithm vocabulary.

| Term                  | Meaning                                                                                                                                                                      |
|-----------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `path key`            | The positional dimension key in the URL path (e.g. `M.*.*.AE` -- four positions for FREQ, EER_TYPE, EER_BASKET, REF_AREA). `*` at a position means "any value for this dim". |
| `c[X]=...`            | Query-parameter filter on dim/measure/attribute `X`. SDMX 3.0 syntax. Multiple values OR-joined with `,`; operators (`ge:`, `le:`, ...) AND-joined with `+`.                 |
| `c[]` projection      | Per-dim list of values BIS reports back inside `cubeRegions[].keyValues[]` of an availability response -- the actual codes that have data under the filter.                  |
| `series_count` (`SC`) | Annotation on the data constraint in the availability response. The number of series the registry reports for the filtered cube.                                             |
| `M`                   | The `SC` value used as the working "current cube size" by the bisect loop -- the most recent SC observation.                                                                 |
| `N`                   | Client `limit`.                                                                                                                                                              |
| `target`              | `floor(N * tolerance)`; upper edge of the feasible band the bisect aims for.                                                                                                 |
| "narrowing"           | Reducing the set of values the registry considers for a dim, either by listing fewer codes in the path key or by adding/tightening a `c[]` filter.                           |
| `convertKeyToFilters` | The per-endpoint config flag added by this design that tells `AdapterRouterImpl` to normalize outbound requests into `key=*` plus `c[]`-only narrowing.                      |

## Context

Production logs from a BIS limit-emulation request showed pathological behavior in the
shrink loop: the probe budget (8) was exhausted with `series_count` barely moving from
`181` to `182`, while we narrowed `c[REF_AREA]` from 64 countries down to a single
country (`AE`). After the request the proxy still returned the correct payload (the
truncator did its job), but the bisect itself was useless -- every probe burned ~150ms
of HTTP latency without informing the next decision.

Root cause turned out to be on the **registry side**: BIS / FusionRegistry mishandles
the combination of *partially-narrowed path key* + `c[]` filter on an enum dimension on
both `/availability` and `/data` endpoints. The proxy's SDMX 3.0 emulation path uses
exactly this combination today (path key from the client, `c[]` filters added by the
bisect), so we trip the bug on every probe. The same bug also fires for plain client
requests when the path key has some narrowing and a separate `c[]` filter is set on a
different enum dim -- even outside of the emulation flow.

This document records what was observed, the experiments that pinned it down, the
hypothesis we have for the registry behavior, and the proposed proxy-side workaround.
**No code changes are made by this document.** Implementation will follow once the plan
is reviewed.

## Symptoms in production

A representative limit-emulation run from `logs.txt`:

| Probe # | URL fragment                                          | `series_count` | Notes                                                                                 |
|---------|-------------------------------------------------------|----------------|---------------------------------------------------------------------------------------|
| 1       | `M.*.*.*` (no `c[REF_AREA]`)                          | 181            | Correct: M-only series, all REF_AREA                                                  |
| 2       | `M.*.*.*` + `c[REF_AREA]=AE,AR,...,JP` (34 countries) | **231**        | **Higher than initial!** Logically impossible if `series_count` were a filtered count |
| 3       | `M.*.*.*` + `c[REF_AREA]=AE,...,DK` (17)              | 205            | Decreases monotonically from here on...                                               |
| 4       | `M.*.*.*` + `c[REF_AREA]=AE,...,BR` (8)               | 192            |                                                                                       |
| 5       | `M.*.*.*` + `c[REF_AREA]=AE,...,AU` (4)               | 187            |                                                                                       |
| 6       | `M.*.*.*` + `c[REF_AREA]=AE,AR` (2)                   | 183            |                                                                                       |
| 7       | `M.*.*.*` + `c[REF_AREA]=AE` (1)                      | 182            | Still 90x the real count (~2)                                                         |
| 8       | `M.*.*.*` + `c[REF_AREA]=AE,c[EER_BASKET]=B`          | 182            | Identical                                                                             |

Two things are obviously wrong:

1. **Non-monotonic**: narrowing the filter increased `series_count` (181 -> 231).
2. **Floor of ~182**: even at the maximally narrow `c[REF_AREA]=AE`, BIS reports 182,
   while the real series count for `(FREQ=M, REF_AREA=AE)` is 2.

## Investigation

### Method

Direct HTTP requests were issued to `https://stats.bis.org/api/v2` against
`/availability/.../FREQ,EER_TYPE,EER_BASKET,REF_AREA?...` and
`/data/...?firstNObservations=1`. A separate sweep compared the bare `*` path key
against the explicit `*.*.*.*` form to confirm that `mergeAllWildcardKey=true`
on BIS gives a clean route.

Each query was issued with combinations of:

- path key (`*`, `*.*.*.*`, `M.*.*.*`, `*.*.*.AE`, `M.*.*.AE`, `M.*.*.AE+US`, etc.);
- `c[]` filters on enum dims (`c[REF_AREA]`, `c[FREQ]`) with comma-OR and `+`-OR
  separators;
- the same `c[TIME_PERIOD]=ge:2021-04-01+le:2026-04-30` operator filter the production
  request used.

For availability we extracted `series_count` and per-dim projection sizes. For data we
counted top-level series and recorded body bytes / wall time.

### Availability results

Summary:

| Group | Label                                         | path key             | extra `c[]`                 | SC      | FREQ proj | EER_TYPE | EER_BASKET | REF_AREA proj |
|-------|-----------------------------------------------|----------------------|-----------------------------|---------|-----------|----------|------------|---------------|
| A1    | full wildcard (4 stars)                       | `*.*.*.*`            | (none)                      | 0       | -         | -        | -          | -             |
| A2    | M-only via key                                | `M.*.*.*`            | (none)                      | 181     | 1         | 2        | 2          | 64            |
| A3    | REF_AREA=AE via key                           | `*.*.*.AE`           | (none)                      | 3       | 2         | 2        | 1          | 1             |
| A4    | M + AE via key                                | `M.*.*.AE`           | (none)                      | **2**   | 1         | 2        | 1          | 1             |
| A5    | M + 2 countries via key                       | `M.*.*.AE+US`        | (none)                      | 6       | 1         | 2        | 2          | 2             |
| A6    | M + 34 countries via key                      | `M.*.*.AE+AR+...+JP` | (none)                      | **100** | 1         | 2        | 2          | 34            |
| B1    | M via key, `c[REF_AREA]=AE`                   | `M.*.*.*`            | `c[REF_AREA]=AE`            | **182** | **2** !!  | 2        | 2          | **64** !!     |
| B2    | M via key, `c[REF_AREA]=US`                   | `M.*.*.*`            | `c[REF_AREA]=US`            | 183     | 2         | 2        | 2          | 64            |
| B3    | M via key, `c[REF_AREA]=AE,US` (comma)        | `M.*.*.*`            | `c[REF_AREA]=AE,US`         | 184     | 2         | 2        | 2          | 64            |
| B4    | M via key, `c[REF_AREA]=AE+US` (plus, bogus)  | `M.*.*.*`            | `c[REF_AREA]=AE+US`         | 181     | **1**     | 2        | 2          | 64            |
| B5    | M via key, `c[REF_AREA]=ZZ` (bogus)           | `M.*.*.*`            | `c[REF_AREA]=ZZ`            | 181     | **1**     | 2        | 2          | 64            |
| B6    | M via key, 34 countries via `c[]`             | `M.*.*.*`            | `c[REF_AREA]=AE,...,JP`     | **231** | **2** !!  | 2        | 2          | 64            |
| C1    | wildcard key, `c[REF_AREA]=AE`                | `*.*.*.*`            | `c[REF_AREA]=AE`            | **3**   | 2         | 2        | 1          | 1             |
| C2    | wildcard key, `c[REF_AREA]=AE+US` (bogus)     | `*.*.*.*`            | `c[REF_AREA]=AE+US`         | 0       | -         | -        | -          | -             |
| C3    | wildcard key, `c[FREQ]=M`                     | `*.*.*.*`            | `c[FREQ]=M`                 | **181** | **1**     | 2        | 2          | 64            |
| C4    | wildcard key, `c[FREQ]=M, c[REF_AREA]=AE`     | `*.*.*.*`            | `c[FREQ]=M, c[REF_AREA]=AE` | **2**   | 1         | 2        | 1          | 1             |
| D1    | REF_AREA via key, `c[FREQ]=M`                 | `*.*.*.AE`           | `c[FREQ]=M`                 | 182     | 2         | 2        | 2          | 64            |
| D2    | REF_AREA via key, `c[REF_AREA]=US` (mismatch) | `M.*.*.AE`           | `c[REF_AREA]=US`            | 8       | 2         | 2        | 2          | 2             |

Cells annotated with `!!` violate at least one of: monotonicity (filtering should not
*increase* SC), correctness of the projection (the path key should narrow the FREQ
projection to `M`), or correctness of the count (one country should give SC ~ a few,
not 182).

#### `*` vs `*.*.*.*`

| Path key  | `c[]`                       | SC    | FREQ proj | REF_AREA proj |
|-----------|-----------------------------|-------|-----------|---------------|
| `*`       | (none)                      | 271   | 2         | 64            |
| `*.*.*.*` | (none)                      | **0** | -         | -             |
| `*`       | `c[FREQ]=M`                 | 181   | 1         | 64            |
| `*.*.*.*` | `c[FREQ]=M`                 | 181   | 1         | 64            |
| `*`       | `c[FREQ]=M, c[REF_AREA]=AE` | 2     | 1         | 1             |
| `*.*.*.*` | `c[FREQ]=M, c[REF_AREA]=AE` | 2     | 1         | 1             |

`*.*.*.*` alone produces an empty constraint (BIS quirk). With at least one `c[]` the
two forms are equivalent. BIS's `mergeAllWildcardKey=true` already collapses
`*.*.*.*` -> `*` for outbound requests, so we naturally avoid the empty-constraint
quirk.

### Data results

Same key+`c[]` matrix, same dataflow, `firstNObservations=1`:

| Label                                  | path key             | `c[]`                             | series  | body (B) | t (ms) |
|----------------------------------------|----------------------|-----------------------------------|---------|----------|--------|
| A2 baseline                            | `M.*.*.*`            | (none)                            | 181     | 38 237   | 388    |
| A4 path-key-only narrow                | `M.*.*.AE`           | (none)                            | **2**   | 3 974    | 216    |
| A5 path-key-only narrow (2 cty)        | `M.*.*.AE+US`        | (none)                            | 6       | 4 805    | 199    |
| A6 path-key-only narrow (34 cty)       | `M.*.*.AE+AR+...+JP` | (none)                            | 100     | 22 645   | 260    |
| B1 mixed: `M.*.*.*` + `c[REF_AREA]=AE` | `M.*.*.*`            | `c[REF_AREA]=AE`                  | **182** | 38 457   | 1 361  |
| B3 mixed comma-OR                      | `M.*.*.*`            | `c[REF_AREA]=AE,US`               | 184     | 38 620   | 693    |
| B6 mixed 34 cty                        | `M.*.*.*`            | `c[REF_AREA]=AE,...,JP`           | **231** | 42 475   | 1 838  |
| C1 wildcard + c[REF_AREA]=AE           | `*.*.*.*`            | `c[REF_AREA]=AE`                  | **3**   | 4 190    | 260    |
| C3 wildcard + c[FREQ]=M                | `*.*.*.*`            | `c[FREQ]=M`                       | 181     | 38 247   | 232    |
| C4 wildcard + both                     | `*.*.*.*`            | `c[FREQ]=M, c[REF_AREA]=AE`       | **2**   | 3 998    | 458    |
| **PROD shrunk filter from logs**       | `M.*.*.*`            | `c[REF_AREA]=AE, c[EER_BASKET]=B` | **182** | 38 473   | 298    |

The last row is the exact filter the emulation produced and shipped to BIS -- and BIS
ignored it, returning 182 series instead of the expected 1-2. The `/data` endpoint
exhibits the same Group-A vs B vs C pattern as `/availability`.

### Findings

1. **Path-key-only narrowing always works** (Group A, both endpoints). `series_count`
   is the actual filtered count and per-dim projections reflect the constraints.
   `M.*.*.AE` returns SC=2 / 2 series; `M.*.*.AE+US` returns SC=6 / 6 series;
   `M.*.*.<34 countries>` returns SC=100 / 100 series.
2. **Wildcard-path + `c[]`-only narrowing also works** (Group C, both endpoints).
   `c[REF_AREA]=AE` gives SC=3 / 3 series; `c[FREQ]=M + c[REF_AREA]=AE` gives SC=2 / 2
   series.
3. **Mixed (partially-narrowed path key + `c[]` on an enum dim) is broken** (Group B,
   both endpoints).
    - `series_count` does not reflect the filter accurately and is even non-monotonic.
    - The path key constraint is *partially forgotten*: A2 (`M.*.*.*` alone) returns
      `FREQ=[M]` in the projection, but B1 (`M.*.*.*` + valid `c[REF_AREA]=AE`) returns
      `FREQ=[D, M]`.
    - The `c[REF_AREA]` filter is *also forgotten in the projection*: B1 reports
      `REF_AREA` size=64 (the full set), not `[AE]`.
    - The `/data` response confirms: 182 series instead of ~2.
4. **Invalid `c[]` value disables the buggy code path**: B4 (`AE+US` -- treated as the
   single literal "AE+US", which doesn't exist as a code) and B5 (`ZZ`, also bogus)
   *both* produce SC=181 with `FREQ=[M]` -- exactly equivalent to A2 (no `c[]` at all).
   It looks like BIS silently drops `c[REF_AREA]` when the value cannot be matched.
5. **`c[TIME_PERIOD]` operator filters work fine**. All Groups send
   `c[TIME_PERIOD]=ge:...+le:...` and the results stay consistent. Operator-based
   filters must go via `c[]` (they cannot be expressed in the path key), and BIS
   handles them correctly even when path-key-only narrowing is in effect.
6. **`*` and `*.*.*.*` are interchangeable as long as at least one `c[]` is present**.
   `*.*.*.*` *alone* triggers an empty-constraint response (SC=0), but BIS's
   `mergeAllWildcardKey=true` already sends `*` over the wire in that scenario.

### Hypothesis on root cause

The bug pattern matches an internal short-circuit: when a `c[X]` filter on an enum
dimension is present and *resolves to at least one valid code*, BIS evidently switches
the resolver (for both /availability and /data) to a code path that treats the path
key as `*.*.*.*` (i.e. ignores it) and returns a constraint that is some sort of
*union* of "matches the `c[]`" and "matches what would have matched the original path
key" -- yielding inflated counts and a projection that includes values not actually
present in the filtered cube. When the `c[]` value is invalid (no match), BIS appears
to skip the `c[]` filter entirely and falls back to the path-key code path -- which
works.

A second-order hypothesis: BIS may be using a precomputed/cached constraint for the
"filter narrows to these codes" case that is keyed only by the `c[]`-mentioned codes
and ignores the surrounding path key. We have no insight into the registry source code
to confirm.

The practical conclusion is the same: **do not mix path-key narrowing with `c[]`
enum-dim narrowing on BIS for either endpoint.**

## Strategy

Two purely-narrowed forms work; a mixed form is broken. We have to pick one of the two
working forms as the proxy's outbound shape for SDMX 3.0.

### Why route through `c[]`, not through the path key

Both forms work for BIS, but `c[]` is strictly more expressive than the path key:

- `c[]` carries operator filters (`ge:`, `le:`, `eq:`, `ne:`, `gt:`, `lt:`, `co:`,
  etc.) -- the path key cannot.
- `c[]` can target measures and attributes -- the path key targets dimensions only.
- `c[]` is the canonical SDMX 3.0 filter mechanism; the path key is a 2.1 holdover.
- Routing through the path key forces us to merge multi-value `c[]` into `+`-OR within
  a position. `FilterTranslatorImpl.mergeFiltersIntoKey` currently truncates to
  `values.get(0)` (see design 014 "Open questions"); we would have to fix that helper
  anyway to preserve multi-value semantics.

Routing everything through `c[]` lets us keep all the SDMX 3.0 expressiveness, avoids
the merge-helper landmine, and matches what a hand-written 3.0 client would produce.

### Concrete shape of outbound BIS requests

For SDMX 3.0 emulation registries (today: BIS), every outbound `/availability` and
`/data` request the proxy issues will have:

- **Path key** = `*` (single star). With `mergeAllWildcardKey=true` already on BIS,
  this is what we send today when nothing in the path is narrowed. The new rule is
  *always* to send `*`, regardless of how the client originally addressed dims.
- **`c[]` filters** carry every constraint:
    - `c[FREQ]=M` (lifted from the client's path key positional constraint, if any),
    - `c[REF_AREA]=DE` (from the client's `c[]` if any),
    - `c[REF_AREA]=AE,AR,...` (added by the bisect during emulation),
    - `c[TIME_PERIOD]=ge:...+le:...` (from the client's operator filter),
    - any client-supplied `c[<measure>]=...` or `c[<attribute>]=...`.
- Multi-value enum filters use **comma-OR** (`c[REF_AREA]=AE,AR`). Comma is the SDMX
  3.0 standard separator within a single `c[]` for OR semantics. BIS handles this
  correctly when the path key is fully wildcard.

The 2.1 emulation path is unchanged: 2.1 has no `c[]`, so it must continue to use the
positional key with `+`-OR within positions, exactly as `runBisect21` already does.

### Why this fixes the production scenario

After the change, the production scenario from `logs.txt` becomes:

- Initial probe: path=`*`, `c[FREQ]=M, c[TIME_PERIOD]=ge:...+le:...`. BIS treats this
  as Group C3 above -> SC=181.
- First bisect probe (narrow REF_AREA to 34 cty): path=`*`, plus
  `c[REF_AREA]=AE,AR,...,JP`. BIS treats this as a Group-C-like wildcard + multi-c[]
  -> expected SC ~ proportional ~ 96 (vs. the broken 231 today).
- The bisect actually converges (no more "everything overshoots" on the broken floor),
  the probe budget is not exhausted, and the request completes in 2-4 probes instead
  of 8.
- The final shrunk query goes to `/data` with the same `*` + multi-`c[]` shape and
  returns ~N series instead of 181.

## Scope

The fix applies to **every outbound request** the proxy issues to a registry that
exhibits this bug (currently only BIS). Five code paths are affected:

1. **Limit-emulation availability probes** (`LimitEmulationServiceImpl.runBisect30`).
2. **Limit-emulation final data call** (`AdapterRouterImpl.resolveRawDataStream`, the
   `genericRegistryAdapter.getData(shrunkQuery)` branch).
3. **Direct `/data` forwarding** for clients that do not trigger emulation (e.g. when
   `limit` is absent or the registry sets `supportsLimit: true`). For BIS this still
   matters because client requests like `M.*.*.*?c[REF_AREA]=DE` reproduce the bug
   independently of emulation.
4. **Direct `/availability` forwarding** -- same reasoning as `/data`.
5. **Bypass paths** (when `bypassEnabled=true` and the format matches). For BIS today,
   bypass is off for data, but the principle still applies: any forwarding code that
   builds a registry URL must use the normalized shape.

### Where the normalization should live

There are two reasonable places:

- **In the per-registry SDMX 3.0 client** (e.g. `Sdmx30AvailabilityClient`,
  `Sdmx30DataClient`): rewrite the request just before it goes out. Pros: localized,
  every caller benefits automatically. Cons: the Feign client is currently a thin
  pass-through; adding logic there blurs the abstraction.
- **In a `FilterNormalizer` helper invoked from `AdapterRouterImpl` before each
  outbound call**: explicit, testable, easy to gate per-registry. Cons: every call
  site must remember to invoke it.

Recommended: a small helper service, applied uniformly inside the SDMX 3.0 emulation
service and the direct-forwarding path. A per-registry config flag (e.g.
`DataEndpointConfiguration.convertKeyToFilters: true`, default false; true for
BIS) controls whether normalization runs. Default off keeps behavior unchanged for
well-behaved registries.

### What stays the same

- `DataEndpointConfiguration.supportsLimit`, `limitEmulationTolerance`,
  `limitEmulationProbeBudget`, `mergeAllWildcardKey` -- unchanged.
- `BisectCalculator`, `CodelistSizeResolver`, `AvailabilityProjection`,
  `KeyParser` -- unchanged.
- `AdapterRouterImpl.resolveRawDataStream` orchestration shape -- unchanged.
- The bisect math and dim ordering -- unchanged.
- `runBisect21` -- 2.1 emulation already uses path-key narrowing because 2.1 has no
  `c[]`; nothing to change there.

## Proposed change (preview)

Outline only -- no code is being changed by this document.

### New code

- `FilterNormalizer` (`@Component`, in `services.filter` or `services.translator`):
  takes a `(key, filtersMultiMap, dsdNonTimeDimIds)` tuple and returns a normalized
  pair where the key is `*` and `filters` carries every dim constraint as a `c[]`
  entry. Operator filters (TIME_PERIOD, measure / attribute predicates) flow through
  unchanged. Multi-value entries use comma separators.

  Pure function modulo the DSD inputs. Unit-tested with a few representative
  scenarios (single positional dim, multi-value c[], TIME_PERIOD operator passthrough,
  empty key, key already wildcard).

- `DataEndpointConfiguration.convertKeyToFilters: boolean` (default `false`):
  per-registry switch. Set to `true` for BIS.

  `AvailabilityEndpointConfiguration.convertKeyToFilters: boolean` (default
  `false`): same idea on the availability endpoint config; both should be true for BIS
  since the bug is on both endpoints.

### Modified code

- `LimitEmulationServiceImpl.runBisect30`: drop the `MultiValueMap filters` mutation
  pattern; instead, parse the client query with `FilterNormalizer.normalize` to obtain
  the normalized filter map, and use that as the bisect's mutable state. Each bisect
  step adds / shrinks a `c[<dim>]=v1,v2,...` entry. The path key sent to availability
  is always `*`.

  When `convertKeyToFilters` is false (non-BIS registry), keep the current
  behavior. This guards against breaking other registries until they're verified.

- `AdapterRouterImpl`:
    - `resolveRawDataStream` -- after `getShrunkQuery`, the returned query already has
      normalized filters; `genericRegistryAdapter.getData(shrunkQuery)` simply uses it.
    - `getAvailability` -- before forwarding to the registry, run the same normalization
      if `convertKeyToFilters` is set on the registry's availability config.
    - `getData` (non-emulation branch) -- same normalization at the start of the
      method, gated by `convertKeyToFilters` on the data config.

- `LimitEmulationServiceImplTest`: tests that asserted on `shrunk.getKey()` for the
  3.0 path now assert on `shrunk.getFilters()` (the filter map carries all the
  narrowing). The shrunk key for 3.0 is always `*`.

- `AdapterRouterImplTest`: new tests for the forwarding normalization on BIS.

- `sdmx_registries_config.json`: add `convertKeyToFilters: true` to BIS's
  `dataEndpointConfig` and `availabilityEndpointConfig`.

- `sdmx-proxy-config/README.md`: schema-table rows for the two new config fields.

- `docs/designs/014-heuristic-limit-emulation/DESIGN.md`: cross-reference to design
  016 in the "Open questions / deferred" section. (No need to rewrite 014 -- the
  algorithm itself is unchanged; only how the filter-vs-key shape is presented to the
  registry.)

## Open considerations

1. **Existing 3.0 client behavior on `FilterTranslatorImpl`**.
   The translator today merges client `c[]` filters into the path key for SDMX 2.1
   routing and may or may not do so for SDMX 3.0. This needs to be inspected: if the
   translator is already producing a partially-narrowed key for 3.0 BIS-bound
   requests, we'll either:
    - keep the translator's output and let `FilterNormalizer` undo the merge before
      send-out (extra work, but localized), or
    - branch the translator on `convertKeyToFilters` so it leaves the client's
      path-key positions in `c[]` from the start.

   The localized normalizer (option A) is safer because it doesn't fork the translator
   logic and can be applied uniformly to *every* outbound request without coordination.

2. **`FilterTranslatorImpl.mergeFiltersIntoKey` truncation bug**.
   Currently truncates multi-value `c[]` to `values.get(0)` on the SDMX 2.1 path. Now
   irrelevant for the 3.0 BIS path (we no longer merge into the key) but still a bug
   on the 2.1 path, where it's masked because `FilterValidatorImpl` rejects multi-
   value `c[]` on 2.1 before the merge. Out of scope for this design but worth
   capturing.

3. **Registries other than BIS**.
   IMF and other SDMX 3.0 registries with `supportsLimit: true` are unaffected --
   emulation does not run for them. For their direct-forwarding paths we leave
   `convertKeyToFilters: false` (default) and only flip it on if we ever see
   the same bug elsewhere.

4. **Bug ticket to BIS / FusionRegistry**.
   This document is the canonical record of the bug. Reproducer URLs from the tables
   above are sufficient to file a ticket. Suggested filing scope:
    - Bug A (highest impact): `series_count` annotation non-monotonic with `c[]`
      narrowing on `/availability` (Group B6 vs A2 above).
    - Bug B: `c[]` filter ignored when path key has positional narrowing on `/data`
      (Group B1 vs A4).
    - Bug C: path key positional narrowing forgotten in availability projection when
      `c[]` is present (Group B1: FREQ projection becomes `[D,M]` despite
      `key=M.*.*.*`).
      These are likely the same underlying bug surfaced through different observables.

5. **Empty filter case**.
   If a client request reaches the proxy with neither a constrained key nor any
   client `c[]` filter (only `?limit=80`), the normalized request becomes `*` with no
   `c[]` at all. On BIS that returns SC=271 (full dataflow) -- which is fine for the
   initial probe. The bisect then narrows from there.

6. **Cache key generation**.
   `CacheKeyGenerator` builds cache keys from request shape. Switching from
   `key=M.*.*.*` + `c[REF_AREA]=AE` to `key=*` + `c[FREQ]=M, c[REF_AREA]=AE` produces
   a different cache key for the same logical query. We need to make sure cache keys
   are normalized consistently (either both inputs go through `FilterNormalizer`
   first, or the cache key builder canonicalizes internally). Otherwise we'd miss
   cache hits for semantically identical client requests.

## Verification plan (when implementation lands)

- Re-run the production failure scenario locally (`./gradlew :sdmx-proxy:bootRun`,
  request `M.*.*.*?limit=80&c[TIME_PERIOD]=...`) and confirm:
    - Initial probe (path=`*`, `c[FREQ]=M`) returns SC=181.
    - First bisect probe (path=`*`, `c[FREQ]=M, c[REF_AREA]=...`) returns a correct SC
      proportional to the cut.
    - In-band landing within 2-4 probes (typical) instead of 8 (current saturated case).
    - `/data` request goes to BIS with path=`*` and full `c[]` and returns the expected
      series count, body size on the order of a few KB instead of 38-520 KB.
- Manual smoke test against IMF: ensure `convertKeyToFilters: false` keeps
  the current behavior intact for registries that handle the mixed shape correctly.
- Add an E2E case (or extend `LimitEmulationE2ETest`) that asserts the registry-side
  `series_count` reported on the second probe is *strictly less than* the first
  probe's `series_count` (a cheap proxy for the bug being absent).
- Add a unit test that hits `FilterNormalizer` with a positional key + multi-value
  `c[]` + `c[TIME_PERIOD]=ge:...+le:...` and verifies the normalized output is
  `(key=*, filters={FREQ=[M], REF_AREA=[AE,US], TIME_PERIOD=[ge:...+le:...]})`.

## Reproducing the investigation

The probe matrices in this document were collected by issuing curl-style requests
against the URLs in the tables above (path key + `c[]` parameter combinations,
`Accept: application/vnd.sdmx.structure+json; version=2.0.0` for availability and
`application/vnd.sdmx.data+json;version=1.0.0` for data). Anyone re-running the
investigation can reproduce the table rows by URL-encoding the corresponding
key/`c[]` shapes and inspecting the `series_count` annotation on the
`/availability` response or the top-level series count in the `/data` response.
