# End-to-End Test Module Design for sdmx-proxy

> **Status:** Superseded by [design 019](../designs/019-e2e-against-review-env/DESIGN.md).
> Retained for historical context only. The Testcontainers / `GenericContainer` /
> `HttpWaitStrategy` / logs-gate model described below no longer reflects the
> code: the test module now runs against an externally provided base URL
> (`E2E_HOST` / `-Dsdmxproxy.e2e.host=...`) and does not start the proxy.

## 1) Scope and Non-Goals

### What E2E Tests Cover

- **Black-box container behavior**: Tests execute against a running Docker container of `sdmx-proxy`, treating it as an
  opaque system with defined HTTP contracts.
- **Core API endpoints**: Validates the 2-5 most critical endpoints (Structure, Data, and Availability) that represent
  the primary use cases of the service.
- **HTTP contract validation**: Verifies status codes, headers, response body structure, and error response formats
  conform to expected contracts.
- **Container health and readiness**: Ensures the container starts correctly and becomes ready for traffic via
  `/sdmx/proxy/api/v0/health` endpoint (returns HTTP 200 with "OK").
- **Logs gate**: Fails the test suite if ERROR/Exception patterns appear in container logs (with allow-list for known
  noise).
- **Integration with dependencies**: Optionally validates interaction with external dependencies (databases, caches,
  downstream services) if they are part of the containerized environment.

### What E2E Tests Do NOT Cover

- **Unit tests**: Internal Spring bean behavior, service layer logic, and component-level testing remain in
  `sdmx-proxy/src/test`.
- **Integration tests**: Tests that use `@SpringBootTest` with mocked or embedded dependencies belong in the main test
  suite, not E2E.
- **Load testing**: Performance, stress, and scalability testing are out of scope (use dedicated tools like Gatling,
  JMeter).
- **UI testing**: No browser automation or UI component validation.
- **Security penetration testing**: While basic authentication/authorization flows may be tested, deep security audits
  are separate.
- **Full API coverage**: E2E focuses on critical paths, not exhaustive endpoint coverage (that's for unit/integration
  tests).

---

## 2) Repository / Module Layout

### Proposed Multi-Module Structure

```
sdmx-proxy/
├── settings.gradle                    # Add: include 'sdmx-proxy', 'sdmx-proxy-e2e'
├── sdmx-proxy/                        # Existing application module
│   ├── build.gradle
│   └── src/
│       ├── main/
│       └── test/                      # Unit and integration tests remain here
└── sdmx-proxy-e2e/                    # New E2E test module
    ├── build.gradle
    └── src/
        └── test/
            ├── java/
            │   └── com/epam/sdmxproxy/e2e/
            │       ├── container/     # Container orchestration
            │       ├── fixtures/      # Test data and fixtures
            │       ├── health/        # Health check tests
            │       ├── contract/      # Contract validation tests
            │       └── logs/          # Logs gate implementation
            └── resources/
                ├── fixtures/          # SDMX request/response files
                │   ├── requests/
                │   ├── responses/
                │   └── schemas/
                ├── docker-compose.yml # Optional: for multi-service scenarios
                └── log-patterns/      # Log allow-list patterns
                    └── allowed-errors.properties
```

### Module Dependencies

**E2E Module MUST have:**

- `testImplementation` dependencies on:
    - `org.testcontainers:testcontainers` (core)
    - `org.testcontainers:junit-jupiter` (JUnit 5 integration)
    - `org.testcontainers:docker-compose` (if using Docker Compose)
    - `io.rest-assured:rest-assured` (HTTP client for black-box testing)
    - `org.assertj:assertj-core` (assertions)
    - `com.fasterxml.jackson.core:jackson-databind` (JSON parsing)
    - `org.apache.httpcomponents.client5:httpclient5` (if not using RestAssured)

**E2E Module MUST NOT have:**

- Any `implementation` or `compileOnly` dependency on `sdmx-proxy` internals.
- Spring Boot test dependencies (`@SpringBootTest`, `@MockBean`, etc.) — E2E is black-box.
- Direct access to application classes, configuration, or internal packages.
- Dependencies on application-specific libraries unless they are needed for parsing SDMX formats in test assertions.

**Rationale**: E2E tests must remain decoupled from application internals to ensure they validate the containerized
service as an external consumer would.

