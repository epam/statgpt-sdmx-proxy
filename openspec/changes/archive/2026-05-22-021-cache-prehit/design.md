# Design 021: Cache pre-heat (cache prewarming) — evaluation

**Status:** rejected. We will not implement cache pre-heat. The cost / complexity is
not justified by the benefit: any scheme adds substantial moving parts (lifecycle
hooks or schedulers or external triggers, multi-instance coordination, a maintained
"what to prewarm" list, failure-mode handling, observability around all of the
above), while the upside is bounded to "the first request per hot key, per cold
replica". Natural traffic warms `W_hot` within a short window anyway, after which
the cache hit rate is the same as it would be with prewarm. We would be optimizing
the cold transient and paying for that optimization in steady-state code surface
and operational burden -- a bad trade for the current workload.

This document is preserved as the record of what was considered. The analysis below
still describes the design space accurately; see "Decision" for the rejection
rationale and "Revisit criteria" for the conditions that would reopen the question.

## Decision

We are not building cache pre-heat. The summary reasoning:

- **The win is narrow.** Prewarm helps exactly once per cold key: the first request
  that would otherwise be a miss. After natural traffic has touched `W_hot` once,
  the cache hit rate with and without prewarm is identical. For an LLM-driven
  workload that re-queries the same handful of dataflows, `T_warm_natural` is
  short -- minutes, not hours.
- **The cost is broad.** Every option in "Options considered" introduces either
  in-proxy lifecycle code (A, B, E), a coordination protocol (D), or an
  external orchestration contract (C). All of them require a maintained prewarm
  target list, a failure mode, and observability that we do not currently need.
  None of that complexity is recouped after the cold transient ends.
- **The worst case is real.** LOCAL-mode rolling deploys with naive prewarm
  self-DoS the upstream registry (`R x |W_hot|` simultaneous requests, against
  per-registry rate limits configured precisely to prevent that). Defensive
  rate-limited prewarm is implementable but its wall-clock cost converges on
  natural traffic warming the cache -- i.e. it buys nothing in LOCAL mode and
  adds risk.
- **The observability work is still worth doing on its own merits.** Cache
  hit/miss counters and `/structure` latency timers (the prerequisites that the
  earlier draft of this document listed as gating prewarm) have independent
  value: TTL tuning, capacity planning, future cache decisions. Land that work
  as part of normal operational hygiene, not as a gate for a feature we are not
  building.

## Revisit criteria

Reopen this design only if all three hold:

1. Production observability shows cache miss rate on `/structure` stays above
   ~5% well past `T_warm_natural` (i.e. natural warming is *not* sufficient for
   the actual traffic distribution).
2. `L_cold - L_warm` on `/structure` is large enough at p95 to be user-visible
   (>= 1s) and correlates with client complaints or SLO breach.
3. The deployment topology is `CACHE_MODE=REDIS`, or a path to REDIS exists.
   Prewarm in LOCAL mode at non-trivial `R` is not worth revisiting -- the
   rate-limit math does not work.

If the criteria hold, the recommended path was option C (external CronJob hitting
a property-gated `POST /admin/prewarm`) paired with REDIS, starting with strategy
3 (dataflow listings only) for the target list. See "Recommendation" below for
the full reasoning, retained for the historical record.

## Context

The SDMX Proxy starts with all three caches cold: parsed structures (raw bytes of
`SdmxBeans`), ready responses (serialized output keyed by URL + Accept + query params),
and limit-emulation shrunk-query results. Every request after a cold start hits the
upstream registry until the relevant entry has been populated by traffic. The cost of a
cold-start miss is significant on three dimensions:

1. **Latency.** A structures fetch against IMF can take seconds (the per-registry
   `readTimeout` is 60s for IMF in `sdmx_registries_config.json`). A `/data` cold call
   that triggers limit-emulation pays the bisect budget too (one warm-start probe plus
   up to `limitEmulationProbeBudget` follow-ups -- see designs 014/017).
