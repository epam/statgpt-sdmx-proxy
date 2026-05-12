# Design 019: E2E tests against a deployed review environment

**Status:** proposed; no code changes yet.

**Affected module:** `sdmx-proxy-e2e`.

**Scope of this design:** only the test module. Workflow files
(`.github/workflows/deploy-review.yml`, deploy/composite-action plumbing) are
deliberately **not** touched in this design — they will be addressed in a
follow-up once the test side is in place.

## Context

The `ai-dial-ci` review-environment workflow
(<https://github.com/epam/ai-dial-ci#deploy-review-environment>) runs E2E suites
against a fully deployed PR-scoped environment. The contract is straightforward:
the test job receives an `environment-url`, waits for `/health`, and exercises
the application over HTTP at that URL. The application itself is built and
deployed elsewhere — the tests do not start it.

The current `sdmx-proxy-e2e` module does the opposite. Every test extends
`@ExtendWith(ContainerFixture.class)`, which uses Testcontainers to:

1. Resolve a Docker image name (`DockerImageConfig`, reading `DOCKER_IMAGE_TAG` /
   `DOCKER_IMAGE_REGISTRY` and optionally running `docker login`).
2. Start an `SdmxProxyContainer` (`GenericContainer<>` with an `HttpWaitStrategy`
   on `/statgpt/sdmx-proxy/api/v0/health`).
3. Hand each test the host's mapped port via `ContainerFixture.getBaseUrl()`.

After the suite, `LogsGate` and `ContainerLogReporter` (both depend on
`SdmxProxyContainer.getLogs()`) scan container stdout for ERROR/exception
patterns and persist per-failure log slices.

This local-Docker model is incompatible with the review-env workflow on two
axes:

- **Lifecycle.** The tests start the server. In the review-env model, the
  server is already running before the tests are invoked.
- **Log surface.** `LogsGate` / `ContainerLogReporter` read container stdout.
  A deployed review env doesn't expose stdout to the test runner.

## Problem

We need `sdmx-proxy-e2e` to run against a base URL supplied externally
(an already-deployed review env) without the Testcontainers / Docker
machinery. The tests' assertion logic and registry configuration model
(`/config` POST mutation, parameterized AllPairs cases, limit emulation
diagnostics, the wildcard 501 contract) are all kept as-is — only the *how do
I find the server* part changes.

## Decisions (locked in pre-design)

1. **Runtime `/config` mutation stays.** Tests will continue to POST a
   `ProxyConfiguration` to `/statgpt/sdmx-proxy/api/v0/config` at `@BeforeAll`
   and flip flags mid-suite (`supportsLimit`, default formats). The review
   environment is expected to be deployed with
   `SDMXPROXY_TEST_CONFIG_ENDPOINT_ENABLED=true`. Each PR gets its own review
   env, so concurrent-mutation concerns are out of scope.

2. **`LogsGate` and `ContainerLogReporter` are removed entirely.** The
   server-side ERROR-in-logs check goes away with this change. Reintroducing
   a remote-log-scrape signal is a follow-up, not part of this work.

3. **No workflow file changes.** This design covers only the test module.
   The composite action (`.github/actions/test-sdmx-proxy/action.yml`),
   wait-for-env step, and artifact upload are deferred to a separate design.
   Nothing in `.github/workflows/` currently invokes `:sdmx-proxy-e2e:e2eTest`
   (a grep for `e2eTest` / `sdmx-proxy-e2e` under `.github/` returns no
   matches), so this PR cannot regress an existing CI E2E job — there is none
   to regress.

4. **Local development still works.** The base-URL provider falls back to
   `http://localhost:8050` so a developer can `./gradlew :sdmx-proxy:bootRun`
   in one shell and `./gradlew :sdmx-proxy-e2e:e2eTest` in another with no
   configuration. `scripts/build-and-docker.ps1` is no longer a prerequisite
   for running E2E — and is no longer documented as one.

5. **`ConfigTests.defaultConfigIsPresentAndReturnedCorrectly` is reframed in
   this PR.** The current `!body.contains("TEST_CONFIG")` assertion only holds
   when the proxy was just started fresh — exactly what container-per-suite
   gave us. With a long-lived review env (or a single-JVM gradle run where an
   earlier `@BeforeAll` already POSTed a `ProxyConfiguration`), that assertion
   becomes a race against class ordering. Drop the negative-string check;
   keep the positive ones (HTTP 200, body parses as `ProxyConfiguration`,
   has at least one registry config). This is the only assertion that
   depends on pristine state *in the `!contains("TEST_CONFIG")` sense*.

   Note: the smoke tests (`AvailabilitySmokeTests`,
   `StructureSmokeTests`) hit BIS paths and assert HTTP 200 *without*
   POSTing their own config in `@BeforeAll` — they depend on the currently
   loaded `/config` containing BIS. That's a *separate* fragility: it
   already exists today (`ContainerFixture` reference-counts one container
   across suites, so an IMF-only `@BeforeAll` in a prior suite already
   breaks the smoke tests in the same gradle run). This PR doesn't
   introduce that bug and doesn't fix it; flagging here so a future reader
   doesn't conflate the two cases.

   (Aside: `DataSmokeTests.testQueryBisData` has every assertion commented
   out today — only the HTTP call runs, and the test passes trivially. It
   is silently inert and stays that way after this PR; the smoke-test
   fragility above applies to `AvailabilitySmokeTests` and
   `StructureSmokeTests` only. Out of scope to fix here.)

6. **Test parallelism stays off.** The suite mutates shared server state
   (`/config`) per class. Parallel class execution would race. We don't enable
   `junit.jupiter.execution.parallel.enabled` and don't introduce
   `@Execution(CONCURRENT)`. If anyone later wants to speed the suite up via
   parallelism, that's a separate redesign: it requires either per-test
   `/config` snapshot-restore or a multi-tenant review env.

## Solution overview

Introduce a `BaseUrlProvider` (replacing `ContainerFixture` as the single
source of truth for the proxy URL) that reads, in order:

1. System property `sdmxproxy.e2e.host`
2. Environment variable `E2E_HOST`
3. Default `http://localhost:8050`

The trailing slash is normalised away. No health-wait, no readiness polling —
that responsibility moves to the workflow layer (out of scope here). If the
server isn't reachable, the first RestAssured request fails fast and the test
reports it; that's correct behaviour for an environment-provisioning bug.

Every test class that today does

```java
@ExtendWith({ContainerFixture.class, LogsGate.class, ContainerLogReporter.class})
```

becomes

```java
// no extension needed
```

and replaces `ContainerFixture.getBaseUrl()` with `BaseUrlProvider.getBaseUrl()`.

## Changes

### 1. New: `support/url/BaseUrlProvider.java`

A pure static helper. Resolves the URL once at first call and caches it.

```java
public final class BaseUrlProvider {
    private static final String SYSTEM_PROP = "sdmxproxy.e2e.host";
    private static final String ENV_VAR = "E2E_HOST";
    private static final String DEFAULT_URL = "http://localhost:8050";
    private static volatile String cached;

    private BaseUrlProvider() {}

    public static String getBaseUrl() { ... }
}
```

Stripping a trailing slash matters because tests concatenate paths like
`baseUrl + "/statgpt/sdmx-proxy/api/v0/..."` and the proxy is sensitive to
double slashes on some routes.

**Path prefixes are *not* a claimed feature of this PR.** Review envs in
the ai-dial-ci pattern expose bare hostnames
(`https://chat-<app>-pr-<n>.<base-domain>`), so `E2E_HOST` is expected to
be a scheme + host (+ optional port) only. RestAssured's behaviour when
`baseURI` carries a non-trivial path component has historically been
version-dependent, and this PR doesn't verify it. If a future workflow
needs prefix routing, the lever is either inlining the prefix into the
test path strings or setting `RestAssured.basePath` globally alongside
`baseURI` — `RestClient` already uses per-request `basePath(path)` for the
endpoint itself, so the global slot is free.

**Cache-once semantics.** `BaseUrlProvider.getBaseUrl()` resolves on first
call and caches the result in a `volatile` static. Each gradle test task
forks its own JVM so the cache doesn't survive between `./gradlew`
invocations; only within a single JVM run does the URL freeze. A reader
shouldn't assume the property is re-read every call. (Re-resolution
mid-run would be wrong anyway: if `E2E_HOST` mid-suite mutated, tests
running against two different servers would silently mix state.)