### Test Fixtures Location

- **SDMX request payloads**: `sdmx-proxy-e2e/src/test/resources/fixtures/requests/`
    - Naming: `{endpoint-name}_{scenario}_{version}.xml` or `.json`
    - Example: `dataquery_happy_path_sdmx30.xml`, `structurequery_invalid_agency.xml`
- **Expected response samples**: `sdmx-proxy-e2e/src/test/resources/fixtures/responses/`
    - Golden files for deterministic assertions (optional, see Section 6).
- **JSON schemas**: `sdmx-proxy-e2e/src/test/resources/fixtures/schemas/`
    - For JSON Schema validation of responses (if applicable).
- **Test configuration**: Environment-specific properties in `src/test/resources/application-e2e.properties` (if needed
  for test execution parameters).

---

## 3) Execution Model (Local and CI)

### Local Execution Flow

1. **Build Docker image** (requires `GPR_USERNAME` / `GPR_PASSWORD` env vars):
   ```bash
   # Windows (PowerShell)
   ./scripts/build-and-docker.ps1

   # Or directly from the repo root:
   docker build -f docker/sdmx-proxy.Dockerfile \
     -t statgpt/statgpt-sdmx-proxy:local \
     --secret id=GPR_USERNAME,env=GPR_USERNAME \
     --secret id=GPR_PASSWORD,env=GPR_PASSWORD \
     .
   ```

2. **Run E2E tests**:
   ```bash
   ./gradlew :sdmx-proxy-e2e:test -Ddocker.image.tag=local
   ```
   Or via IDE: Run test classes with system property `docker.image.tag=local`.

3. **View reports**:
    - JUnit XML: `sdmx-proxy-e2e/build/test-results/test/`
    - HTML reports: `sdmx-proxy-e2e/build/reports/tests/test/`
    - Container logs: Captured in test output or saved to `build/e2e-logs/` on failure.

### CI Execution Flow

**Stage: `e2e`** (runs after `build` and `publish` stages)

1. **Prerequisites**:
    - Docker image must be built and tagged in the `publish` stage.
    - Image tag strategy: Tag from CI job (e.g., `$CI_COMMIT_TAG`, `$CI_COMMIT_SHA`, `latest`).

2. **E2E job execution**:
    - Pull the published image from registry (registry path provided via CI environment variable).
    - Set environment variables: `DOCKER_IMAGE_TAG=<TAG>` and `DOCKER_IMAGE_REGISTRY=<REGISTRY>` (provided by CI job).
    - Run:
      `./gradlew :sdmx-proxy-e2e:test -Ddocker.image.tag=${DOCKER_IMAGE_TAG} -Ddocker.image.registry=${DOCKER_IMAGE_REGISTRY}`.
    - Publish test reports as CI artifacts (JUnit XML, HTML, container logs on failure).

3. **Conditional execution**:
    - Run E2E on: tags, merge requests to `main`/`development`, manual triggers.
    - Skip E2E on: fast builds, documentation-only changes, snapshot builds (unless explicitly requested).

### Image Tag and Registry Injection Strategy

- **System properties**:
    - `docker.image.tag` (default: `latest`).
    - `docker.image.registry` (default: empty, uses image name as-is).
- **Environment variables** (take precedence if set, provided by CI job):
    - `DOCKER_IMAGE_TAG` - Docker image tag.
  - `DOCKER_IMAGE_REGISTRY` - Docker registry URL
- **Test configuration class**: `com.epam.sdmxproxy.e2e.container.DockerImageConfig` reads the tag and registry,
  constructs full image name, and provides it to container orchestration classes.

**Example CI configuration**:

```yaml
"E2E Tests":
  stage: e2e
  image: ${GRADLE_IMAGE}
  variables:
    DOCKER_IMAGE_TAG: ${CI_COMMIT_TAG:-latest}
    DOCKER_IMAGE_REGISTRY: ${DOCKER_IMAGE_REGISTRY}  # Provided by CI
  script:
    - ./gradlew :sdmx-proxy-e2e:test -Ddocker.image.tag=${DOCKER_IMAGE_TAG} -Ddocker.image.registry=${DOCKER_IMAGE_REGISTRY}
  artifacts:
    when: always
    paths:
      - sdmx-proxy-e2e/build/test-results/
      - sdmx-proxy-e2e/build/reports/
      - sdmx-proxy-e2e/build/e2e-logs/
    expire_in: 7 days
  needs:
    - "Publish Dockers"
  rules:
    - if: "$CI_COMMIT_TAG"
    - if: "$CI_PIPELINE_SOURCE == 'merge_request_event'"
```

