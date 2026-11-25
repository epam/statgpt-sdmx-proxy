# SDMX Proxy — Claude Workspace

## What is this project

SDMX API Proxy for the StatGPT platform. A Java 25 / Spring Boot 4.0 proxy and translation layer that exposes a unified
SDMX 3.0 REST API, routing requests to multiple external SDMX registries (which may speak SDMX 2.1 or 3.0) and
converting between formats (SDMX-ML, SDMX-JSON, SDMX-CSV). Built by EPAM Systems.

## Development workflow (OpenSpec)

The repo uses [OpenSpec](https://github.com/Fission-AI/OpenSpec) with the `spec-driven` schema.
`PROCESS.md` is the full process: the change lifecycle, the gate ladder, the defect cycle, and
roles. The rules here are the ones every session must hold.

```
/openspec-propose → [owner read] → /review-change → [owner approval] → /openspec-apply-change → /run-gates → /openspec-archive-change
   artifacts         checkpoint 1     critic loop       checkpoint 2        tasks + unit tests     ladder, E2E last     merge specs
```

- **Spec-first.** The normative specs live in `openspec/specs/<capability>/spec.md`. Read the
  ones a change touches before writing code. Behavior changes go through an OpenSpec change in
  `openspec/changes/<name>/` before the code does, never by drift. If a spec looks wrong, stop
  and flag it.
- **No code before approval.** A propose or update request authorizes planning artifacts only.
  Implementation starts only when the user explicitly runs or asks for apply on a named change,
  after the independent review and the owner approval.
- **Gates, in order, before a change is done:** `./gradlew clean build`; scenario coverage of
  the delta specs; an independent diff review by a fresh subagent; then E2E via `e2e-report`,
  once and last, for registry-facing changes only. E2E takes 30 to 60 minutes, so it never runs
  during implementation or review. Results go to the change's `evidence/gates.md`.
- **Every E2E run goes through the `e2e-report` skill**, inside or outside an OpenSpec change. It
  boots the proxy on port 8060 with the test config endpoint enabled, wipes stale reports, runs
  `e2eTest` with `-Dtest.ignoreFailures=true`, and correlates JUnit results with the proxy log.
  Never run `:sdmx-proxy-e2e:e2eTest` by hand.
- **Defects run the cycle in `PROCESS.md`:** instrument (negative control first), diagnose, spec
  gate, spec change, implement, re-test. A defect that exposes unspecified behavior adds the
  spec line in the same change.
- **Roles.** The main session orchestrates and verifies. Authoring, artifact review, and diff
  review run on opus subagents; implementation, gate runs, and troubleshooting run on sonnet
  subagents. Every pass is a fresh agent briefed from the change folder.
- **No finding lives only in chat.** Review rounds, diff reviews, gate results, and finding
  records land in the change's `evidence/`.
- **Comments and specs carry behavior and its reason, never provenance.** Don't cite change
  folders, review rounds, or "per the review" in code comments. Who decided and when lives in
  the change folder and git history.
- **History.** `openspec/changes/archive/` holds every past change. The entries dated before
  2026-11-25 were migrated from `docs/designs/` and keep their three-digit number (for example,
  `2026-11-23-044-structure-cache-preheat`), so "design 044" still resolves.

Trigger behavior: `/openspec-propose` and `/openspec-explore` start from a description.
`/review-change`, `/openspec-apply-change`, `/run-gates`, and `/openspec-archive-change` act on a
named change, so a heavy, side-effectful step never starts on its own.

## Shell / working directory

- The primary working directory is already `sdmx-proxy` (the repo root). Do NOT `cd` into it — you are already there.
- Use relative paths from the repo root for all commands (e.g. `./gradlew`, not `cd /some/path && ./gradlew`).

## Prose style

All prose in the repo follows the [Google developer documentation style guide](https://developers.google.com/style):
OpenSpec artifacts, READMEs, code comments, issue and bug write-ups, commit messages, and PR
descriptions. The rules that matter most:

- Write in second person (*you*) and active voice, in the present tense. Name who acts.
- Use sentence case for every heading, with no trailing period.
- Put the condition before the instruction: "To enable fan-out, set `structureFanOutEnabled`."
- Keep sentences under about 26 words, and one idea per paragraph with the key point first.
- Put code items in code font: classes, methods, fields, files, paths, flags, HTTP status codes,
  config keys, and values.
- Introduce every list, table, and code block with a complete sentence.
- Drop *simply*, *just*, *easy*, *please*, *note that*, and time-bound words such as *currently*,
  *new*, and *soon*. Don't use *e.g.*, *i.e.*, *etc.*, or *via*.
- Don't use emojis unless asked.
- Avoid the antithesis tic ("it is X, not Y", "this is not A, it is B"). State the point once,
  plainly, and move on.

## Code style and conventions

- Prefer discussion before writing or modifying code — ask for confirmation first
- Preserve existing line endings when editing (LF stays LF, CRLF stays CRLF)
- Lombok everywhere: `@Data`, `@Builder`, `@RequiredArgsConstructor`, `@Slf4j`
- Constructor injection only (no field injection)
- Log4j2 (not Logback — Logback explicitly excluded)
- API interface + Controller implementation pattern
- Don't line-break method calls or initializers — keep them on one line even if the line is long. Holds for multi-argument calls too, as long as the resulting line is reasonable to read; only split when the argument list is genuinely unwieldy (5+ args or arguments that themselves contain non-trivial expressions)
- Always use braces `{}` for `if`/`else`/`for`/`while` blocks, even single-line bodies
- Commit messages and PR titles use Conventional Commits: `<type>: <description>`, for example
  `feat: Add structure cache preheat` or `fix: Support SDMX-CSV v1 download`. Types in use: `feat`, `fix`, `chore`,
  `ci`, with an optional scope such as `ci(deps):`. GitHub appends the PR number (`(#130)`) on squash merge, so don't
  add it by hand
- New features and behavior changes require an OpenSpec change in `openspec/changes/` before implementation; no code
  changes until the change is reviewed and approved (see "Development workflow")
- Do not decide on a solution approach without asking first — present options and let the user choose
- When adding, removing, or renaming any field in the config classes under `sdmx-proxy-config/src/main/java/com/epam/sdmxproxy/configuration/`, update the schema tables in `sdmx-proxy-config/README.md` in the same change so the documented options stay in sync with the model

## Project structure

```
sdmx-proxy/
├── sdmx-proxy/              # Main Spring Boot application module
│   ├── src/main/java/com/epam/sdmxproxy/
│   │   ├── api/             # OpenAPI interface definitions
│   │   ├── controller/      # REST controllers
│   │   ├── common/          # Shared DTOs, media types, mappers
│   │   ├── configuration/   # Telemetry config
│   │   ├── exception/       # Custom exceptions
│   │   ├── registry/        # Feign clients, resilience, 429 retry, config loading (incl. config server)
│   │   ├── services/        # Core business logic
│   │   │   ├── adapter/     # AdapterRouter, conversion services, registry-specific sources (statcan/)
│   │   │   ├── agencyscheme/ # Synthetic AgencyScheme endpoint
│   │   │   ├── availability/ # Availability emulation for registries without the endpoint
│   │   │   ├── cache/       # CacheService (Caffeine / Redis): raw structures, ready responses, limit-emulation shrink
│   │   │   ├── filter/      # Filter validation, 3.0->2.1 translation, FilterNormalizer (c[]-routing)
│   │   │   ├── fixture/     # Response patching: structure/, data/, availability/
│   │   │   ├── limit/       # SDMX 3.0 `limit` emulation (probe-budgeted bisect, truncators)
│   │   │   ├── preheat/     # Structure cache preheat
│   │   │   ├── routing/     # Agency-to-registry routing
│   │   │   ├── sdmxsource/  # Custom overrides of sdmx-core classes
│   │   │   ├── singleflight/ # Single-flight for concurrent registry structure fetches
│   │   │   └── translator/  # QueryTranslator — routing & translation brain
│   │   └── web/             # WebMVC config, exception handler
│   └── src/main/resources/  # application.yaml, log4j2.xml
├── sdmx-proxy-config/       # Pure data classes (no Spring dep), shared with E2E; holds sdmx_registries_config.json
├── sdmx-proxy-config-server/ # Separate config server app (DIAL storage backed)
├── sdmx-proxy-e2e/          # E2E tests (RestAssured + allpairs4j) against a running proxy
├── openspec/                # OpenSpec: specs/ (normative behavior), changes/ (active), changes/archive/ (history)
├── PROCESS.md               # Change lifecycle, gate ladder, defect cycle, roles
└── docs/                    # Conversion notes, registries, E2E, OTel, discussions, issues, reviews
```

## Key entry points (read first)

1. **`QueryTranslatorImpl.java`** (~850 lines) — THE central brain: registry selection, version fallback, filter
   validation, format determination
2. **`AdapterRouterImpl.java`** (~740 lines) — cache, single-flight, bypass vs conversion, fixture application,
   limit-emulation orchestration (cache lookup → bisect → truncator), filter normalization for BIS-style registries
3. **`GenericRegistryAdapterImpl.java`** (~370 lines) — version-specific Feign client dispatch, SDMX 2.1 query
   normalization
4. **`LimitEmulationServiceImpl.java`** (~700 lines) — TC fast-path + probe-budgeted bisect (designs 014 + 017)
5. **`sdmx-proxy-config/src/main/resources/sdmx_registries_config.json`** — registry configurations (URLs, formats,
   fixtures, resilience)

## Build & run

```bash
./gradlew clean build                    # Full build + unit tests (does not run E2E)
./gradlew clean build -x test            # Build without tests
./gradlew :sdmx-proxy:bootRun           # Run app (port 8050)
./gradlew :sdmx-proxy:test              # Unit tests
```

E2E needs a running proxy and internet access to the live registries. Run it with the `e2e-report` skill.

## Key environment variables

| Variable                                       | Default        | Description                   |
|------------------------------------------------|----------------|-------------------------------|
| `CACHE_MODE`                                   | `LOCAL`        | `LOCAL` (Caffeine) or `REDIS` |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | localhost:6379 | Redis connection              |
| `OTEL_SDK_DISABLED`                            | `true`         | Disable OpenTelemetry         |
| `FEIGN_LOG_LEVEL`                              | `BASIC`        | Feign client log level        |

## E2E tests — the key to registry integration

E2E tests are the primary tool for integrating new registries.

### How it works

- The suite is a black-box client of a running proxy (`E2E_HOST`, default `http://localhost:8050`).
- `BaseRegistryTestSuite` is the generic heart — generates 100-200+ parameterized tests per registry using pairwise
  combinatorics (allpairs4j)
- Per-registry suites are tiny: they load two JSON config files and extend `BaseRegistryTestSuite`. Suites:
  `BIS_3_0`, `IMF_3_0`, `ESTAT_3_0`, `OECD_2_1`, `STATCAN_2_1` (each `<NAME>_RegistryTestSuit`)
- Parameterized areas: specific structures, data endpoint, availability endpoint, limit emulation. On top of those,
  config-gated checks pin specific behaviors: DSD and data conversion fidelity, metadata attribute preservation,
  dimension-group attributes, key order, OR keys, empty time ranges, and others
- Each test sets the proxy config at runtime via `POST /config`, then hits the endpoint and asserts on the response

### Two config files per registry

1. **Registry config** (`*_registry_config.json`) — the `ProxyConfiguration` JSON pushed to the proxy: agency, URLs,
   formats, fixtures, resilience settings
2. **Test config** (`*_test_config.json`) — `RegistryTestSuitConfiguration`: which artefact URNs, dataflow keys, media
   types, and return formats to test

Location: `sdmx-proxy-e2e/src/test/resources/.../registry/{provider}/{version}/`

### New registry integration flow

1. Copy an existing registry config pair, update agency/URLs/formats
2. Create a tiny test suite class extending `BaseRegistryTestSuite`
3. Run the suite with the `e2e-report` skill and see what fails
4. Add fixtures as needed to fix registry-specific issues (fixtures are reusable across registries)

Cross-registry behavior checks go in `BaseRegistryTestSuite`, gated by a toggleable block in the test config, never as
`@Test` methods on a single registry suite.

### Run commands

Run E2E through the `e2e-report` skill, which wraps these commands with the proxy boot, report
cleanup, and log correlation. The raw commands are for reference:

```bash
./gradlew :sdmx-proxy-e2e:e2eTest                                    # All E2E
./gradlew :sdmx-proxy-e2e:e2eTest --tests "*.BIS_3_0_RegistryTestSuit"   # Single registry
./gradlew :sdmx-proxy-e2e:e2eTest --tests "*.OECD_2_1_RegistryTestSuit"
```

## sdmx-core quirks and workarounds

The proxy uses sdmx-core (`io.sdmx`, the `fusion-*` modules) from Maven Central, version `sdmx_core_version` in
`gradle.properties` (2.4.0). Several bugs and API gaps require custom overrides in `services/sdmxsource/`, catalogued
in `docs/conversion/sdmxsource-overrides.md`. Key issues:

- **Hierarchy level field**: The SDMX-JSON 2.0.0 spec defines `level` in `HierarchicalCodeType` as an `idType` (plain
  string like "0", "L1"). sdmx-core's `SdmxJsonHierarchicalCodelistReaderEngineV2` incorrectly parses it as a URN,
  crashing on plain IDs. Fixed by `CustomSdmxJsonHierarchicalCodelistReaderEngineV2`.
- **Level reference lost in immutable bean**: `HierarchicalCodeBeanImpl` stores the raw `levelRef` string in a private
  field, but `getLevel(false)` returns null when the hierarchy has no formal Level objects. The mutable round-trip (
  `getMutableInstance()`) also loses it because `CodeRefMutableBeanImpl` uses the same broken `getLevel(false)` path.
  `HierarchyMapper` uses reflection to extract the private `levelRef` field — this is the only way to preserve level
  references through the conversion pipeline without modifying sdmx-core.
- **StructureMapperImpl** must explicitly map each structure type from `SdmxBeans` to jsdmx `Artefacts`. If a new
  structure type is added to `supportedStructures`, check that `StructureMapperImpl.map()` includes it — otherwise the
  structure will be parsed but silently dropped from the output.
- Do NOT assume sdmx-core correctly implements the SDMX spec. Always verify against the spec (SDMX REST 2.2.0,
  SDMX-JSON 2.0.0) when something doesn't work.
- Do NOT fabricate claims about documented patterns or recommendations. If something is a pragmatic workaround, say so.

## SDMX glossary (quick reference)

| Term            | Meaning                                                      |
|-----------------|--------------------------------------------------------------|
| Registry        | SDMX web service (IMF, BIS, OECD, Eurostat, StatCan)         |
| Agency/AgencyID | Organization maintaining data — routes to registry           |
| Dataflow        | Defines how data is organized/published                      |
| DSD             | Data Structure Definition (dimensions, attributes, measures) |
| MSD             | Metadata Structure Definition                                |
| SdmxBeans       | sdmx-core's in-memory representation of structural metadata  |
| Bypass          | Raw passthrough when formats match + bypassEnabled           |
| Conversion      | Parse -> SdmxBeans -> re-serialize to client format          |
| Fixture         | Patch on a raw response (JSON, XML, CSV) to fix registry bugs |
| Fan-out         | Parallel query to multiple registries (agencyID=`*`)         |
| Return format   | `SdmxFormat` the proxy requests FROM the registry (not client format) |
| c[] parameter   | SDMX 3.0 filter: `c[DIM]=value`                              |
| Key             | SDMX 2.1 position-based filter: `val1.val2.val3`             |
