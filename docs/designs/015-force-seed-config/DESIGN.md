# Design: Force Seed Config via `CONFIG_SERVER_FORCE_SEED`

Tracks GitHub issue [#50](https://github.com/epam/statgpt-sdmx-proxy/issues/50).

## Context

The config server (`sdmx-proxy-config-server`) seeds Dial Storage from the bundled
`sdmx_registries_config.json` **only when the stored configuration is absent or empty**
(`ConfigService.init()` at `sdmx-proxy-config-server/src/main/java/com/epam/sdmxproxy/configserver/service/ConfigService.java:32-49`).
Once seeded, subsequent deployments never update it, even as the bundled config evolves
(new fixtures, new registries, adjusted resilience settings).

Today the only ways to push an updated bundled config into an existing environment are
manual: editing the object in Dial Storage by hand, or calling `POST /config` with a copy
of the new JSON. A proper versioned/managed update mechanism is tracked as a follow-up;
this design is a deliberately blunt, fast interim flag.

## Problem

`ConfigService.init()` calls `seedFromClasspathDefault()` only inside the `isNullOrEmpty(config)`
branch:

```java
if (isNullOrEmpty(config)) {
    log.warn("Configuration from {} is absent or empty", configExtractor.sourceType());
    if (configExtractor.sourceType() == ConfigSourceType.DIAL_STORAGE) {
        seedFromClasspathDefault();
    }
} else {
    currentConfig.set(config);
    ...
}
```

There is no operator-facing switch to force the seed path when a non-empty (but stale) config
is already in storage.

## Solution

Introduce one env var, `CONFIG_SERVER_FORCE_SEED` (default `false`), bound to the Spring
property `sdmxproxy.configserver.source.force-seed`. When `true`, `ConfigService.init()`
unconditionally loads the bundled classpath default, validates, writes via `ConfigWriter`,
and sets it as the current config — skipping the `isNullOrEmpty` guard entirely.

### Decisions (locked in pre-design discussion)

1. **Failure of a forced reseed is fatal.** If the operator flipped the flag, the intent is
   "install the new bundled config". Silently keeping the old one and reporting unhealthy
   defeats that intent. Any `Exception` during the forced-seed path (resource missing,
   validation, write) rethrows so Spring aborts `@PostConstruct` and the pod crashloops.
   (The existing empty-seed path keeps its current soft-fail semantics — it is reached when
   nothing was asked for.)

2. **`CONFIG_SERVER_FORCE_SEED=true` with `CONFIG_SERVER_SOURCE_TYPE=FILESYSTEM` is fatal.**
   In filesystem mode the file on disk *is* the source of truth; a "force overwrite from
   classpath default" request has no sensible meaning. Crash on startup with a clear error
   so operators know their flag did nothing. Equivalent behavior for any future non-DIAL
   source type.

### Scope

- In: `DIAL_STORAGE` mode only.
- Out: filesystem mode (hard-fail as above).
- Out: automatic version-diff reseed, `POST /config/reseed` endpoint, per-registry selective
  reseed — tracked separately per issue #50's follow-up section.

## Changes

### 1. `sdmx-proxy-config-server/src/main/resources/application.yaml`

Add one line under `sdmxproxy.configserver.source`:

```yaml
sdmxproxy:
  configserver:
    source:
      type: ${CONFIG_SERVER_SOURCE_TYPE:FILESYSTEM}
      config-path: ${CONFIG_SERVER_SOURCE_CONFIG_PATH:config/sdmx_registries_config.json}
      force-seed: ${CONFIG_SERVER_FORCE_SEED:false}
```

### 2. `sdmx-proxy-config-server/src/main/java/com/epam/sdmxproxy/configserver/service/ConfigService.java`

Add a `@Value`-injected field and rewrite `init()`. Pattern matches `DialStorageConfigWriter.java:22`
(`@Value` on a non-final field inside a `@RequiredArgsConstructor` class).

```java
@Value("${sdmxproxy.configserver.source.force-seed:false}")
private boolean forceSeed;

@PostConstruct
void init() {
    if (forceSeed) {
        if (configExtractor.sourceType() != ConfigSourceType.DIAL_STORAGE) {
            throw new IllegalStateException(
                "CONFIG_SERVER_FORCE_SEED=true is only supported with "
                + "CONFIG_SERVER_SOURCE_TYPE=DIAL_STORAGE, got " + configExtractor.sourceType());
        }
        log.warn("CONFIG_SERVER_FORCE_SEED=true — overwriting stored configuration "
            + "with bundled classpath default. Flip this flag back to false after a healthy rollout.");
        forceSeedFromClasspathDefault();
        return;
    }

    try {
        ProxyConfiguration config = configExtractor.getConfiguration();
        if (isNullOrEmpty(config)) {
            log.warn("Configuration from {} is absent or empty", configExtractor.sourceType());
            if (configExtractor.sourceType() == ConfigSourceType.DIAL_STORAGE) {
                seedFromClasspathDefault();
            }
        } else {
            currentConfig.set(config);
            storageAvailable.set(true);
            log.info("Config server initialized with configuration from {} source",
                configExtractor.sourceType());
        }
    } catch (Exception e) {
        log.warn("Failed to load configuration from {} on startup: {}. "
            + "Config server will report unhealthy until storage becomes available.",
            configExtractor.sourceType(), e.getMessage());
    }
}

private void forceSeedFromClasspathDefault() {
    log.info("Forced reseed: loading bundled default configuration from classpath resource {}",
        DEFAULT_CONFIG_RESOURCE);
    try (InputStream resourceStream = getClass().getClassLoader().getResourceAsStream(DEFAULT_CONFIG_RESOURCE)) {
        if (resourceStream == null) {
            throw new IllegalStateException(
                "Default configuration resource not found on classpath: " + DEFAULT_CONFIG_RESOURCE);
        }
        byte[] fileBytes = resourceStream.readAllBytes();
        ProxyConfiguration defaultConfig = objectMapper.readValue(fileBytes, ProxyConfiguration.class);
        configValidator.validate(defaultConfig);
        configWriter.writeConfig(defaultConfig);
        currentConfig.set(defaultConfig);
        storageAvailable.set(true);
        log.info("Forced reseed complete: overwrote DIAL Storage with bundled default "
            + "({} registries, {} agencies). Remember to unset CONFIG_SERVER_FORCE_SEED "
            + "before the next restart or you will wipe manual changes again.",
            defaultConfig.getConfigs().size(), defaultConfig.getAgencies().size());
    } catch (IOException e) {
        throw new IllegalStateException("Forced reseed failed: " + e.getMessage(), e);
    }
}
```

Notes:
- `forceSeedFromClasspathDefault()` does **not** catch `RuntimeException` — `ConfigValidator.validate`
  and `ConfigWriter.writeConfig` already throw `IllegalStateException` on failure, which propagates
  out of `init()` and aborts Spring startup. Only `IOException` from the resource read needs wrapping.
- The existing `seedFromClasspathDefault()` is left untouched — it still swallows exceptions on the
  empty-seed path, preserving today's behavior for unchanged setups.
- The warning log at the top of the forced path is intentionally at WARN so "we forgot to unset it"
  is visible in alerting on every subsequent restart.

### 3. `sdmx-proxy-config-server/src/test/java/com/epam/sdmxproxy/configserver/service/ConfigServiceTest.java`

Existing tests construct `ConfigService` directly (no Spring context), so the `@Value` field
defaults to `false` and current tests stay green. Add the following tests, using
`org.springframework.test.util.ReflectionTestUtils.setField(service, "forceSeed", true)` to flip
the flag.

- `forceSeedOverwritesExistingConfig` — extractor returns a non-empty config; with `forceSeed=true`
  and `DIAL_STORAGE`, verify `configWriter.writeConfig` is called with a config loaded from the
  bundled classpath resource (not the one from the extractor) and `currentConfig` is the bundled one.
- `forceSeedIsNoOpWhenFlagDisabled` — extractor returns a non-empty config; `forceSeed=false` (or
  unset). Verify `configWriter.writeConfig` is **never** called and `currentConfig` equals the
  extractor's config. (Regression guard equivalent to the current `initLoadsConfigFromExtractorOnSuccess`
  plus an explicit flag assertion.)
