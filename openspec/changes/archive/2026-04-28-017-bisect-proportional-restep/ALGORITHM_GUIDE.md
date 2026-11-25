# Heuristic limit emulation -- algorithm guide

A learning-oriented walkthrough of the limit-emulation algorithm. Read this first if
the code or flowcharts are unclear about what `k`, `kLow`, `dimSize`, `target`, etc.
mean and why we clamp things where we do.

---

## 0. Why we need this algorithm at all

A client sends the SDMX proxy a request like:

```
GET /data/dataflow/BIS/WS_EER/1.0/*?limit=1000
```

`limit=1000` means "give me at most 1000 series". In SDMX 3.0 this is a standard
query parameter and the registry is supposed to enforce it. But **BIS does not
honor `limit`** -- it ignores the parameter. Without emulation, BIS would return,
say, 4000 series (hundreds of megabytes) and the proxy would try to convert them
all and run out of memory.

The emulation idea: before issuing a `/data` request, use the (much cheaper)
`/availability` endpoint to figure out how many series the client's slice contains,
then **narrow the filter** so the registry returns roughly 1000. A final truncator
on our side cuts the response down to exactly 1000.

---

## 1. Glossary -- what's what

### 1.1 Numbers from the client

| Symbol        | What it is                                                                               | Example        |
|---------------|------------------------------------------------------------------------------------------|----------------|
| `N`           | The client's `limit`. How many series the client wants.                                  | `1000`         |
| `T`           | `tolerance` from the registry config. Multiplier for "how much overshoot is acceptable". | `1.2`          |
| `target`      | `floor(N * T)`. Upper edge of "acceptable".                                              | `1200`         |
| `[N, target]` | The **feasible band** -- the series-count range we want to land in.                      | `[1000, 1200]` |
| `budget`      | `limitEmulationProbeBudget`. Maximum number of `/availability` probes per request.       | `8`            |

### 1.2 The cube and its dimensions

A "cube" is the space of all possible series. It has **dimensions** (dims), e.g.
`COUNTRY`, `FREQ`, `UNIT`, `INDICATOR`. Each dimension takes values from its own
codelist.

Running example: a dataflow with three dims (plus time).

| Dimension     | Values present in the cube |
|---------------|----------------------------|
| `INDICATOR`   | 20                         |
| `COUNTRY`     | 100                        |
| `UNIT`        | 10                         |
| `TIME_PERIOD` | (always excluded)          |

| Symbol                 | What it is                                                                                          | Example                            |
|------------------------|-----------------------------------------------------------------------------------------------------|------------------------------------|
| `d`                    | a specific dimension                                                                                | `d = COUNTRY`                      |
| `A_d`                  | the list of values for `d` that **actually appear** in the cube (from the `/availability` response) | `[USA, GBR, DEU, ... 100 entries]` |
| `\|A_d\|` or `dimSize` | the size of that list                                                                               | `100`                              |

The bars `\|...\|` denote "set size"; `dimSize` is the same thing in code. We'll
mostly use `dimSize` from here on.

### 1.3 Series count

| Symbol | What it is                                                                                                                      | Example                 |
|--------|---------------------------------------------------------------------------------------------------------------------------------|-------------------------|
| `TC`   | **Total Combinations** = the product of `dimSize` across all non-time dims. An upper bound, computed locally without any probe. | `20 * 100 * 10 = 20000` |
| `SC`   | **Series Count** -- the actual number of series. Comes from the `/availability` response (`series_count` annotation).           | `4000`                  |
| `M`    | The `SC` from the most recent probe. Used in the formulas.                                                                      | `4000`                  |

> **Note:** `TC` is how many combinations are *theoretically possible*. `SC` is how
> many series the registry actually has. `SC <= TC` always. In our example the cube
> is "20% dense": `4000 / 20000 = 0.2`.

### 1.4 What a `probe` is

A **probe** is one HTTP call to the registry's `/availability` endpoint. It returns:

- `valuesByDimensionId` -- a map `{dim -> list of values present in the cube}`.
  We get `dimSize` from this.
- `series_count` -- the exact series count for the filtered cube. This is our `SC`.