2. **Registry load.** Cold deploys send every concurrent request through to the
   registry. For broad structure queries (dataflow listings, full DSDs) this is
   wasteful work the registry has to serve repeatedly.
3. **Self-inflicted spikes during rolling deploys.** In `LOCAL` cache mode every
   replica caches independently. If the deployment policy is "drain and restart N
   replicas", each new replica starts cold and faces real traffic until its working
   set is in Caffeine -- so the same response is fetched from the registry N times
   over the rolling-deploy window.

The user has asked whether to introduce a cache pre-heat / prewarm mechanism that
populates the caches before traffic arrives (or, in the case of TTL refresh-ahead,
before entries expire). They have flagged three hard constraints:

- The proxy is horizontally scaled behind a load balancer (multiple replicas).
- A new microservice dedicated to prewarming is not on the table.
- Putting prewarm logic into the proxy itself feels architecturally noisy.

The user has explicitly said "won't do for now" is an acceptable conclusion if the
cost / complexity is not justified. This document is structured to argue both
directions honestly and to recommend a decision; it deliberately does not propose
code.

## Notation

| Symbol             | Meaning                                                                                                                                       |
|--------------------|-----------------------------------------------------------------------------------------------------------------------------------------------|
| `CACHE_MODE`       | Environment variable: `LOCAL` (Caffeine, per-instance) or `REDIS` (shared).                                                                   |
| `R`                | Number of proxy replicas behind the load balancer.                                                                                            |
| `W`                | Working set: distinct cache keys actually requested by clients in some reference window.                                                      |
| `W_hot`            | The "core" subset of `W` -- structures/responses that almost every client request resolves to (dataflow listings, the few most-used DSDs).    |
| `L_cold`           | Median latency of a cold (uncached) request.                                                                                                  |
| `L_warm`           | Median latency of a warm (cached) request.                                                                                                    |
| `T_warm_natural`   | Wall-clock time after a fresh deploy before traffic itself fills `W_hot` -- after which natural cache hit rate has reached steady state.      |
| `TTL_structures`   | Configured TTL for the parsed-structures cache (default 24h, `ParsedStructuresProperties`).                                                   |
| `TTL_responses`    | Configured TTL for ready responses (default 6h, `ReadyResponsesProperties`).                                                                  |
| `TTL_limit`        | Configured TTL for limit-emulation shrink entries (default 1h, `LimitEmulationProperties`).                                                   |

## Current state

### What is cached and where

Three cache "domains" sit behind the same `CacheService` interface
(`sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/cache/CacheService.java`):

| Domain                 | Stored value                              | Key shape (from `CacheKeyGenerator`)                                                                                                 | Populator (call site)                                                                                                                                                                                                  |
|------------------------|-------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Raw structures         | `byte[]` of the registry's raw structure body (post-fixture) | `structure:{type}:{registryName}:{agencyId}:{resourceId}:{version}:{references}:{detail}`                                            | `AdapterRouterImpl.getStructureBytes(...)` -- only invoked through `getSdmxBeans(...)`, which is itself only called by the data and availability code paths (filter validation, limit emulation, availability fixtures). |
| Ready responses        | `byte[]` of the converted final response  | `response:structure:{...above...}:{md5(Accept + sorted queryParams)}`                                                                | `AdapterRouterImpl.getStructures` (both bypass and conversion paths).                                                                                                                                                  |
| Limit-emulation shrink | Serialized `CachedShrinkResult` (`shrunkKey` + filter map) | `limit_emu:{registryName}:{agencyId}:{resourceId}:{version}:{md5(key + filters + limit + tolerance + budget)}`                       | `AdapterRouterImpl.resolveShrunkQuery(...)`.                                                                                                                                                                           |

Two important observations:

1. **Ready responses are populated only on the `getStructures` endpoint.** Data
   responses are *not* cached today (an explicit choice noted in design 014); only
   structure responses are. So "prewarm the response cache" can only mean prewarm
   structure responses.