- `forceSeedThrowsOnFilesystemMode` — `forceSeed=true`, `sourceType=FILESYSTEM`. Assert
  `IllegalStateException` from `init()` and that `configWriter.writeConfig` is never called.
- `forceSeedPropagatesWriterFailure` — `forceSeed=true`, `DIAL_STORAGE`,
  `configWriter.writeConfig` stubbed with `doThrow(new IllegalStateException(...))`. Assert
  `init()` throws (no silent swallow). Contrast with existing `initRemainsUnhealthyWhenWriterFails`
  which asserts the opposite for the empty-seed path.

Skeleton for the overwrite test (for the implementing agent to complete):

```java
@Test
void forceSeedOverwritesExistingConfig() {
    when(configExtractor.sourceType()).thenReturn(ConfigSourceType.DIAL_STORAGE);
    // Note: we do NOT stub getConfiguration(); forced path must not rely on it.

    ConfigService service = new ConfigService(configExtractor, configWriter, configValidator, objectMapper);
    ReflectionTestUtils.setField(service, "forceSeed", true);
    service.init();

    ArgumentCaptor<ProxyConfiguration> captor = ArgumentCaptor.forClass(ProxyConfiguration.class);
    verify(configWriter).writeConfig(captor.capture());
    ProxyConfiguration written = captor.getValue();
    // Bundled sdmx_registries_config.json has > 0 registries and > 0 agencies by construction.
    assertFalse(written.getConfigs().isEmpty());
    assertFalse(written.getAgencies().isEmpty());
    assertEquals(written, service.getConfiguration());
    assertTrue(service.isStorageAvailable());
}
```

### 4. `sdmx-proxy-config-server/README.md`

Insert a new row in the **Core** env var table after `DIAL_STORAGE_API_KEY`:

```
| `CONFIG_SERVER_FORCE_SEED`        | No                                                | If `true`, overwrite stored configuration from the bundled classpath default on startup (DIAL_STORAGE only; hard-fails otherwise). See "Forced reseed" below. | `true`, `false` | `false` |
```