Each probe consumes one unit of `budget`. The default is 8 probes per client request.

### 1.5 What `k`, `kLow`, `kHigh` are

When we decide to narrow a specific dimension `d`, we **keep the first `k` values** of
`A_d` and drop the rest. For example: COUNTRY has 100 entries; if we pick `k=30`, we
keep `[USA, GBR, DEU, ..., 30 entries]`.

| Symbol     | What it is                                                                                                     | Example                  |
|------------|----------------------------------------------------------------------------------------------------------------|--------------------------|
| `k`        | Number of values to keep on dim `d`. Integer in `1 <= k <= dimSize`.                                           | `30`                     |
| `state[d]` | The current list of values kept for `d`. After narrowing, `state[d] = first k values`.                         | `[USA, ..., 30 entries]` |
| `kLow`     | Last `k` that produced an **undershoot** (`SC < N`). Anything below it is definitely too small.                | `15`                     |
| `kHigh`    | Last `k` that produced an **overshoot** (`SC > target`). Anything above it is definitely too large.            | `30`                     |
| `dimSize`  | Size of `A_d`. Initially `kHigh = dimSize` (the full dim is the implicit overshoot we never had to probe for). | `100`                    |

> The bisect logic: the "right" `k` lies somewhere in `[kLow, kHigh]`. Each probe
> either pushes `kLow` up or pulls `kHigh` down. When `kHigh - kLow <= 1`, we've
> converged.

### 1.6 The three outcomes of every probe

After a probe we get `SC` and compare it to the band `[N, target]`:

| Outcome        | Condition           | What we do                               |
|----------------|---------------------|------------------------------------------|
| **In-band**    | `N <= SC <= target` | Hit. Return the shrunk query.            |
| **Overshoot**  | `SC > target`       | Too much. `kHigh = k`. Need to cut more. |
| **Undershoot** | `SC < N`            | Too little. `kLow = k`. Need to ease up. |

### 1.7 What `clamp` and `ceil` mean

- **`ceil(x)`** -- round up. `ceil(2.4) = 3`, `ceil(7.0) = 7`.
- **`clamp(x, low, high)`** -- pin into a range. If `x < low` -> `low`. If `x > high`
  -> `high`. Otherwise `x`.
    - `clamp(150, 1, 99) = 99`
    - `clamp(0, 1, 99) = 1`
    - `clamp(50, 1, 99) = 50`

### 1.8 Snapshot and revert

Before bisecting on each dim `d` we **save a copy** of `state[d]` -- call it the
`snapshot`. If the bisect fails to find an overshoot below the full dim
(outcome `NO_OVERSHOOT_FOUND`), we **revert** `state[d]` to the snapshot. This
prevents leaving an undershoot value behind on a dim we effectively didn't manage
to narrow.

### 1.9 Two formulas for picking `k`

The algorithm uses **the same** formula in two places -- for the first probe of each
dim ("warm-start") and for every subsequent bisect step ("re-step").

```
k_new = ceil(target * k_reference / M_reference)
```

`(k_reference, M_reference)` is the "what we kept / what we got back" pair.

- **Warm-start**: `(k_reference = dimSize, M_reference = M)`. We haven't probed this
  dim yet, so the reference is the full dim and the latest M. Clamp to
  `[1, dimSize - 1]`.
- **Re-step**: `(k_reference = the last k probed, M_reference = the last SC observed)`.
  After every probe we use the freshest pair. Clamp to `(kLow, kHigh)` -- strictly
  inside the known boundaries.

It's the same idea both times: "if the cube is locally linear, what `k` lands at
target?". Warm-start applies the formula at the start; re-step applies it again
after each new measurement.

> **Why `ceil`?** If the cube were **uniform**, `ceil` picks the smallest `k` for
> which the new upper-bound estimate `M' >= target`. `floor` would risk landing
> below the band in a single step.
>
> **Why clamp re-step to `(kLow, kHigh)`?** To keep the bisect invariant: target is
> known to lie inside `[kLow, kHigh]`, so probing outside that range is pointless.
> If the linear extrapolation falls outside (cube is locally non-linear), the clamp
> degrades gracefully to "one step away from the best-known boundary".