---

## 4) Container Orchestration Strategy

### Decision: GenericContainer vs DockerComposeContainer

**Use `GenericContainer` (single container)** when:

- `sdmx-proxy` is self-contained or uses external services that are already running (e.g., managed Redis, external SDMX
  registries).
- Tests focus on the application container only.
- Simpler setup and faster startup.

**Use `DockerComposeContainer` (multi-service)** when:

- `sdmx-proxy` requires local dependencies (e.g., Redis, PostgreSQL, mock SDMX registry) that must be started together.
- Tests need to validate interactions between multiple containers.
- Dependencies are defined in `docker-compose.yml` for local development.

**Recommendation**: Start with `GenericContainer` for simplicity. Migrate to `DockerComposeContainer` if dependencies
become necessary.

### Container Lifecycle and Readiness Strategy

**Class**: `com.epam.sdmxproxy.e2e.container.SdmxProxyContainer`

**Responsibilities**:

- Extends `GenericContainer` (single container approach).
- Configures image name/tag from `DockerImageConfig`.
- Sets environment variables required for container startup (may be configured later if needed).
- Exposes port `8050` and maps to a random host port.
- Implements readiness wait strategy.

**Readiness Wait Strategy**:

- **Endpoint**: `/sdmx/proxy/api/v0/health` (from `HealthCheckController`).
- **Condition**: HTTP 200 response with body containing "OK".
- **Implementation**: Use `HttpWaitStrategy` from Testcontainers:
    - Poll interval: 1 second.
    - Timeout: 60 seconds (configurable via system property `e2e.startup.timeout.seconds`).
    - Retries: Up to timeout duration.
    - Expected status: 200.
    - Optional: Body matcher for JSON health response.

**Base URL Derivation**:

- After container is ready, derive base URL: `http://${container.getHost()}:${container.getMappedPort(8050)}`.
- Store in a static field or test context for use by all test classes.

**Example structure** (conceptual):

- `SdmxProxyContainer`: Singleton container instance, started once per test suite.
- `ContainerFixture`: JUnit extension or base class that manages container lifecycle and provides base URL to tests.

---

## 5) Test Suite Structure

### Package Structure

```
com.epam.sdmxproxy.e2e/
├── container/
│   ├── SdmxProxyContainer.java           # Container wrapper (GenericContainer/DockerComposeContainer)
│   ├── DockerImageConfig.java            # Reads docker.image.tag from system/env
│   └── ContainerFixture.java             # JUnit extension or @BeforeAll/@AfterAll base class
├── fixtures/
│   ├── SdmxRequestLoader.java           # Loads SDMX request files from resources
│   ├── ResponseValidator.java           # Validates responses against schemas/golden files
│   └── TestDataProvider.java            # Provides test data for parameterized tests
├── health/
│   └── HealthCheckTests.java            # Tests health/readiness endpoint
├── contract/
│   ├── HappyPathContractTests.java      # Validates successful API calls
│   ├── NegativeContractTests.java      # Validates error responses (4xx, 5xx)
│   └── HeaderContractTests.java        # Validates required/optional headers
├── logs/
│   ├── LogsGate.java                   # JUnit extension that checks logs after suite
│   └── LogPatternMatcher.java          # Matches ERROR/Exception patterns with allow-list
└── util/
    ├── RestClient.java                  # Wrapper around RestAssured for HTTP calls
    └── SdmxResponseParser.java         # Parses SDMX XML/JSON responses for assertions
```

### Class Responsibilities

**`SdmxProxyContainer`**:

- Manages Docker container lifecycle (start/stop).
- Configures image, ports, environment variables.
- Implements readiness wait strategy.
- Provides base URL and container instance to tests.

**`ContainerFixture`**:

- JUnit 5 extension (`@ExtendWith(ContainerFixture.class)`) or base test class.
- Ensures container starts once per test suite (not per test method).
- Provides static accessor for base URL: `ContainerFixture.getBaseUrl()`.
- Handles container cleanup in `@AfterAll` or extension cleanup.