2. **Raw structures are populated as a *side effect* of data and availability
   requests** (via `getStructureQuery(...)` -> `getSdmxBeans(...)`), not only when
   `/structure` is called. A given client `/data` request indirectly forces a
   structures fetch for filter validation, limit emulation, and availability
   fixtures. The same SDMX dataflow descriptor underlies many distinct URLs.

### TTL configuration

`CacheProperties` -> `TtlProperties` carries three per-domain `Duration`s and per-domain
`jitter`s (`ParsedStructuresProperties`, `ReadyResponsesProperties`,
`LimitEmulationProperties`). The defaults are 24h / 6h / 1h with +/- jitter applied at
write time in `RedisCacheService.addJitter` to desynchronize expiry across keys.
`InMemoryCacheService` uses Caffeine `expireAfterWrite` without jitter (Caffeine spreads
eviction itself).

Crucially, *there is no refresh-ahead loader* on either cache today. Caffeine's
`refreshAfterWrite` is not configured; Redis entries expire on TTL and the *next*
client request that needs them pays the full cold cost.

### Cache modes and multi-instance topology

`CACHE_MODE=LOCAL` (the default) instantiates `InMemoryCacheService` per replica;
`CACHE_MODE=REDIS` instantiates `RedisCacheService` backed by a shared Redis
(credentials from one of the three cloud-IAM providers in
`services/cache/credentials/`, indicating this runs in multi-cloud environments).

| Cache mode | Cache scope          | Cold-start cost                                                                                                          |
|------------|----------------------|--------------------------------------------------------------------------------------------------------------------------|
| `LOCAL`    | Per-replica          | Every replica restart re-fetches its entire working set from registries. Rolling deploys multiply registry load by `R`. |
| `REDIS`    | Shared across pods   | Replicas share entries. A cold restart of one replica costs nothing extra -- existing Redis entries serve immediately.   |

### Runtime reconfiguration

Registries are not fully known at startup. `ProxyConfigurationProviderImpl` resolves
configuration from one of three extractors (`FILESYSTEM`, `CLASSPATH_RESOURCE`,
`CONFIG_SERVER`). With `CONFIG_SERVER`, `ConfigServerPoller` polls every 30s by default
and the `ProxyConfiguration` can change mid-flight (registries added, URLs swapped,
`supportsLimit` flipped). The E2E suite also overrides config via `POST /config`. Any
prewarm scheme must therefore work with config that is mutable at runtime, not a static
boot-time snapshot.

### What the proxy already knows at startup

- `CACHE_MODE` and TTLs (Spring `@ConfigurationProperties`).
- Whatever `ProxyConfiguration` the extractor produces: registry list, per-registry
  endpoint URLs, `supportedStructures`, `supportsLimit`, fixtures, agency list.
- It does *not* know: which dataflows exist on each registry, which dataflows are
  popular, what client filters look like.

The "core" prewarm targets (dataflow listings and DSDs of the most-used dataflows) are
*not* in the static config. Discovering them is the first non-trivial problem.

## Goals and non-goals

### Goals (if any prewarm is implemented)

1. Reduce p95 cold-start latency for *the most common* request shapes against each
   configured registry, on each fresh replica boot (`LOCAL`) or fresh Redis cluster
   (`REDIS`).
2. Avoid amplifying registry load. The cure must be cheaper than the disease.
3. Keep the surface area in the proxy small and clearly fenced from request-serving
   code. No business logic in prewarm.
4. Work with the existing dynamic configuration model -- not require a separate
   prewarm spec file maintained in lockstep with the registry config.

### Non-goals

- **Prewarming data responses.** Data is not cached, so there is nothing to prewarm.
- **Prewarming limit-emulation shrink entries.** Per-`(key, filters, limit)`
  combinatorics are too sparse to prewarm meaningfully; clients send wildly different
  filters. TTL is already short (1h). Skip.
- **Cache invalidation on registry updates.** Out of scope here.
- **Replacing TTL with prewarm.** Prewarm complements TTL; it does not remove the
  staleness bound.
- **A separate prewarmer microservice.** Off the table per user constraint.
- **A blocking prewarm phase that delays HTTP readiness.** The proxy must remain
  serving while any prewarm runs (otherwise rolling deploys stretch in proportion to
  prewarm wall-clock).