### 2. Deleted: `support/container/`

- `ContainerFixture.java`
- `SdmxProxyContainer.java`
- `DockerImageConfig.java`

Two system properties that only `SdmxProxyContainer` / `DockerImageConfig`
honored go away with this deletion:

- `e2e.startup.timeout.seconds` — was the readiness-wait timeout
  (`SdmxProxyContainer.java:63`). Health-waiting moves out of the test
  module entirely (workflow layer's concern). A developer or CI override
  in `~/.gradle/gradle.properties` will be silently ignored from this PR
  onward; that's expected.
- `docker.image.tag` / `docker.image.registry` plus the `DOCKER_IMAGE_TAG`,
  `DOCKER_IMAGE_REGISTRY`, `E2E_DOCKER_USER`, `E2E_DOCKER_PASS` env vars
  (`DockerImageConfig.java`) — all unused after deletion. The README
  rewrite drops the table that documents them.

### 3. Deleted: `support/logs/`

- `LogsGate.java`
- `ContainerLogReporter.java`
- `LogPatternMatcher.java`
- Resource: `src/test/resources/log-patterns/allowed-errors.properties`

The `log-patterns/` resource directory becomes empty and is removed too.

### 4. Updated: test classes

Eight classes carry the `@ExtendWith` chain or call `ContainerFixture`:

| File | Change |
|------|--------|
| `tests/framework/BaseRegistryTestSuite.java` | Remove `@ExtendWith`. `setUp()` calls `new RestClient(BaseUrlProvider.getBaseUrl())`. |
| `tests/registry/BIS_3_0_RegistryTestSuit.java` | No change (inherits). |
| `tests/registry/IMF_3_0_RegistryTestSuit.java` | No change (inherits). |
| `tests/StructureWildcardE2ETest.java` | Remove `@ExtendWith`, swap `ContainerFixture.getBaseUrl()`. |
| `tests/LimitEmulationE2ETest.java` | Remove `@ExtendWith`, swap `ContainerFixture.getBaseUrl()`. |
| `tests/health/HealthCheckTests.java` | Remove `@ExtendWith`, swap `ContainerFixture.getBaseUrl()`. |
| `tests/config/ConfigTests.java` | Remove `@ExtendWith`, swap `ContainerFixture.getBaseUrl()`, **and reframe per Decisions §5** (drop the `!contains("TEST_CONFIG")` pristine-state assertion; keep HTTP 200 + body-parses-as-`ProxyConfiguration` + has-at-least-one-registry-config). |
| `tests/smoke/*Tests.java` (3 files) | Remove `@ExtendWith`, swap `ContainerFixture.getBaseUrl()`. |

The `@ExtendWith` annotation goes away entirely on every class. No JUnit
extension is needed — the URL is read on demand.

### 5. Updated: `sdmx-proxy-e2e/build.gradle`

Remove the Testcontainers dependencies:

```diff
- testImplementation "org.testcontainers:testcontainers:${testcontainers_version}"
- testImplementation "org.testcontainers:junit-jupiter:${testcontainers_version}"
```

Remove the `e2e.logs.dir` system property and the `doLast` block that copies
`build/reports/e2e-logs` — that directory is no longer produced. JUnit XML
copy stays (the workflow layer still wants it).

Remove the `testcontainers_version` line from `gradle.properties`. Confirmed
unused after the two `testImplementation` lines are dropped: a grep across
the repo finds it only at `gradle.properties:54`, `sdmx-proxy-e2e/build.gradle:22`,
and `sdmx-proxy-e2e/build.gradle:23`. The latter two go away in this PR;
nothing else references the version property.

### 6. Updated: `sdmx-proxy-e2e/README.md`

Rewrite to describe the new model:

- Prerequisites: a running sdmx-proxy reachable at some URL (local bootRun, or
  a deployed review env).
- One environment variable: `E2E_HOST` (or `-Dsdmxproxy.e2e.host=...`).
- The Docker / image-tag / docker-login table is removed.
- The "before running, build the Docker image" preamble is removed.
- The "tests run against a real proxy" wording stays.
- A short "see also" pointer to `scripts/build-and-docker.ps1` noting it's
  still useful for building a local image to run via `docker compose` (or
  for manual smoke testing), but is no longer an E2E prerequisite — so a
  future reader doesn't assume the script is dead and propose removing it.

### 7. Knock-on: scripts and docs

- `scripts/build-and-docker.ps1` stays (it's still useful for building a
  local image for `docker compose` or manual smoke runs), but is no longer
  referenced as an E2E prerequisite anywhere.
- `docs/e2e_tests/E2E_TEST_DESIGN.md` (existing pre-Testcontainers-era
  design doc) is **superseded by this design**. It documents the Docker
  model end-to-end — `SdmxProxyContainer`, `DockerImageConfig`,
  `ContainerFixture`, `GenericContainer` vs `DockerComposeContainer`,
  `HttpWaitStrategy`, the GitLab CI recipe — almost all of which goes away.
  Rather than rewrite it, add a `> **Status:** Superseded by
  [design 019](../designs/019-e2e-against-review-env/DESIGN.md). Retained
  for historical context only.` banner at the top, and leave the body
  intact as a record of the previous model. A fresh "how E2E works now"
  doc will land with the workflow-side follow-up.

## Deployment-env contract owed to the test module

`ContainerFixture` today sets four env vars on the container before start —
those decisions move to whoever deploys the review env. Recording them here
so the workflow design that follows has a concrete contract to satisfy:

| Variable                                      | Required value                                                | Why                                                                          |
|-----------------------------------------------|---------------------------------------------------------------|------------------------------------------------------------------------------|
| `SDMXPROXY_TEST_CONFIG_ENDPOINT_ENABLED`      | `true`                                                        | Every `@BeforeAll` POSTs a `ProxyConfiguration` to `/config`. Without this, every suite fails before the first assertion. |
| `SDMXPROXY_REGISTRY_CONFIG_SOURCE_TYPE`       | Any value that lets Spring start (e.g. `FILESYSTEM`)          | The proxy needs *enough* registry config at boot to start the Spring context and serve `/health`. The content is irrelevant past startup — every suite overwrites it via `/config` in `@BeforeAll`. |
| `SDMXPROXY_REGISTRY_CONFIG_SOURCE_FILENAME`   | Path to a startable config (e.g. the bundled `sdmx_registries_config.json`) | Pairs with the above. Content is moot once `/health` is green; tests don't read what was deployed. |
| `FEIGN_LOG_LEVEL`                             | `HEADERS` (recommended; implicit default is `BASIC`)          | The proxy reads `FEIGN_LOG_LEVEL` and falls back to `BASIC` when unset (`SdmxApiClientProviderImpl.java:168`, `ConfigServerFeignConfig.java:32`). `BASIC` logs method+URL+status only; `HEADERS` adds upstream-call headers, which is what makes diagnostic logs actionable when an E2E run fails on a registry-side regression. |

Plus the JVM-tuning `JAVA_OPTS` block that `ContainerFixture` sets — that's
not test-contract, it's deploy-side ops; mentioning so the workflow design
doesn't have to rediscover it.

This table is intentionally placed here (test-side design) rather than the
future workflow-side design, because the test module is the entity that
imposes the requirement. The workflow design's job is to *satisfy* it.

## Non-goals

- **Workflow integration.** Nothing in `.github/` is touched. Wiring this
  module into `deploy-review.yml` (or a new `e2e.yml`) is deferred.
- **Health-wait / DNS-wait / retry-on-startup.** These are workflow-layer
  concerns. The test module assumes the URL it's given is already serving.
- **Server-log gating replacement.** `LogsGate` is dropped without a
  replacement. Surfacing server-side errors back to the test report is a
  separate design.
- **Per-failure server-log slices.** `ContainerLogReporter` produced
  `failures/{Class}_{method}.log` slices — the single most useful artifact
  when a test failed against an opaque-body upstream call. That capability
  goes away with this PR and is *not* replaced. A cheap interim that doesn't
  require server-log access is to dump response body + status + headers from
  RestAssured's failure path; that's a follow-up, not part of this design.
  Flagging here so a future reader knows the missing piece is deliberate.
- **Test-data isolation across PRs.** Each review env is single-tenant per PR,
  so this is not a problem in practice. We're not adding a `@BeforeEach`
  reset of `/config`.
- **Cross-test-class `/config` reset within a single run.** Addressed for
  `ConfigTests` by the reframe in Decisions §5. The smoke tests'
  (`AvailabilitySmokeTests`, `StructureSmokeTests`) separate dependency on
  a BIS-containing `/config` is a pre-existing fragility (see §5 note); it
  is not regressed by this PR and is not fixed here.

## Migration

This change is intended to land in one commit. No phased rollout — the test
module either targets Testcontainers or it targets `E2E_HOST`; there is no
useful intermediate state.

### CI status during landing

There is no current CI job that runs `:sdmx-proxy-e2e:e2eTest` (a grep across
`.github/workflows/` confirms — see Decisions §3). This PR therefore does not
break any existing pipeline. The follow-up workflow design must land *before*
any CI job invokes `e2eTest`, otherwise that job will fail at the first
RestAssured call (defaulting to `http://localhost:8050`, which won't resolve
in the runner).

### Local-dev recipe after this change

```powershell
# Terminal 1
./gradlew :sdmx-proxy:bootRun
# (or `docker compose up sdmx-proxy` against a pre-built image)

# Wait until you see "Started SdmxProxyApplication in N seconds" before the
# next step — there is no health-wait inside the test module any more, so
# launching Terminal 2 before the server is ready will race startup and the
# first RestAssured request will get a connection refused.

# Terminal 2
./gradlew :sdmx-proxy-e2e:e2eTest
# or against a remote env:
./gradlew :sdmx-proxy-e2e:e2eTest -Dsdmxproxy.e2e.host=https://chat-sdmx-proxy-pr-NNN.<base-domain>
```

## Verification

After implementation:

- `:sdmx-proxy-e2e:compileTestJava` clean (no stray imports of the removed
  classes).
- `./gradlew :sdmx-proxy:bootRun` in one shell, `./gradlew
  :sdmx-proxy-e2e:e2eTest` in another — should behave identically to the
  current Testcontainers run, modulo the missing `LogsGate` assertion *and*
  the absence of `failures/{Class}_{method}.log` slices under
  `build/reports/e2e-logs/` (both removed with the support classes — see
  Decisions §2 and Non-goals).
- A single suite (e.g. `BIS_3_0_RegistryTestSuit`) run with
  `-Dsdmxproxy.e2e.host=...` pointing at a manually deployed environment to
  confirm the URL plumbing.
- `:sdmx-proxy:test` stays green (no expected impact; this module isn't
  touched).