**`HealthCheckTests`**:

- Test class: `@TestInstance(TestInstance.Lifecycle.PER_CLASS)`.
- Tests: `healthEndpointReturns200()`, `healthEndpointContainsUpStatus()`, `readinessProbeWorks()`.

**`HappyPathContractTests`**:

- Validates successful API calls to the primary endpoints:
    - `GET /sdmx/proxy/api/v0/data/{context}/{agencyID}/{resourceID}/{version}/{key}` (Data endpoint)
    - `GET /sdmx/proxy/api/v0/structure/{structureType}/{agencyId}/{resourceId}/{version}` (Structure endpoint)
    - `GET /sdmx/proxy/api/v0/availability/{context}/{agencyID}/{resourceID}/{version}/{key}/{componentId}` (
      Availability endpoint)
- Test methods per endpoint: `testDataQueryEndpoint()`, `testStructureQueryEndpoint()`,
  `testAvailabilityQueryEndpoint()`.
- Uses `RestClient` to send requests and `ResponseValidator` for assertions.

**`NegativeContractTests`**:

- Validates error handling: invalid parameters, missing headers, malformed requests.
- Tests: `testInvalidAgencyIdReturns400()`, `testNotFoundReturns404()`, `testInvalidContextReturns400()`.
- Validates error response structure (status, error code, message format).
- Note: Authentication is not required (no auth headers needed).

**`LogsGate`**:

- JUnit 5 extension: `@ExtendWith(LogsGate.class)`.
- Runs after all tests complete (`@AfterAll` equivalent).
- Reads container logs via `container.getLogs()`.
- Uses `LogPatternMatcher` to detect ERROR/Exception patterns.
- Fails the suite if disallowed patterns are found (after applying allow-list).

**`LogPatternMatcher`**:

- Loads allow-list from `src/test/resources/log-patterns/allowed-errors.properties`.
- Matches log lines against regex patterns for ERROR/Exception/Stacktrace.
- Returns violations (patterns found that are not in allow-list).

### Lifecycle: Per-Suite vs Per-Test Container

**Recommendation: Per-Suite Container** (one container for all tests).

**Rationale**:

- Faster execution: container starts once, all tests run, container stops.
- More realistic: simulates a long-running service.
- Resource efficient: lower CPU/memory usage in CI.

**Trade-offs**:

- **State pollution**: Tests must be independent and not rely on shared state. Use unique test data (IDs, timestamps) or
  clean up after each test if needed.
- **Isolation**: If one test corrupts container state, subsequent tests may fail. Mitigate by designing stateless tests
  or using test-specific identifiers.

**Alternative: Per-Test Container** (only if tests modify persistent state):

- Use `@Testcontainers` annotation with `@Container` instance field (not static).
- Slower but provides complete isolation.
- Consider for tests that write to databases or modify filesystem state.

**Implementation**:

- Use static `@Container` field in `ContainerFixture` or a singleton pattern.
- Start container in `@BeforeAll` (or extension `beforeAll`).
- Stop container in `@AfterAll` (or extension `afterAll`).

---

## 6) Assertions and Contracts

### Response Assertion Strategy

**HTTP Status Codes**:

- Assert exact status codes: `assertThat(response.getStatusCode()).isEqualTo(200)`.
- Use RestAssured or AssertJ assertions.

**Headers**:

- Required headers: `Content-Type`, `Cache-Control` (if applicable), custom headers.
- Assert header presence and values: `assertThat(response.getHeader("Content-Type")).contains("application/json")`.

**Response Body**:

- **Option A — Selective field assertions** (recommended for E2E):
    - Parse JSON/XML response (SDMX XML or JSON format).
    - Crawl through XML/JSON objects and verify required information is present.
    - Assert critical fields: status, data structure, error codes, required SDMX elements.
    - Ignore non-deterministic fields (timestamps, IDs) or normalize them.
    - Example: Assert that SDMX structure response contains required elements (e.g., dataflow, datastructure
      components), or that data response contains expected series/observations.

- **Option B — JSON Schema validation**:
    - Define JSON schemas in `src/test/resources/fixtures/schemas/`.
    - Use `org.everit.json.schema` or `com.networknt.schema` to validate responses.
    - Good for ensuring response structure compliance, but may be brittle for optional fields.