## The "is it worth it" question

We cannot answer it from code alone. The decision depends on three quantities that the
proxy does not currently measure:

| Quantity                                                | Why it matters                                                                                                             | How to obtain                                                                                                                                                                                                              |
|---------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `L_cold` vs `L_warm` on `/structure`                    | Direct measure of what prewarm would save per request.                                                                     | The codebase has `log.debug` "Cache hit/miss" lines on every cache call. A short observability change (or a synthetic test against a live registry) suffices.                                                              |
| Cache hit rate after `T_warm_natural` post-deploy       | If natural traffic warms the cache within a minute, prewarm is solving a one-minute problem; if it takes an hour, prewarm is solving a real problem. | Same hit/miss log lines, plus deploy timestamps from the orchestrator.                                                                                                                                                     |
| Distribution of `W_hot` across `W`                      | Tells us if prewarm has a small Pareto-style target (dozens of keys) or is a hopeless tail-chase (thousands of distinct keys, most rarely repeated). | Server-side access log of `/structure` over a representative window. The proxy does not log full URLs at INFO today; either turn on `FEIGN_LOG_LEVEL=FULL` for a sample window or grep nginx/gateway logs in front of it. |

Until those numbers exist, any choice of "what to prewarm" is a guess. The user
explicitly listed "if you can't measure, say so and identify what would need to be
measured" as a valid outcome. We say so.

That said, a *qualitative* estimate is worth recording so the cost / benefit framing is
not abstract:

- The current registry config (`sdmx_registries_config.json`) lists two registries
  (BIS, IMF), both SDMX 3.0. Each registry has on the order of dozens of dataflows
  (BIS) to hundreds (IMF). A truly hot working set for an LLM-facing platform like
  StatGPT is plausibly small -- the LLM tends to re-query the same handful of
  dataflows. A working hypothesis is `|W_hot| <= 50` keys per registry.
- `L_cold` for an IMF DSD is plausibly in the multi-second range (their `readTimeout`
  is 60s; observed conversion time adds non-trivial CPU on top).
- `L_warm` for the same DSD served from Caffeine is microseconds; from Redis it is
  sub-millisecond on the local network. The saving is real per-request.
- The pain is therefore concentrated in *the first request per cold key*, not in
  steady state. This is exactly the regime where prewarm helps, *if* you can
  enumerate the keys.

## Multi-instance constraint

This is the load-bearing constraint, and it splits the design space into two regimes.

### REDIS mode

Prewarm is cheap to justify:

- The cache is shared. One actor (any single replica, or an external trigger) fills
  Redis once; all replicas benefit.
- Coordination is trivial: a Redis-backed lock (`SETNX prewarm:lock value EX 600`)
  ensures only one actor runs at a time. If two replicas race the lock, the loser
  just exits.
- Refresh-ahead becomes a real option: a single replica (or an external scheduler)
  can periodically re-fetch hot keys before their TTL elapses, and every replica sees
  the refreshed entry.

In REDIS mode the cost/benefit of even a simple prewarm tilts toward "worth it",
*provided* we know what to prewarm.

### LOCAL mode

Prewarm is much harder to justify:

- Every replica has its own Caffeine. Prewarm has to run on every replica, every
  time it starts.
- If `R` replicas roll-restart on a deploy, the registry sees `R` simultaneous
  prewarm bursts. Even at a conservative `|W_hot| = 50` and `R = 6`, that is 300
  upstream requests in seconds. BIS's per-registry `rateLimit.limitForPeriod = 100`
  per 60s is configured exactly to prevent this kind of burst. We would trip our own
  rate limiter on deploy.
- Rate-limit-aware prewarm (issue requests with a delay equal to the configured
  refresh interval) is implementable, but at that point the prewarm has the same
  wall-clock cost as natural traffic warming the cache. The only thing it buys is
  "warm before traffic arrives" -- which only matters if there is a deterministic
  gap between replica readiness and traffic arrival. Most platforms route traffic
  immediately on readiness.

