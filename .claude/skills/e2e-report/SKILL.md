---
name: e2e-report
description: Run the sdmx-proxy E2E suite against a live proxy and produce a pass/fail report. Boots the proxy (on a non-default port, capturing its log), wipes the E2E build/report folder, runs e2eTest, then correlates JUnit reports with proxy logs to report what passed and what failed. Use when the user asks to run E2E tests against an environment and get a report.
---

# Run E2E tests and report

End-to-end procedure: bring up the proxy (capturing its log), run the E2E suite against it,
then analyse the reports + proxy log and tell the user what passed and what failed.

The E2E suite is a black-box client: it pushes a per-test `ProxyConfiguration` via `POST /config`
and queries live registries through a **running** proxy. Nothing works without a proxy listening.

## Inputs to resolve first

Before doing anything, settle these (ask the user only if genuinely ambiguous — otherwise use the defaults):

1. **Proxy target** — boot a fresh proxy (default), or reuse one the user already has running.
   - If reusing, get its base URL (e.g. `http://localhost:8050`) and skip the boot step.
   - If booting, default to port **8060** (NOT 8050 — that is the proxy default and a fresh E2E-spawned
     proxy could collide; using a different port keeps logs cleanly attributable to our run).
2. **Scope** — all E2E tests (default), a single registry suite, or a specific test class.
   - Registry suites live in `sdmx-proxy-e2e/.../tests/registry/` (e.g. `IMF_3_0_RegistryTestSuit`,
     `BIS_3_0_RegistryTestSuit`). Other E2E classes: `LimitEmulationE2ETest`, `StructureFanOut*E2ETest`,
     `StructureWildcardE2ETest`, smoke tests under `tests/smoke/`.
   - Scope with `--tests "*.<ClassName>"`.

E2E runs are slow (minutes) and hit real registries against their rate limits — do not loop or re-run casually.

## Step 1 — Boot the proxy (skip if reusing an existing one)

Run in the background, capturing stdout+stderr to a log file. Use PowerShell (Windows shell):

```powershell
$env:SDMXPROXY_TEST_CONFIG_ENDPOINT_ENABLED='true'; $env:SERVER_PORT='8060'; ./gradlew :sdmx-proxy:bootRun *> proxy-e2e.log
```

Notes:
- `SDMXPROXY_TEST_CONFIG_ENDPOINT_ENABLED=true` enables `POST /config` (required by the suite).
- `SERVER_PORT=8060` overrides `server.port` via Spring relaxed binding.
- Run this with the tool's `run_in_background` option so it keeps running across the rest of the steps.
- Write the log to `proxy-e2e.log` at the repo root (this is the proxy log you will correlate against later).

**Wait for readiness** before running tests — poll the log for the startup line, e.g. grep `proxy-e2e.log`
for `Started SdmxApiProxyApplication`, or until `GET http://localhost:8060/actuator/health` (or any structure
endpoint) responds. Do not start E2E until the proxy is up, or every test fails with connection-refused.

## Step 2 — Wipe the E2E build/report folder

Clear stale reports so the analysis reflects only this run. The "special folder" is `sdmx-proxy-e2e/build`:

```powershell
Remove-Item -Recurse -Force sdmx-proxy-e2e/build/test-results, sdmx-proxy-e2e/build/reports -ErrorAction SilentlyContinue
```

(Removing the whole `sdmx-proxy-e2e/build` also works but forces a fuller recompile; clearing just
`test-results` + `reports` is enough and faster.)

## Step 3 — Run the E2E suite against the booted proxy

Point the suite at the proxy port via `E2E_HOST` (env is inherited by the forked test JVM).
Pass `-Dtest.ignoreFailures=true` so the run completes and produces reports for ALL suites even when
some fail (otherwise Gradle stops at the first failing suite and you lose coverage of the rest):

```powershell
$env:E2E_HOST='http://localhost:8060'; ./gradlew :sdmx-proxy-e2e:e2eTest -Dtest.ignoreFailures=true
```

Scope when asked, e.g.:

```powershell
$env:E2E_HOST='http://localhost:8060'; ./gradlew :sdmx-proxy-e2e:e2eTest --tests "*.IMF_3_0_RegistryTestSuit" -Dtest.ignoreFailures=true
```

If reusing an existing proxy on 8050, set `E2E_HOST='http://localhost:8050'` (or omit — 8050 is the default).

## Step 4 — Analyse reports + proxy log

Reports produced by the run:
- **JUnit XML** (machine-readable, the source of truth): `sdmx-proxy-e2e/build/test-results/e2eTest/*.xml`
  (also copied to `build/test-results/e2e/`).
- **HTML** (human-friendly): `sdmx-proxy-e2e/build/reports/tests/e2eTest/index.html`.

From the JUnit XML:
- Each `<testsuite>` carries `tests`, `failures`, `errors`, `skipped`, `time` attributes — sum these for totals.
- Each failing `<testcase>` has a `<failure>`/`<error>` child with the assertion message and stack trace.
  Capture the test name (`classname` + `name`) and the failure message.

Correlate each failure with `proxy-e2e.log`:
- Match on the registry/endpoint/artefact in the test name, and on timestamps near the test run.
- Look for proxy-side exceptions, conversion errors, upstream registry HTTP errors, timeouts, or
  `JsonParseException`/mapper warnings around the failing request.
- Distinguish failure *categories*: assertion mismatch (proxy returned 200 but content wrong) vs.
  proxy error (5xx/exception) vs. upstream/registry failure (registry down, rate-limited, 4xx from registry)
  vs. environment (connection refused → proxy wasn't up). This category is the most useful thing for the user.

## Step 5 — Write the report and tell the user

Write a concise markdown report to `sdmx-proxy-e2e/build/e2e-report.md` AND summarise in chat:
- Run metadata: scope, proxy port/URL, timestamp, total duration.
- Totals: passed / failed / errored / skipped.
- Per-failure table: test name, category (see above), one-line cause from the assertion + proxy log,
  and the relevant proxy-log excerpt.
- A short "likely root cause" grouping when several failures share one cause (e.g. one registry rate-limited).
- Pointer to the HTML report path for drill-down.

Do not overstate: if a failure's cause is unclear from the logs, say so rather than guessing.

## Step 6 — Tear down

Stop the background proxy process you started in Step 1 (kill the bootRun background task). Leave it running
only if the user asked you to keep the environment up.

## Gotchas

- The proxy must be fully started before Step 3 — poll the log, don't sleep-and-hope.
- `-Dtest.ignoreFailures=true` is essential for a complete report across multiple suites.
- Env vars (`E2E_HOST`) propagate to the forked test JVM; system props passed to the outer `gradlew`
  do not automatically — prefer `E2E_HOST` over `-Dsdmxproxy.e2e.host` for the host.
- Real registries are rate-limited; a wave of failures across one agency often means rate-limiting, not a proxy bug.
- Do NOT run an unscoped root `./gradlew build`. Scope to `:sdmx-proxy-e2e:e2eTest`.
- This repo is Windows/PowerShell — use `$env:VAR='x'` and `*> file.log`, not bash `VAR=x` / `2>&1`.
