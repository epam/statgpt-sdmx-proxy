# Design 017: Replace bisect midpoint with proportional re-step

**Status:** implemented.

## Notation

Quick reference for the symbols used throughout this document. See
`ALGORITHM_GUIDE.md` (sections 1.1-1.9) in this folder for fuller definitions and
worked examples.

| Symbol                    | Meaning                                                                                                                                                                                        |
|---------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `N`                       | The client's `limit` parameter -- requested maximum number of series.                                                                                                                          |
| `tolerance`               | Per-registry overshoot factor (`limitEmulationTolerance`, default `1.2`).                                                                                                                      |
| `target`                  | `floor(N * tolerance)` -- upper edge of the feasible band `[N, target]`.                                                                                                                       |
| `dim` (`d`)               | A single non-time dimension we may narrow (e.g. `REF_AREA`).                                                                                                                                   |
| `dimSize`                 | Number of values the registry returned for that dim in the availability projection.                                                                                                            |
| `k`                       | Number of values we're keeping on the current dim. Integer in `[1, dimSize]`.                                                                                                                  |
| `state[d]`                | The list of values we're keeping for dim `d` (i.e. `first k values of A_d`).                                                                                                                   |
| `kLow`                    | Largest `k` that has produced an undershoot (`SC < N`) so far. Lower bound on the bisect.                                                                                                      |
| `kHigh`                   | Smallest `k` that has produced an overshoot (`SC > target`) so far. Upper bound on the bisect.                                                                                                 |
| `kCurrent`, `k_current`   | The `k` of the *most recent* probe -- input to the re-step formula.                                                                                                                            |
| `SC` (or `series_count`)  | Series count returned by the registry's `/availability` for the current filter.                                                                                                                |
| `scCurrent`, `SC_current` | The `SC` of the *most recent* probe -- input to the re-step formula.                                                                                                                           |
| `M`                       | Series count from the last probe used to decide the next move. After warm-start, `M = SC` of the warm-start probe; after each subsequent probe, `M` may be updated to the latest overshoot SC. |
| `lastOvershootSc`         | The most recent `SC` value above `target` -- what we expect the registry to return if we lock the dim at `kHigh`.                                                                              |
| `budget`                  | `limitEmulationProbeBudget` -- maximum number of `/availability` probes per request (default `8`).                                                                                             |

## Context

The limit-emulation bisect (design 014) shrinks the cube one dim at a time. After the
warm-start probe gives an `(SC, kHigh)` data point, subsequent bisect probes used the
plain midpoint `(kLow + kHigh) / 2` to pick the next `k`. That works in worst case
(`O(log n)` probes), but it ignores the magnitude of the overshoot / undershoot we just
observed. When the target lies *asymmetrically* inside `[kLow, kHigh]` -- which is the
common case right after a warm-start -- midpoint over-corrects:

A representative production run on BIS WS_EER (`limit=80`, `target=96`):

| Probe | k      | SC     | Result                                              |
|-------|--------|--------|-----------------------------------------------------|
| 1     | (init) | 181    | shrink needed                                       |
| 2     | 34     | 100    | overshoot by 4 series; `kHigh = 34`                 |
| 3     | **17** | **48** | midpoint `(0+34)/2`; **deep undershoot** (50% miss) |
| 4     | 25     | 76     | midpoint `(17+34)/2`; still undershoot              |
| 5     | 29     | 86     | midpoint `(25+34)/2`; in-band, lock                 |

After probe 2 the algorithm was 4 series above target; the right answer was `k=33` (one
country fewer). Midpoint cut all the way to `k=17`, then the loop spent three more
probes climbing back. **5 probes total for what should have been 3.**

## Root cause

Plain midpoint is the right move when nothing is known about the function's shape. But
the bisect orchestration *does* have a current `(k, SC)` data point and a known
`target`. Under the same locally-linear cube assumption the warm-start formula uses
(`SC ≈ density * k`), the next k that lands at `target` is

```
k_next = ceil(target * k_current / SC_current)
```

In the production scenario above:

- after probe 2: `k_current = 34`, `SC_current = 100`, `target = 96`.
- `k_next = ceil(96 * 34 / 100) = ceil(32.64) = 33`. Single-step down.

This is the same warm-start formula, just re-applied to the most recent probe. Each
step is informed by the freshest SC, not by an interval midpoint.

## Fix

Replace `BisectCalculator.bisectMidpoint(kLow, kHigh)` with
`proportionalReStep(kCurrent, scCurrent, target, kLow, kHigh)`:

```java
public int proportionalReStep(
        int kCurrent, long scCurrent, long target,
        int kLow, int kHigh
) {
    if (scCurrent <= 0L) {
        return kHigh - 1;          // extreme undershoot -> step toward upper bound
    }
    long numerator;
    try {
        numerator = Math.multiplyExact(target, (long) kCurrent);
    } catch (ArithmeticException overflow) {
        numerator = Long.MAX_VALUE;
    }
    long k = ceilDiv(numerator, scCurrent);
    if (k <= (long) kLow) k = (long) kLow + 1L;
    if (k >= (long) kHigh) k = (long) kHigh - 1L;
    return (int) k;
}
```

Clamp to `(kLow, kHigh)` (strictly between known boundaries) preserves the bisect
invariant: never re-probe a known overshoot or known undershoot. When the linear
extrapolation would land outside the known interval, the clamp falls back to
"one-past-the-best-known-side" -- so on a sharply non-linear cube the algorithm
degrades to bisect-like behavior rather than loops.