The honest reading is: **in LOCAL mode, a prewarm scheme has to be defensive enough
about rate limits that its benefit is marginal**. The strongest argument for prewarm
in LOCAL mode is "we will switch to REDIS soon"; if that is true, design for REDIS
mode and live with whatever LOCAL does in the meantime.

## Options considered

Each option assumes we have answered the "what to prewarm" question -- see "What to
prewarm" below for that subproblem.

### Option A: `ApplicationReadyEvent` listener in the proxy

A Spring `@EventListener(ApplicationReadyEvent.class)` bean iterates the prewarm list
once after startup and issues internal calls through `AdapterRouter` (or equivalently
through Feign clients) to populate the cache.

| Pros                                                                                            | Cons                                                                                                                                                                                                  |
|-------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Trivial to implement. No new infrastructure.                                                    | Runs on every replica in LOCAL mode -- multiplies registry load by `R`.                                                                                                                              |
| Uses existing in-process service graph -- no new HTTP path, no auth concerns.                   | Tied to the application lifecycle: if startup is slow because of prewarm, readiness/liveness probe windows are at risk.                                                                              |
| TTL refresh-ahead is not addressed -- only solves the cold-boot moment.                         | If config is loaded from `CONFIG_SERVER`, the `ApplicationReadyEvent` may fire *before* the first poll completes. The prewarm runs against an incomplete configuration unless explicitly sequenced. |
| In REDIS mode, multiple replicas all try; `SETNX` dedup is a small addition but works.         | "Putting it in the proxy" is exactly what the user flagged as architecturally noisy.                                                                                                                  |

### Option B: `@Scheduled` task inside the proxy

A `@Scheduled(fixedDelayString = "${sdmxproxy.prewarm.interval}")` re-runs prewarm
periodically. Solves cold-boot (first tick fires shortly after startup) and TTL
refresh-ahead (subsequent ticks).

| Pros                                                                                              | Cons                                                                                                                                                                       |
|---------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| One mechanism for cold-boot and refresh-ahead.                                                    | LOCAL mode: every replica refreshes independently. Steady-state registry load = `R x |W_hot| / interval`. With `R=6`, `|W_hot|=50`, interval=1h -- 300 req/h, all redundant. |
| Already-established pattern in this codebase: `ConfigServerPoller` uses the same idiom.           | Coordination in REDIS mode requires distributed locking (Redisson, or hand-rolled `SETNX EX`). Adds dependency complexity for a marginal feature.                          |
| Decouples prewarm cadence from TTL -- can refresh more or less aggressively than TTL expires.    | The user explicitly flagged "in-proxy is weird"; this is more in-proxy than option A.                                                                                       |

### Option C: Admin endpoint `POST /admin/prewarm`, triggered externally

Expose a protected endpoint that runs the prewarm pass. Drive it from outside the
proxy: a Kubernetes `CronJob` that `curl`s the endpoint, or a GitHub Actions cron job,
or a manual `kubectl exec`. The proxy is purely a passive worker.

| Pros                                                                                                                | Cons                                                                                                                                                                                                |
|---------------------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Orchestration logic lives *outside* the proxy -- exactly the architectural shape the user prefers, no new microservice. | Requires platform support (Kubernetes CronJob or equivalent). Without it, the endpoint is dead weight.                                                                                              |
| `POST /admin/prewarm` is a single endpoint, easy to reason about, easy to disable via property (mirror of `POST /config`). | Caller has to know what to prewarm and against which registries -- pushes the "what" decision out of the proxy. Could be addressed by making the endpoint take a list, but then the caller owns the list. |
| Natural for refresh-ahead: cron schedule decides cadence.                                                            | In LOCAL mode the load balancer routes the prewarm request to *one* replica only; the others stay cold. Triggering all replicas requires hitting each replica directly (bypass the LB).             |
| In REDIS mode, hitting one replica fills the shared cache. Clean.                                                    | In LOCAL mode the operator has to issue `R` requests (one per pod IP) to warm all replicas -- workable for CronJob with `kubectl get pods` enumeration, ugly for ad-hoc.                            |