---

## 2. Step-by-step walkthrough

Setup:

- `N = 1000`, `T = 1.2`, `target = 1200`, `band = [1000, 1200]`, `budget = 8`.
- Cube: `INDICATOR(20)`, `COUNTRY(100)`, `UNIT(10)`. Real `SC = 4000`.

### Step 0 -- TC fast-path (free, no probe)

Compute locally:

```
TC = INDICATOR_size * COUNTRY_size * UNIT_size = 20 * 100 * 10 = 20000
```

Compare to `target = 1200`:

```
TC (20000) <= target (1200) ?  ->  NO
```

Doesn't fire -- the cube is theoretically too big. Fall through to the probe path.

> **When does the fast-path fire?** When the client has already narrowed the request,
> e.g. `c[COUNTRY]=USA & c[FREQ]=M & limit=1000`. Then `TC = 1 * 1 * 20 = 20 <= 1200`
> -> fast-path hit, **zero probes**, the query is returned as is. Typical for narrow
> client queries.

> **Uncodified dims (text format).** SDMX lets a dim use a text format -- string,
> integer, double, date, etc. -- instead of an enumerated codelist. Such a dim has
> no defined size (the value space is unbounded). For these,
> `CodelistSizeResolver.resolveSize` returns `OptionalInt.empty()`,
> `dimSizeForTcEstimate` returns `-1`, and `computeTotalCombinations` returns `-1`
> -- the fast-path simply doesn't trigger and we go to the probe path. That's a fine
> degradation: the probe path works for any dim because the bisect operates on values
> from the availability projection, which the registry returns regardless of whether
> the dim is codified. If the client narrowed an uncodified dim with an explicit
> `c[STR_DIM]=foo`, `dimSizeForTcEstimate` will use the client filter size (`1`), so
> the fast-path can still fire if every other dim is resolvable too.

### Step 1 -- Initial probe

Send the first `GET /availability/BIS/WS_EER/...`. The registry replies:

```
A_INDICATOR = [I1, I2, ..., I20]    ->  dimSize = 20
A_COUNTRY   = [USA, GBR, ..., 100]  ->  dimSize = 100
A_UNIT      = [USD, EUR, ..., 10]   ->  dimSize = 10
series_count = 4000
```

`M = 4000`. Compare:

```
M (4000) <= target (1200) ?  ->  NO
```

The cube overshoots. `probesIssued = 1`. Continue to Step 2.

> **When does the initial probe close the case?** If `M <= target`, the cube is
> already small enough -- nothing to narrow. Return the query (with `limit=null`).
> 1 probe.

### Step 2 -- Multi-dim bisect

Iterate dims **in reverse DSD order** (last non-time dim first). The SDMX convention
puts coarse dims (FREQ, COLLECTION) first in the DSD and granular dims (REF_AREA,
UNIT) last. Cutting the granular ones first matches what an analyst would do by
hand: keep the broad slices, sample down through the fine-grained tail.

```
DSD order (without TIME_PERIOD):  [INDICATOR, COUNTRY, UNIT]
order for bisect (reverse):       [UNIT, COUNTRY, INDICATOR]
```

Inside the inner bisect, dims with a single value are simply skipped (nothing to
narrow).

For each dim `d` in this list we run an **inner bisect** (see below). After each
inner bisect we read the outcome:

| Inner bisect outcome | What the outer loop does                                                                                           |
|----------------------|--------------------------------------------------------------------------------------------------------------------|
| `IN_BAND`            | Done. Return the shrunk query.                                                                                     |
| `LOCKED_AT_KHIGH`    | This dim is pinned at the overshoot side (`kHigh`). M is updated. Move to the next dim if M is still above target. |
| `NO_OVERSHOOT_FOUND` | This dim didn't help (state was reverted to snapshot). M is unchanged. Move to the next dim.                       |
| `BUDGET_EXHAUSTED`   | Probes ran out. Outer loop exits.                                                                                  |

#### 2.1 Inner bisect -- narrowing a single dim