Append a new subsection after the Core table:

```markdown
### Forced reseed

`CONFIG_SERVER_FORCE_SEED=true` forces the config server to overwrite whatever is in Dial
Storage with the bundled `sdmx_registries_config.json` on every startup while the flag is
set. Use this to push an updated bundled configuration into an existing environment.

**Recommended deploy flow:**

1. Set `CONFIG_SERVER_FORCE_SEED=true` in the deployment manifest for the next rollout.
2. After the deployment is healthy, flip the variable back to `false` (or remove it) and
   redeploy.

**Footguns:**

- **Every restart while the flag is on wipes the stored configuration**, including any
  manual changes made via `POST /config` or directly in Dial Storage. A WARN-level log is
  emitted on every startup while the flag is on to make "we forgot to unset it" visible
  in alerting.
- The flag only applies to `CONFIG_SERVER_SOURCE_TYPE=DIAL_STORAGE`. With any other
  source type the server will fail to start — the filesystem file is the source of truth
  in filesystem mode, so "reseed from classpath" has no meaning there.
- A forced reseed discards any environment-specific overrides that are not part of the
  bundled default.
```

## Files Affected

| File                                                                                                    | Type     | Change                                                                           |
|---------------------------------------------------------------------------------------------------------|----------|----------------------------------------------------------------------------------|
| `sdmx-proxy-config-server/src/main/java/com/epam/sdmxproxy/configserver/service/ConfigService.java`     | Modified | Add `forceSeed` field, branch in `init()`, add `forceSeedFromClasspathDefault()` |
| `sdmx-proxy-config-server/src/main/resources/application.yaml`                                          | Modified | Add `force-seed` property under `sdmxproxy.configserver.source`                  |
| `sdmx-proxy-config-server/src/test/java/com/epam/sdmxproxy/configserver/service/ConfigServiceTest.java` | Modified | Add 4 tests covering the forced-seed path                                        |
| `sdmx-proxy-config-server/README.md`                                                                    | Modified | Document `CONFIG_SERVER_FORCE_SEED` and deploy flow                              |

## No Changes Required

- `ConfigServerApplication.java`, `ConfigServerConfiguration.java` — no new beans.
- `ConfigExtractor` / `ConfigWriter` interfaces and their DIAL/Filesystem implementations — the
  forced path reuses `configWriter.writeConfig` and `configValidator.validate` unchanged.
- `ConfigSourceType` enum — `DIAL_STORAGE` and `FILESYSTEM` constants are sufficient.
- `sdmx_registries_config.json` — bundled resource consumed as-is.
- Any file outside `sdmx-proxy-config-server/` — main proxy does not observe this flag.

## Verification

1. **Build and unit tests:**
   ```
   ./gradlew :sdmx-proxy-config-server:build
   ```
   All existing `ConfigServiceTest` tests pass unchanged; four new tests pass.

2. **Manual: forced reseed overwrites non-empty storage (DIAL_STORAGE).**
   - Start config server with Dial Storage containing a modified config (e.g. rename one registry).
   - Stop, set `CONFIG_SERVER_FORCE_SEED=true`, restart.
   - Expect WARN `CONFIG_SERVER_FORCE_SEED=true — overwriting...` and INFO
     `Forced reseed complete: overwrote DIAL Storage with bundled default (...)` in logs.
   - `GET /config` (with API key) returns the bundled default, not the modified one.
   - Stop, set `CONFIG_SERVER_FORCE_SEED=false`, restart — logs show the normal
     `Config server initialized with configuration from DIAL_STORAGE source` path.

3. **Manual: filesystem mode with flag set fails fast.**
   - `CONFIG_SERVER_SOURCE_TYPE=FILESYSTEM`, `CONFIG_SERVER_FORCE_SEED=true`.
   - Startup aborts with `IllegalStateException: CONFIG_SERVER_FORCE_SEED=true is only
     supported with CONFIG_SERVER_SOURCE_TYPE=DIAL_STORAGE, got FILESYSTEM`.

4. **Manual: writer failure during forced reseed fails fast.**
   - Point `DIAL_STORAGE_BASE_URL` at an unreachable host, `CONFIG_SERVER_FORCE_SEED=true`.
   - Startup aborts; pod crashloops.

## Acceptance Criteria (from issue #50)

- [x] New env var `CONFIG_SERVER_FORCE_SEED` is read by the config server (default `false`).
- [x] When enabled with `CONFIG_SERVER_SOURCE_TYPE=DIAL_STORAGE`, startup always writes the
      bundled config to storage, overwriting any existing configuration.
- [x] When disabled, startup behaviour is byte-identical to today.
- [x] Unit tests in `ConfigServiceTest` cover: force-seed overwrites a non-empty config,
      force-seed is a no-op when disabled, force-seed is disallowed in filesystem mode.
      (Plus a fourth test for fatal writer failure on the forced path.)
- [x] `sdmx-proxy-config-server/README.md` documents the new variable, its footguns, and
      recommended deploy flow.