### Option D: Redis pub/sub-coordinated prewarm

One replica is elected (via Redis lock); it prewarms; it publishes results (already
written to Redis by virtue of the prewarm calls). In LOCAL mode, election plus
fan-out: the leader publishes "prewarmed key X" notifications; followers do nothing
because their caches still need their own copies. Falls back to per-replica prewarm.

| Pros                                                                | Cons                                                                                                                                                                          |
|---------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Elegant in REDIS mode: leader does the work, everyone reads.        | In LOCAL mode it adds Redis dependency for coordination of a cache that does not use Redis. That is a worse architecture than option A.                                       |
| Robust to leader failure if implemented carefully.                  | Lock semantics, fencing tokens, leader handover -- non-trivial code in the proxy. The "in-proxy is weird" objection becomes a major design smell.                              |

### Option E: Lazy background warming on startup

A low-priority worker started post-`ApplicationReadyEvent` enumerates the prewarm list
and trickles requests through `AdapterRouter` with throttling that respects the
per-registry `RegistryRateLimitConfig`. Same idea as option A but explicitly
rate-limited and asynchronous.

| Pros                                                                                                                                 | Cons                                                                                                              |
|--------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------|
| Does not block startup; serves traffic immediately.                                                                                  | Same per-replica multiplication problem in LOCAL mode.                                                            |
| Respecting the registry rate limit avoids the "self-DoS on deploy" worst case.                                                       | Wall-clock time to fully warm = `|W_hot| / rate_limit`. For 50 keys / (100 per 60s) = 30s -- acceptable.            |
| The "trickle and respect rate limit" pattern is what the registry would impose anyway if we hammered it; we just make it explicit.    | Refresh-ahead is not addressed; this is cold-boot-only. Adding TTL refresh-ahead converts it back to option B.    |

### Option F: Do not implement prewarm. Defer.

Document the decision, the criteria for revisiting, and rely on natural traffic
warming.

| Pros                                                                                                                                                                       | Cons                                                                                              |
|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------|
| Zero new code, zero new failure surface, zero new operational burden.                                                                                                       | Cold-start penalty stays.                                                                          |
| Avoids investing in a mechanism whose benefit we cannot quantify and whose worst case (LOCAL-mode rolling deploy self-DoS) is real.                                          | If the cold-start penalty turns out to be operationally painful, we discover that the slow way.   |
| Forces the prerequisite observability work (cache hit/miss rates, `L_cold` distribution). That work is cheap, has its own value, and *is* the information needed to revisit. | "Do nothing" is sometimes parsed as "no answer". Mitigated by documenting decision criteria.        |
| Aligns with the user's explicit invitation to choose this outcome.                                                                                                          |                                                                                                   |

## What to prewarm -- the discovery subproblem

Regardless of trigger, the prewarm needs a list of `(registry, structureType, agencyId,
resourceId, version, references, detail)` tuples and ideally the Accept headers that
matter. Three discovery strategies:

1. **Static list from config.** Add a per-registry `prewarm` block to
   `RegistryConfiguration` listing the dataflows / DSDs / codelists to prewarm. Pros:
   explicit, auditable, easy to test. Cons: humans have to keep it accurate as
   dataflows are added/retired upstream; we do not have a process for that today.
2. **Dynamic from access history.** Maintain an LRU of "most-recently-requested cache
   keys" and replay it on next boot. Pros: self-tuning, no manual list. Cons: in
   LOCAL mode the LRU is per-replica and ephemeral (lost on restart). In REDIS mode
   it could live in Redis -- but then we are reintroducing Redis-coupled state for
   the LOCAL deployment too. Also creates a chicken-and-egg problem for the first
   deploy.
3. **Boot-time discovery: prewarm dataflow listings only.** Use
   `translateStructureQueryForAgencySchemaDiscovery`'s shape -- a per-registry
   `/structure/dataflow/{agency}/*/*` with `references=children`, `detail=full`.
   Pros: a single key per registry. Cons: this is the *cheapest* miss to begin with;
   it does not solve the per-DSD cold cost which is the expensive one.