- **Option C — Golden file comparison** (use sparingly):
    - Store expected full responses in `fixtures/responses/`.
    - Compare actual vs expected after normalization.
    - Useful for regression testing but requires maintenance when APIs evolve.

**Recommendation**: Use **Option A** (selective assertions) for E2E tests. Reserve schema validation and golden files
for integration tests or specific contract testing scenarios.

### Handling Nondeterministic Fields

**Fields to normalize or ignore**:

- Timestamps: Extract and assert format (ISO 8601) but not exact value, or assert it's within a time window.
- Request IDs / Correlation IDs: Assert presence and format (UUID), ignore exact value.
- Version numbers: Assert presence, ignore exact build number if it changes.
- Container-specific fields: Hostnames, internal IPs — ignore or assert format only.

**Normalization strategies** (design only, no code):

- **Regex replacement**: Replace timestamp patterns with placeholders before comparison.
- **JSON path filtering**: Extract only fields under test, ignore others.
- **Custom matchers**: Use AssertJ custom matchers that compare structure while ignoring specific paths.

**Example** (conceptual):

- Response contains `{"timestamp": "2024-01-15T10:30:00Z", "data": [...]}`.
- Assertion: Verify `data` array structure and content, ignore `timestamp` or assert it matches ISO 8601 pattern.

---

## 7) Logs Gate Design

### Patterns That Should Fail the Suite

**Error patterns to detect**:

- `ERROR` log level entries (case-insensitive).
- Exception class names: `Exception`, `RuntimeException`, `NullPointerException`, etc.
- Stack trace indicators: `at com.`, `Caused by:`, `java.lang.`.
- Application-specific error patterns: Custom exception classes from `com.epam.sdmxproxy`.

**Implementation approach**:

- Scan container logs line by line.
- Match against regex patterns: `(?i).*ERROR.*`, `.*Exception.*`, `.*Caused by:.*`, `.*at\s+\w+\.\w+\.`.
- Collect all matches.
- Filter against allow-list.
- Fail if any non-allowed patterns remain.

### Allow-List Design

**File**: `sdmx-proxy-e2e/src/test/resources/log-patterns/allowed-errors.properties`

**Format**:

```properties
# Pattern description
allowed.pattern.1=.*ERROR.*Connection.*pool.*exhausted.*
allowed.pattern.2=.*WARN.*Deprecated.*API.*
allowed.pattern.3=.*Exception.*in.*background.*task.*
```

**Maintenance strategy**:

- Start with an empty allow-list.
- When a test fails due to a known benign error, add the pattern to the allow-list with a comment explaining why.
- Review allow-list periodically (quarterly) to remove patterns that are no longer relevant.
- Document each pattern: what it means, why it's allowed, when it was added.

**Pattern matching**:

- Use Java `Pattern.compile()` with case-insensitive matching.
- Match entire log line or substring (design decision: prefer substring matching for flexibility).

### When to Check Logs

**Recommendation: After the entire test suite** (suite-level logs gate).

**Rationale**:

- Single check is faster than checking after each test.
- Catches errors that don't cause HTTP failures (background tasks, async processing).
- Aligns with per-suite container lifecycle.

**Alternative: After each test** (only if needed for debugging):

- Use `@AfterEach` to check logs after individual tests.
- More granular but slower and may produce noise if errors are transient.

**Implementation**:

- `LogsGate` extension runs in `afterAll()` phase.
- Reads logs via `container.getLogs()` (or `container.getLogsFrom(container.getContainerId())`).
- Applies `LogPatternMatcher` to detect violations.
- If violations found, fails the suite with a summary: "Logs gate failed: Found 3 ERROR patterns not in
  allow-list: [pattern1, pattern2, pattern3]".

### Structured Logs (Optional Enhancement)

**If the application uses structured logging (JSON)**:

- Parse logs as JSON instead of plain text.
- Filter by `level` field: `level == "ERROR"`.
- Extract `exception` and `stackTrace` fields if present.
- More precise than regex matching on plain text.

**Design consideration**: If structured logs are available, `LogPatternMatcher` should support both JSON and plain text
formats, with JSON taking precedence.

---

## 8) Test Data and Fixtures

### SDMX Request/Response Storage