Convergence still uses the same `kHigh - kLow <= 1` integer-gap exit. With re-step,
that exit fires faster than with midpoint when the cube is roughly linear.

## Behavior on representative scenarios

### Production scenario (BIS WS_EER, limit=80)

```
Probe 1 (init):           SC=181 -> shrink needed
Probe 2 (warm-start k=34): SC=100 -> overshoot, kHigh=34
Probe 3 (re-step):
  k = ceil(96 * 34 / 100) = 33
  SC=96 -> in-band -> LOCK at k=33
Total: 3 probes (vs 5 with midpoint).
```

Verified end-to-end against BIS via `./gradlew :sdmx-proxy:bootRun` and the original
client request -- log output confirms `probes=3, finalM=96`.

### Tight band (limit=4, target=4)

```
Probe 1 (init): SC=181 -> shrink needed
Probe 2 (warm-start k = ceil(4 * 64 / 181) = 2): SC=4 -> in-band -> LOCK
Total: 2 probes.
```

### Large overshoot (high-density cube)

`kCurrent=34, SC=10000, target=96`:

```
k_next = ceil(96 * 34 / 10000) = 1
clamp to (kLow=0, kHigh=34) = 1
```

Aggressive cut, equivalent to midpoint here (or more aggressive). No regression on the
"slash hard" case.

### Sharply non-linear cube

If a probe lands outside the local-linear prediction (e.g. cube has dense and sparse
regions), the next re-step prediction can fall outside `(kLow, kHigh)`. The clamp
collapses it to `kLow + 1` or `kHigh - 1`, which is a one-step probe at the very edge
of the known interval. The integer-gap exit then fires within at most one or two more
probes. Net effect: re-step never does worse than midpoint by more than a constant
factor on adversarial cubes.

## Files affected

### Modified

- `sdmx-proxy/.../services/limit/BisectCalculator.java` -- removed
  `bisectMidpoint`; added `proportionalReStep`. Doc comment explains the
  asymmetric-target motivation and clamp behavior.
- `sdmx-proxy/.../services/limit/LimitEmulationServiceImpl.java` --
  `bisectOneDim` calls `proportionalReStep(k, sc, target, kLow, kHigh)` instead of
  `bisectMidpoint(kLow, kHigh)`. The local variables `k` and `sc` from the loop carry
  the just-probed `(k, SC)` pair; no other changes to the bisect state machine.
- `sdmx-proxy/src/test/.../limit/BisectCalculatorTest.java` -- removed midpoint
  tests; added re-step tests covering: small overshoot (production scenario), large
  overshoot, undershoot step-up, clamp to `kLow + 1`, clamp to `kHigh - 1`, zero SC,
  multiplication overflow, minimum-span clamp.
- `sdmx-proxy/src/test/.../limit/LimitEmulationServiceImplTest.java` -- two tests
  updated for the new probe trajectory: `getShrunkQuery_bisect_overshootThenIntoBand`
  (re-step picks k=18 instead of midpoint k=15; mocked projection adjusted) and
  `getShrunkQuery_noOvershootOnFirstDim_revertsAndTriesNextDim` (re-step converges in
  fewer probes; mock list trimmed accordingly).

### No changes

- `BisectCalculator.proportionalWarmStart` -- already in use for the first probe of
  every dim's bisect; conceptually identical math, separate method only because the
  parameters and clamp range differ slightly (`[1, dimSize - 1]` for warm-start vs
  `(kLow, kHigh)` for re-step).
- The dim-ordering, snapshot/revert, in-band detection, and budget-exhaustion logic
  in `bisectOneDim` -- unchanged.
- Algorithm flowchart, architecture, sequence diagrams, and the limit-emulation
  walkthrough in design 014 are unaffected by this change in spirit (the bisect step
  formula is an implementation detail of "pick next k inside `(kLow, kHigh)`").
  Update those only if the difference becomes confusing for new readers.

## Verification

- Unit tests:
  ```
  ./gradlew :sdmx-proxy:test --tests "com.epam.sdmxproxy.services.limit.BisectCalculatorTest"
  ./gradlew :sdmx-proxy:test --tests "com.epam.sdmxproxy.services.limit.LimitEmulationServiceImplTest"
  ```
  Both green.
- Integration: live request against BIS WS_EER reproduces the 3-probe trajectory
  documented above, `finalM=96` exactly. Logged at INFO under
  `c.e.s.s.l.LimitEmulationServiceImpl: Bisect probe ...` and `Bisect lock ...`.

## Open considerations / not done

- **No metrics on re-step convergence rate.** The current logging captures
  `(k, kLow, kHigh, SC)` per probe at INFO; that is sufficient to inspect convergence
  by hand. If a registry consistently triggers many re-step probes per dim, we'd see
  it in logs. A formal histogram would only be worthwhile if log-grepping proves
  insufficient.
- **No fallback to plain midpoint.** The clamp to `(kLow, kHigh)` is the implicit
  fallback. If a future cube proves pathological enough to oscillate inside that
  interval (re-step bouncing between `kLow + 1` and `kHigh - 1`), introduce an
  explicit "switch to midpoint after K oscillations" rule. Not anticipated.
- **No change to the lock band**. The band remains `[N, target] = [N, floor(N *
  tolerance)]`. This design only changes how the bisect picks the next `k`; whether
  the band is wide enough is a separate question that may surface again on registries
  whose density estimate skews differently.