Strategies 1 and 3 are implementable on the existing codebase. Strategy 2 is
substantially more work and couples LOCAL mode to Redis.

## Failure mode

If a prewarm call to a registry fails (registry down at startup, transient 5xx,
circuit breaker open):

- Crashing the proxy is wrong: the proxy must serve other registries normally.
- Retry-with-backoff is wrong inside an `@EventListener`: it blocks startup
  indefinitely against a flapping upstream.
- The correct behavior is **log and continue with cold cache**. Prewarm is best-effort
  by definition. Any option above that does not adopt this stance is unsafe.

This also means prewarm failures must be loud in the logs and in metrics -- a silent
"prewarm crashed, we are running cold" is operationally invisible.

## Observability

Whether we prewarm or not, we need the following to decide whether prewarm helped
(and, in option F, to decide whether to revisit):

- Cache hit/miss *rate* per cache domain (not just per-call DEBUG logs). Today the
  code logs `Cache hit/miss for ...: <key>` at DEBUG. Aggregating that is the
  operator's job; a counter increment would surface it for free.
- Wall-clock `L_cold` vs `L_warm` distribution on `/structure`. Histogram/timer per
  registry.
- Time-to-warm post-deploy. Implicit from hit-rate-over-time.
- If prewarm is implemented: prewarm duration, per-registry count of prewarmed keys,
  per-registry count of prewarm failures.

These are simple Micrometer additions, orthogonal to whether prewarm itself ships.

## Risk inventory

| Risk                                                                                       | Likelihood     | Severity                           | Mitigation                                                                                              |
|--------------------------------------------------------------------------------------------|----------------|------------------------------------|---------------------------------------------------------------------------------------------------------|
| LOCAL-mode rolling deploy DoSes a small registry that has tight rate limits                | Medium-High    | High (circuit breaker opens; cascading 5xx for clients) | Trigger respects per-registry `rateLimit.limitForPeriod`; or skip prewarm in LOCAL mode entirely.       |
| Prewarm fails silently on registry outage; operator believes cache is warm                 | Medium         | Medium (latency surprise)          | Loud logs + per-registry "last successful prewarm at..." metric.                                        |
| Prewarm list drifts from reality (dataflow renamed/retired upstream)                       | High over time | Low (404s logged, proxy serves)    | Strategy 3 (dataflow listings only) is drift-immune. Strategy 1 needs a CI check or upstream-discovery. |
| In REDIS mode, two replicas race the prewarm and double the upstream load                  | Low            | Low (one-shot, bounded)            | `SETNX prewarm:running EX 300`; second replica exits.                                                   |
| `ApplicationReadyEvent` fires before config-server poll completes                          | Medium         | Medium (prewarms against stale config) | Defer prewarm start to first successful config poll, not `ApplicationReadyEvent`.                       |
| The endpoint in option C is left enabled and unauthenticated in production                 | Low            | High (anyone can force registry hits) | Mirror the `POST /config` pattern: `@Value("${sdmxproxy.admin.prewarm.enabled:false}")` and gate via property + network policy. |

## Recommendation

**Defer (option F), with two explicit prerequisites and a sunset clause.**

1. Land the small observability piece described in "Observability" above. Specifically:
   counters for cache hit/miss per domain, timers on the `/structure` endpoint
   distinguishing cache-hit vs cache-miss latency, and per-registry timers on the
   underlying Feign client. None of this is prewarm code -- it is general
   instrumentation that has independent value.
2. Run for one representative period (a deploy plus a week of normal traffic) and
   record the three quantities from "The 'is it worth it' question" section.
3. Revisit this design if, after that period, *either* is true:
    - `L_cold - L_warm` on `/structure` exceeds 1s at p95 *and* miss-rate exceeds 5%
      of requests after `T_warm_natural`.
    - Rolling deploys produce a measurable client-visible latency spike that
      correlates with cache cold-state (e.g. p95 latency doubles in the 5 min after
      a rollout).