Take the first dim from the order: `d = UNIT`, `dimSize = 10` (last non-time dim
in DSD).

**Initialization:**

```
snapshot = state[UNIT]                  # for revert (initially null/wildcard)
kLow  = 0                               # no undershoot known yet
kHigh = 10                              # implicit: k=10 overshoots (M=4000 > target)
k = warm-start = clamp(ceil(target * dimSize / M), 1, dimSize - 1)
  = clamp(ceil(1200 * 10 / 4000), 1, 9)
  = clamp(3, 1, 9)
  = 3
```

**Probe k=3**: send `/availability` with the filter `c[UNIT]=USD,EUR,GBP` (first 3).
`probesIssued += 1` -> now 2.

Suppose the registry returns `SC = 600` (undershoot -- below `N=1000`). The cube is
non-uniform across UNITs.

```
state[UNIT] = first 3 units    # already applied before the probe
SC < N  ->  kLow = 3
```

`kHigh - kLow = 10 - 3 = 7`. Not converged. Compute the next `k` via
**proportional re-step** using the latest pair `(k=3, SC=600)`:

```
k = clamp(ceil(target * k_last / SC_last), kLow + 1, kHigh - 1)
  = clamp(ceil(1200 * 3 / 600), 4, 9)
  = clamp(6, 4, 9)
  = 6
```

**Probe k=6**: filter `c[UNIT]=first_6`. `probesIssued = 3`.

Registry: `SC = 1100`. **In-band!** (`1000 <= 1100 <= 1200`).

```
state[UNIT] = first 6 units
Return IN_BAND with sc=1100
```

Outer loop catches `IN_BAND` and builds the final query:

```
shrunk = query.toBuilder
  .filters({UNIT: [USD, EUR, GBP, JPY, CHF, AUD]})  # first 6
  .limit(null)
  .build()
```

`AdapterRouter` issues a single `/data` request with these filters, gets ~1100
series back, the truncator cuts it down to 1000, and the client sees exactly 1000.

**Total: 3 probes** (1 initial + 2 in the bisect). This matches the diagram of
narrowing UNIT 10 -> 3 -> 6.

---

## 3. Other scenarios

### 3.1 Warm-start lands in the band in 2 probes (best case)

Same setup but the registry is uniform: probe `k=3` on UNIT returns `SC = 1180`
(in-band right away).

```
Probe 1 (initial): SC=4000 -> overshoot
Probe 2 (warm-start k=3 on UNIT): SC=1180 -> IN_BAND ✓
```

**Total: 2 probes.** The typical case for uniform cubes.

### 3.2 Small overshoot -- re-step takes a tiny step (BIS production scenario)

A real run from the BIS WS_EER logs, `limit=80`, `target=96`, REF_AREA(64), `M=181`.

```
Probe 1 (initial): M=181, shrink needed
Probe 2 (warm-start): k = ceil(96 * 64 / 181) = 34
                      SC=100 -> overshoot (4 series above target)
                      kHigh=34, lastOvershootSc=100
Re-step:              k = clamp(ceil(96 * 34 / 100), 1, 33) = clamp(33, 1, 33) = 33
Probe 3 (k=33):       SC=96 -> in-band -> LOCK
```

**Total: 3 probes.** Re-step saw that the overshoot was small (4%), took a one-step
adjustment (k=34 -> k=33), and landed in the band.

> **How is re-step different from plain midpoint?** A midpoint
> `(kLow + kHigh) / 2 = (0 + 34) / 2 = 17` would have sent probe 3 to `k=17`, where
> `SC ~ 48` (deep undershoot). The algorithm would then have needed 2-3 extra probes
> to climb back to `k ~ 30`. Re-step uses the magnitude of the overshoot (only 4
> series above target) and so it makes a tiny step.

### 3.3 Integer gap (no integer `k` lands in band)

Small cube: `dimSize = 3` (REF_AREA with 3 values), `M = 4000`.