**Location**: `sdmx-proxy-e2e/src/test/resources/fixtures/`

**Directory structure**:

```
fixtures/
├── requests/
│   ├── dataquery/
│   │   ├── happy_path_sdmx30.xml
│   │   ├── invalid_agency.xml
│   │   └── missing_version.xml
│   ├── structurequery/
│   │   ├── datastructure_request.xml
│   │   └── dataflow_request.xml
│   └── availabilityquery/
│       └── basic_request.xml
├── responses/
│   └── (optional golden files for comparison)
└── schemas/
    └── (optional JSON schemas for validation)
```

**Test Data Strategy**:

- Create fixtures that represent valid SDMX requests for each endpoint.
- Test assertions will crawl through XML/JSON response objects and verify required information is present.
- Use SDMX-aware parsers to navigate structure and validate:
    - Required SDMX elements are present (e.g., Structure, Dataflow, Dataset, Series, Observations).
    - Data types and formats are correct.
    - Relationships between elements are valid (e.g., dimensions, attributes, measures).

### Naming Conventions

**Request files**:

- Format: `{endpoint}_{scenario}_{sdmx_version}.{extension}`
- Examples:
    - `dataquery_happy_path_sdmx30.xml`
    - `dataquery_invalid_key_400.xml`
    - `structurequery_datastructure_v2.1.xml`

**Response files** (if using golden files):

- Format: `{endpoint}_{scenario}_{sdmx_version}_expected.{extension}`
- Examples: `dataquery_happy_path_sdmx30_expected.json`

**Schema files**:

- Format: `{endpoint}_{version}_schema.json`
- Examples: `dataquery_v3.0_schema.json`

### Versioning Fixtures When API Changes

**Strategy**:

- **Breaking changes**: Create new fixture files with version suffix (e.g., `dataquery_happy_path_v2.xml`).
- **Non-breaking changes**: Update existing fixtures and document the change in commit message.
- **Deprecation**: Keep old fixtures for backward compatibility tests, mark as deprecated in filename or comments.

**Maintenance**:

- Review fixtures during API version bumps.
- Remove fixtures for deprecated API versions after a grace period (e.g., 2 major versions).
- Document fixture changes in `fixtures/README.md` (optional but recommended).

**Loader and Validator classes**:

- `SdmxRequestLoader`: Reads fixtures from classpath and provides them to test methods, handling file I/O and encoding (
  UTF-8).
- `SdmxResponseParser`: Parses SDMX XML/JSON responses and provides methods to crawl through objects.
- `ResponseValidator`: Validates that required SDMX elements and information are present in responses by crawling
  through the parsed structure.

---

## 9) Reporting and Diagnostics

### Artifacts to Publish

**JUnit XML**:

- Location: `sdmx-proxy-e2e/build/test-results/test/`
- Format: Standard JUnit XML for CI integration (GitLab, Jenkins, etc.).
- Contains: Test names, status, duration, failure messages.

**HTML Reports**:

- Location: `sdmx-proxy-e2e/build/reports/tests/test/`
- Generated by Gradle test task.
- Contains: Test summary, individual test results, failure details.

**Container Logs on Failure**:

- Location: `sdmx-proxy-e2e/build/e2e-logs/`
- Saved when: Any test fails or logs gate fails.
- Naming: `container-logs-{timestamp}.txt` or `container-logs-{test-class}-{timestamp}.txt`.
- Content: Full container stdout/stderr logs.

**Request/Response Dumps** (sanitized):

- Location: `sdmx-proxy-e2e/build/e2e-logs/request-response-dumps/`
- Saved when: Test failures occur (configurable via system property `e2e.dump.requests.on.failure=true`).
- Format: Separate files per request/response pair, or combined JSON.
- Sanitization: Remove sensitive headers (Authorization, API keys), mask PII if present.

**Test Execution Summary**:

- Console output: Summary of tests run, passed, failed, skipped.
- CI artifacts: Attach summary as `e2e-summary.txt` for quick review.

### Making Failures Actionable

**Failure messages should include**:

- **What failed**: Test name and assertion that failed.
- **Expected vs actual**: Clear diff of expected vs actual values.
- **Request details**: HTTP method, URL, request headers (sanitized), request body (if applicable).
- **Response details**: Status code, response headers, response body (truncated if large, full body in logs).
- **Container state**: Container logs excerpt around the failure time (last 50 lines).
- **Diagnostic hints**: Common causes and troubleshooting steps (e.g., "Check if container started correctly", "Verify
  environment variables").

**Example failure message structure** (conceptual):

```
Test: testDataQueryEndpoint
Assertion: Response status code
Expected: 200
Actual: 500
Request: GET http://localhost:32891/sdmx/proxy/api/v0/data/dataflow/AGENCY/RESOURCE/1.0/KEY
Response body: {"error": "Internal server error", "message": "..."}
Container logs (last 20 lines):
  [ERROR] Exception in thread main: ...
  [ERROR] Caused by: ...
```

**CI integration**:

- Publish all artifacts (`when: always`) so logs are available even if tests pass (for debugging flakiness).
- Set artifact expiration: 7-30 days depending on storage constraints.

---

## 10) Risks and Mitigations

### Flakiness Sources

**1. Container startup time**:

- **Risk**: Container takes longer than expected to become ready, causing timeout failures.
- **Mitigation**:
    - Increase readiness timeout via system property (default 60s, configurable to 120s).
    - Use exponential backoff in readiness checks.
    - Log startup time in CI to monitor trends.

**2. Network issues**:

- **Risk**: Transient network failures between test runner and container, or container and external dependencies.
- **Mitigation**:
    - Use `localhost` binding for container ports (Testcontainers default).
    - Add retry logic for HTTP requests (max 3 retries with exponential backoff).
    - Mark flaky tests with `@Flaky` annotation and retry in CI (if supported).

**3. Resource constraints in CI**:

- **Risk**: CI runners have limited CPU/memory, causing slow container startup or OOM kills.
- **Mitigation**:
    - Use lightweight base images (Alpine Linux).
    - Set container memory limits:
      `withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withMemory(512 * 1024 * 1024L))`.
    - Run E2E on dedicated CI runners with sufficient resources.
    - Monitor CI runner resource usage and scale if needed.

**4. Dependency availability**:

- **Risk**: External dependencies (SDMX registries, databases) are unavailable or slow.
- **Mitigation**:
    - Use Docker Compose to start mock dependencies locally.
    - For external dependencies, add health checks and skip tests if dependencies are down (with clear logging).
    - Use test doubles (WireMock, MockServer) for external HTTP services.

**5. Test isolation**:

- **Risk**: Tests interfere with each other due to shared container state.
- **Mitigation**:
    - Design stateless tests (use unique identifiers per test).
    - If stateful, add cleanup in `@AfterEach` or use test-specific data namespaces.
    - Consider per-test containers if isolation is critical (with performance trade-off).

### Resource Usage in CI

**Memory**:

- Container: ~512MB-1GB (configurable).
- Test JVM: ~256MB-512MB.
- Total per E2E job: ~1-2GB.

**CPU**:

- Container startup: High CPU during first 10-30 seconds.
- Test execution: Low to moderate CPU.
- Parallel execution: Limit parallel E2E jobs to avoid resource exhaustion.

**Disk**:

- Docker images: ~200-500MB per image.
- Test artifacts: ~10-50MB per run (logs, reports).
- Cleanup: Remove old images and artifacts periodically.

**Mitigation strategies**:

- Use Docker layer caching in CI to speed up image pulls.
- Run E2E on dedicated runners or schedule during off-peak hours.
- Set resource limits on containers and CI jobs.

### Keeping E2E Stable and Fast

**Stability**:

- **Idempotent tests**: Tests should produce the same results on repeated runs.
- **Deterministic data**: Use fixed test data, avoid random values unless necessary.
- **Time-independent**: Mock or normalize timestamps in assertions.
- **Environment isolation**: Don't rely on external state (databases, file systems) unless containerized.

**Speed**:

- **Parallel execution**: Run independent test classes in parallel (JUnit 5
  `junit.jupiter.execution.parallel.enabled=true`).
- **Selective execution**: Tag tests (`@Tag("e2e")`) and allow running subsets:
  `./gradlew :<E2E_MODULE_NAME>:test --tests "*HealthCheckTests"`.
- **Optimize container startup**: Use pre-built images, minimize image size, use health checks instead of fixed delays.
- **Target execution time**: Aim for < 5 minutes for full E2E suite (adjust based on complexity).

**Monitoring**:

- Track E2E execution time in CI metrics.
- Alert on flakiness rate (failure rate > 10% on retries).
- Review and refactor slow tests regularly.

---

## Recommended Defaults

**Container lifecycle**: Per-suite container (one container for all tests).

**Container orchestration**: `GenericContainer` (single container), migrate to `DockerComposeContainer` if dependencies
are needed.

**Readiness strategy**: HTTP wait on `/sdmx/proxy/api/v0/health` endpoint (returns HTTP 200 with "OK") with 60-second
timeout, 1-second poll interval.

**Image tag and registry injection**:

- System properties: `docker.image.tag` (default: `latest`), `docker.image.registry` (default: empty).
- Environment variables: `DOCKER_IMAGE_TAG` and `DOCKER_IMAGE_REGISTRY` (provided by CI job, take precedence).

**Test execution**: Run E2E in separate CI stage (`e2e`) after `publish`, not on every build.

**Assertion strategy**: Selective field assertions (Option A from Section 6), not golden files or full schema
validation.

**Logs gate**: Check logs after entire suite completes, not after each test.

**Test data**: Store SDMX fixtures in `src/test/resources/fixtures/requests/` with naming convention
`{endpoint}_{scenario}_{version}.xml`.

**Reporting**: Publish JUnit XML, HTML reports, and container logs on failure (always publish logs for debugging).

**Execution time target**: < 5 minutes for full E2E suite.

**Resource limits**: Container memory limit 512MB-1GB, monitor and adjust based on actual usage.

---

## Project-Specific Values

The following values have been set for this project:

- **Module names**:
    - `<APP_MODULE_NAME>` → `sdmx-proxy`
    - `<E2E_MODULE_NAME>` → `sdmx-proxy-e2e`
- **Packages**:
    - `<BASE_PACKAGE>` → `com.epam.sdmxproxy`
    - `<E2E_BASE_PACKAGE>` → `com.epam.sdmxproxy.e2e`
- **Docker configuration**:
    - `<DOCKER_IMAGE_NAME>` → `statgpt/statgpt-sdmx-proxy`
    - `<PORT>` → `8050`
    - Docker image tag: Provided via `DOCKER_IMAGE_TAG` environment variable from CI job
- **Primary API endpoints**:
    - `GET /sdmx/proxy/api/v0/data/{context}/{agencyID}/{resourceID}/{version}/{key}` (Data endpoint)
    - `GET /sdmx/proxy/api/v0/structure/{structureType}/{agencyId}/{resourceId}/{version}` (Structure endpoint)
    - `GET /sdmx/proxy/api/v0/availability/{context}/{agencyID}/{resourceID}/{version}/{key}/{componentId}` (
      Availability endpoint)
- **Health endpoint**: `/sdmx/proxy/api/v0/health` (from `HealthCheckController`, returns HTTP 200 with "OK")
- **Container orchestration**: `GenericContainer` (single container)
- **Docker registry**: Provided via `DOCKER_IMAGE_REGISTRY` environment variable from CI
- **Authentication**: Not required (proxy works without authentication)
- **Test data validation**: Fixtures will crawl through XML/JSON objects and verify required SDMX information is present

---

## Implementation Decisions

The following decisions have been made:

1. **Health Endpoint**:
    - Endpoint: `/sdmx/proxy/api/v0/health` (from `HealthCheckController`)
    - Readiness condition: HTTP 200 response with body containing "OK"

2. **Docker Image Registry**:
    - Registry path will be provided via `DOCKER_IMAGE_REGISTRY` environment variable from CI job
    - Full image name will be constructed as `${DOCKER_IMAGE_REGISTRY}/statgpt/statgpt-sdmx-proxy:${DOCKER_IMAGE_TAG}`

3. **Test Data**:
    - Fixtures will be created to represent valid SDMX requests
    - Test assertions will crawl through XML/JSON response objects and verify required SDMX information is present
    - Validation will check for required elements, data types, formats, and relationships

4. **Authentication**:
    - Authentication checks have been removed from the application
    - E2E tests do not need to handle authentication (proxy works without auth)

---

**Document Version**: 1.0  
**Last Updated**: [Date]  
**Author**: [Your Name/Team]