If revisited, the recommended path is option C (external trigger via
Kubernetes CronJob hitting `POST /admin/prewarm`) **paired with REDIS cache mode**, on
the basis that:

- Option C keeps orchestration outside the proxy -- the architectural shape the user
  prefers, no new microservice.
- REDIS mode reduces the multi-instance multiplication factor from `R` to `1`.
- The "what to prewarm" list can start as strategy 3 (dataflow listings only, one
  key per registry, drift-immune) and grow to strategy 1 (named DSDs per registry)
  once the observability data shows which DSDs are hot.
- Refresh-ahead is naturally implementable as CronJob cadence; the proxy stays
  stateless.

LOCAL mode is *not* recommended as a prewarm target. If LOCAL mode is the chosen
deployment, the recommendation is to live with cold caches and rely on natural warming
-- prewarm in LOCAL is not worth the rate-limit and rolling-deploy risk.

### Why defer rather than pick option C now

We do not know whether the cold-start penalty is operationally painful. We do not
know `|W_hot|`. We do not know whether the StatGPT traffic pattern produces a
long-tail miss rate that prewarm cannot help with anyway. Option C without those
numbers is "implement scaffolding for a feature whose target list is empty". The
prerequisite observability work informs every future decision in this area
(cache-effectiveness reviews, TTL tuning, future cache decisions for the data
endpoint, capacity planning for the registries) and costs roughly one PR.

## Open questions

1. **Is the platform migrating to REDIS cache mode?** If yes, the prewarm calculus
   becomes more favorable and the recommendation could shift. If no, prewarm in LOCAL
   has the rate-limit problem documented above and option F is the durable answer.
2. **What is the actual deployment topology?** Is `R` typically 2, 6, 20? The
   per-replica multiplication factor scales linearly with `R`. The risk inventory
   depends on the answer.
3. **Is StatGPT's request distribution Pareto-skewed enough for a small `|W_hot|` to
   matter?** This is the single most important number and the one we cannot estimate
   from the proxy code alone -- it is a property of the LLM workload upstream.
4. **Should data responses ever be cached?** Out of scope here, but a "yes" changes
   the prewarm calculus considerably -- data responses are larger, slower to fetch,
   and a much higher fraction of total registry load. This deserves its own design.
5. **Is there an existing platform-wide CronJob pattern?** Option C assumes Kubernetes
   CronJob (or equivalent in whatever orchestrator is in use). If the platform team
   already has a "ping these admin endpoints periodically" mechanism, option C is
   close to free; if not, we are creating that pattern from scratch.
6. **Where would the prewarm key list live?** Per-registry block on
   `RegistryConfiguration` is the natural answer, but it would require the schema
   updates and a per-registry maintenance discipline that does not exist today. An
   alternative -- inferring from registry's own `/structure/dataflow/*` -- pushes the
   problem out by one level (each prewarmed dataflow listing tells you what DSDs to
   prewarm next, recursively). Worth deciding before any implementation.

## Appendix: design references

- `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/cache/CacheService.java`,
  `CacheKeyGenerator.java`, `InMemoryCacheService.java`, `RedisCacheService.java`.
- `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/cache/config/CacheProperties.java`
  and the per-domain TTL classes (`ParsedStructuresProperties`,
  `ReadyResponsesProperties`, `LimitEmulationProperties`).
- `sdmx-proxy/src/main/java/com/epam/sdmxproxy/services/adapter/AdapterRouterImpl.java`
  -- the only writer to the three caches.
- `sdmx-proxy/src/main/java/com/epam/sdmxproxy/registry/configuration/ProxyConfigurationProviderImpl.java`,
  `configserver/ConfigServerPoller.java` -- mutable runtime configuration model.
- `sdmx-proxy/src/main/java/com/epam/sdmxproxy/controller/ConfigController.java` --
  precedent for a property-gated admin-style endpoint (`POST /config`).
- Designs 014 and 017 -- the limit-emulation cache and its TTL rationale.