```
Probe 1 (initial): M=4000
Warm-start: k = clamp(ceil(1200*3/4000), 1, 2) = clamp(1, 1, 2) = 1
Probe 2 (k=1): SC=500   -> undershoot (< N=1000)
  kLow = 1, kHigh = 3
Re-step: k = clamp(ceil(1200 * 1 / 500), 2, 2) = clamp(3, 2, 2) = 2
Probe 3 (k=2): SC=1500  -> overshoot (> target=1200)
  kHigh = 2, lastOvershootSc = 1500
Convergence check: kHigh - kLow = 2 - 1 = 1 <= 1 -> converged
```

There is no integer `k` whose SC lands in the band: between `k=1` (SC=500) and
`k=2` (SC=1500) there's a **gap**. The algorithm pins on the overshoot side:

```
state[REF_AREA] = first 2 values (kHigh)
LOCKED_AT_KHIGH, M = 1500
```

Outer loop: `M=1500` is still above `target=1200`. If there's another dim, try it.
In this example there's only one dim, so we exit. The final shrunk query goes to
`/data`, the registry returns ~1500 series, the truncator trims to 1000.
**3 probes.**

> **Why pin to `kHigh` and not `kLow`?** Pinning to `kLow` (k=1, SC=500) would give
> the client only 500 series for `limit=1000` -- an undershoot, the failure mode we
> avoid. Pinning to `kHigh` (SC=1500) is an overshoot, but the truncator brings it
> down to 1000 and the client gets the requested count.

### 3.4 NO_OVERSHOOT_FOUND -- bisect couldn't help, revert

Awkward cube: REF_AREA(10), highly non-uniform -- even all 9 first countries combined
give SC <= 700.

```
Probe 1 (initial): M=4000
Warm-start: k = clamp(ceil(1200*10/4000), 1, 9) = 3
Probe 2 (k=3): SC=200   -> undershoot, kLow=3
Re-step: k = clamp(ceil(1200 * 3 / 200), 4, 9) = clamp(18, 4, 9) = 9
Probe 3 (k=9): SC=700   -> undershoot, kLow=9
Convergence: kHigh - kLow = 10 - 9 = 1 <= 1 -> converged
kHigh == dimSize (10) -> NO_OVERSHOOT_FOUND
```

We never found a `k < dimSize` that overshoots. The bisect failed.

```
Revert: state[REF_AREA] = snapshot (the client's filter, or a wildcard)
M stays 4000
```

Outer loop moves to the next dim. If there isn't one, we exit with REF_AREA
unconstrained. The truncator still trims to 1000 in the final response.

> **Why revert instead of pinning at `kLow=9`?** Pinning at `k=9` (SC=700) leaves an
> undershoot for the client. Reverting puts REF_AREA back to its full extent
> (SC=4000), and the next dim has the broadest possible cube to work with.

> **Compared to midpoint:** with midpoint, this scenario takes 5 probes
> (k=3 -> 6 -> 8 -> 9), because midpoint moves by half the known interval each step.
> Re-step jumps directly (`ceil(1200*3/200) = 18` -> clamp to `kHigh-1=9`) and gets
> to `kHigh-1` in one step. **3 probes instead of 5.**

### 3.5 Budget exhausted

`budget = 3` (deliberately tight). DSD: COUNTRY(100). M=4000.

```
Probe 1 (initial): probesIssued=1
Warm-start k=30
Probe 2 (k=30): SC=2000 -> overshoot, kHigh=30, lastOvershootSc=2000, probesIssued=2
Re-step: k = clamp(ceil(1200 * 30 / 2000), 1, 29) = clamp(18, 1, 29) = 18
Probe 3 (k=18): SC=500  -> undershoot, kLow=18, probesIssued=3
Check convergence: kHigh - kLow = 12, not converged
Check budget: probesIssued (3) >= budget (3) -> BUDGET_EXHAUSTED
```

What we do when budget is exhausted:

- If `kHigh < dimSize` (we did see an overshoot): **state[d] = first kHigh values**
  -- without a re-probe (no probes left). This guarantees `SC >= target`, and the
  truncator handles the rest.
- If `kHigh == dimSize` (no overshoot was observed): revert to the snapshot.

In our example:

```
state[COUNTRY] = first 30 (kHigh)
M = 2000 (lastOvershootSc)
WARN log: budget exhausted
Exit outer loop
```

The final query carries `c[COUNTRY]=first 30`, the registry returns ~2000 series,
the truncator trims to 1000. **3 probes** (the entire budget).

### 3.6 Multi-dim -- two dims cooperating

DSD: `[INDICATOR(20), COUNTRY(100)]` (DSD order), `M=4000`. Bisect order (reverse):
`[COUNTRY, INDICATOR]` -- last DSD dim first.

```
--- Outer iteration 1: COUNTRY (dimSize=100) ---
Inner bisect:
  Warm-start: k = clamp(ceil(1200*100/4000), 1, 99) = 30
  Probe k=30 -> SC=900 -> undershoot, kLow=30
  Re-step: k = clamp(ceil(1200*30/900), 31, 99) = clamp(40, 31, 99) = 40
  Probe k=40 -> SC=1200 -> IN_BAND ✓
```

3 probes (1 initial + 2 in the bisect). Done.

OR if the bisect on COUNTRY hit an integer gap:

```
--- Outer iteration 1: COUNTRY ---
Inner bisect: integer gap, lock at kHigh=47, SC=1300
state[COUNTRY] = first 47 countries
M = 1300

--- Outer iteration 2: INDICATOR (dimSize=20) ---
mAtEntry = 1300
Warm-start: k = clamp(ceil(1200*20/1300), 1, 19) = 19
Probe k=19 -> SC=1235 -> overshoot, kHigh=19
Re-step: k = clamp(ceil(1200*19/1235), 1, 18) = clamp(19, 1, 18) = 18
Probe k=18 -> SC=1170 -> IN_BAND ✓
```

**Summary:** the outer loop walks the dims in reverse DSD order. If one dim
produces `IN_BAND`, we're done. Otherwise we pin that dim on its overshoot side and
move to the next, hoping the combined constraint across multiple dims lands in the
band.

---

## 4. Decision flow at a glance

```
+------------------------------+
| 1. Compute TC locally        |
| TC <= target? --> EXIT       |
+--------------+---------------+
               | TC > target
               v
+------------------------------+
| 2. Initial probe -> M        |
| M <= target?    --> EXIT     |
+--------------+---------------+
               | M > target
               v
+------------------------------+
| 3. Multi-dim bisect          |
| For each dim in reverse-DSD: |
|   - snapshot                 |
|   - inner bisect             |
|     . warm-start k           |
|     . probe                  |
|     . re-step k (loop)       |
|   - apply outcome:           |
|     IN_BAND        -> EXIT   |
|     LOCKED_AT_KHIGH -> next  |
|     NO_OVERSHOOT_FOUND -> next
|        (with snapshot revert)|
|     BUDGET_EXHAUSTED -> EXIT |
+--------------+---------------+
               v
       Final shrunk query
       -> AdapterRouter
       -> /data (one request)
       -> truncator trims to N
```

---

## 5. Guarantees and why they hold

| Guarantee                                                      | Why it holds                                                                                                                                                           |
|----------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Worst case <= budget probes** (default 8 -> ~1.2s wall time) | Every re-step stays strictly inside `(kLow, kHigh)` and never widens the interval. The outer loop also counts probes and exits on budget.                              |
| **Never undershoot if any overshoot was observed**             | Every dim lock is on `kHigh` (the overshoot side). The truncator on the proxy trims to `N`.                                                                            |
| **Never undershoot if no overshoot was found**                 | Snapshot revert -- the dim is left as the client had it (overshoot from the initial probe).                                                                            |
| **Always exactly one `/data` request**                         | The TC fast-path and the bisect both return a `TranslatedDataQuery`; `AdapterRouter` issues exactly one `getData()`.                                                   |
| **Correct for uniform cubes**                                  | The warm-start `k = ceil(target * dimSize / M)` lands exactly on `target` under uniformity; re-step does the same after every probe, correcting against the actual SC. |

The only case where the client receives `< N` series is when the cube genuinely has
fewer than `N` series. That's an honest "no more available" from the registry.
