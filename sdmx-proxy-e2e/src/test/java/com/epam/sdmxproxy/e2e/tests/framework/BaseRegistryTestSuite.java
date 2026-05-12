package com.epam.sdmxproxy.e2e.tests.framework;

import com.epam.sdmxproxy.configuration.data.AvailabilityEndpointConfiguration;
import com.epam.sdmxproxy.configuration.data.DataEndpointConfiguration;
import com.epam.sdmxproxy.configuration.data.ProxyConfiguration;
import com.epam.sdmxproxy.configuration.data.ReturnFormat;
import com.epam.sdmxproxy.configuration.data.StructureEndpointConfiguration;
import com.epam.sdmxproxy.configuration.data.VersionSpecificRegistryConfiguration;
import com.epam.sdmxproxy.e2e.support.fixtures.ResponseValidator;
import com.epam.sdmxproxy.e2e.support.sdmx.StructureQueryDetail;
import com.epam.sdmxproxy.e2e.support.sdmx.StructureReferenceDetail;
import com.epam.sdmxproxy.e2e.support.url.BaseUrlProvider;
import com.epam.sdmxproxy.e2e.support.util.RestClient;
import com.epam.sdmxproxy.e2e.tests.framework.config.DataflowKeyCase;
import com.epam.sdmxproxy.e2e.tests.framework.config.LimitTestSuitConfiguration;
import com.epam.sdmxproxy.e2e.tests.framework.config.RegistryTestSuitConfiguration;
import com.epam.sdmxproxy.e2e.tests.framework.config.StructureTypeAndUrn;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pavelicii.allpairs4j.AllPairs;
import io.github.pavelicii.allpairs4j.Case;
import io.github.pavelicii.allpairs4j.Parameter;
import io.restassured.response.Response;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Abstract base class for registry-specific test suites.
 * Contains all test methods that can be reused for any registry:
 * generic structures, specific structures, data endpoint, and availability endpoint.
 * <p>
 * Test cases are generated using the AllPairs pairwise algorithm to achieve
 * comprehensive coverage with a manageable number of test combinations.
 * <p>
 * Subclasses must implement:
 * - {@link #getProxyConfig()} - provides registry configuration
 * - {@link #getTestConfig()} - provides test data with per-dataset/structure/availability configs
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("registry")
public abstract class BaseRegistryTestSuite {

    /**
     * Registry data formats for which {@code LimitEmulationService} has a truncator
     * implementation today. Registries whose {@code dataEndpointConfig.defaultFormat} is
     * not in this set can't run the emulation diagnostic -- the test softly aborts with
     * an actionable message naming the missing truncator format.
     */
    private static final Set<ReturnFormat> TRUNCATOR_SUPPORTED_FORMATS = Set.of(
            ReturnFormat.JSON_1_0_0,
            ReturnFormat.JSON_DATA_2_0_0,
            ReturnFormat.CSV_DATA_2_0_0,
            ReturnFormat.XML_GENERICDATA_2_1,
            ReturnFormat.XML_STRUCTURE_SPECIFIC_2_1
    );

    private static final String BASE_PATH = "/statgpt/sdmx-proxy/api/v0";
    public static final String CONFIG_PATH = BASE_PATH + "/config";
    private static final List<String> STRUCTURE_SUPPORTED_PROXY_FORMATS = List.of(
            "application/vnd.sdmx.structure+xml;version=2.1",
            "application/vnd.sdmx.structure+json;version=2.0.0"
    );
    private static final List<String> AVAILABILITY_MODES = List.of("exact", "available");

    private final ObjectMapper objectMapper = new ObjectMapper();
    protected RestClient restClient;
    protected ResponseValidator responseValidator;
    protected ProxyConfiguration proxyConfig;
    protected RegistryTestSuitConfiguration testConfig;
    private List<Case> specificStructureCases;
    private List<Case> dataCases;
    private List<Case> availabilityCases;
    private static final Pattern SHORT_URN = Pattern.compile(
            "^(?<agency>[^:]+):(?<resourceId>.+)\\((?<version>[^)]+)\\)$");

    /**
     * Parses a short SDMX URN to extract agency, resource ID, and version.
     * <p>
     * Format: {@code agency:resourceId(version)}
     *
     * @param urn the short URN string (e.g. {@code IMF.STA:QGFS(9.0.0)})
     * @return a String array with [agency, resourceId, version]
     */
    private static String[] parseUrn(String urn) {
        Matcher m = SHORT_URN.matcher(urn);
        return m.matches()
                ? new String[]{m.group("agency"), m.group("resourceId"), m.group("version")}
                : new String[]{urn, "", ""};
    }

    private AllPairs.AllPairsBuilder getSpecificStructureCases() {
        var structConfig = testConfig.getStructuresTestSuitConfiguration();

        return new AllPairs.AllPairsBuilder()
                .withParameter(new Parameter("artefact", new ArrayList<>(structConfig.getArtefacts())))
                .withParameter(new Parameter("detail", List.of(StructureQueryDetail.values())))
                .withParameter(new Parameter("references", List.of(StructureReferenceDetail.values())))
                .withParameter(new Parameter("registryReturnFormat", new ArrayList<>(structConfig.getRegistryReturnFormats())))
                .withParameter(new Parameter("proxyFormat", new ArrayList<>(structConfig.getMediaTypes())));
    }

    private AllPairs.AllPairsBuilder getDataCases() {
        var dataConfig = testConfig.getDataTestSuitConfiguration();

        var dataflowCases = new ArrayList<>(dataConfig.getDataflowConfigs().stream()
                .flatMap(dc -> dc.getKeys().stream()
                        .map(key -> new DataflowKeyCase(dc.getUrn(), key)))
                .distinct()
                .toList());

        return new AllPairs.AllPairsBuilder()
                .withParameter(new Parameter("dataflowCase", dataflowCases))
                .withParameter(new Parameter("registryReturnFormat", new ArrayList<>(dataConfig.getRegistryReturnFormats())))
                .withParameter(new Parameter("proxyFormat", new ArrayList<>(dataConfig.getMediaTypes())));
    }

    private static VersionSpecificRegistryConfiguration getVersionSpecificRegistryConfiguration(ProxyConfiguration proxyConfig) {
        return proxyConfig.getConfigs()
                .getFirst()
                .getVersions()
                .entrySet()
                .stream()
                .findAny()
                .get()
                .getValue();
    }

    private AllPairs.AllPairsBuilder getAvailabilityCases() {
        var availConfig = testConfig.getAvailabilityTestSuitConfiguration();

        // Dedupe (urn, key) tuples -- DataflowKeyCase doesn't carry the filters field, so
        // two config entries that differ only by filters (e.g. one with {FREQ=M}, another
        // without) collapse to the same case here. AllPairs rejects duplicate parameter
        // values, so without distinct() the suite fails at initialization. Duplicates
        // surface as one test case regardless of how many config entries produced them.
        var dataflowCases = new ArrayList<>(availConfig.getDataflowConfigs().stream()
                .flatMap(dc -> dc.getKeys().stream()
                        .map(key -> new DataflowKeyCase(dc.getUrn(), key)))
                .distinct()
                .toList());

        return new AllPairs.AllPairsBuilder()
                .withParameter(new Parameter("dataflowCase", dataflowCases))
                .withParameter(new Parameter("mode", new ArrayList<>(AVAILABILITY_MODES)))
                .withParameter(new Parameter("registryReturnFormat", new ArrayList<>(availConfig.getRegistryReturnFormats())))
                .withParameter(new Parameter("proxyFormat", new ArrayList<>(availConfig.getMediaTypes())));
    }

    @BeforeAll
    @SneakyThrows
    void setUp() {
        restClient = new RestClient(BaseUrlProvider.getBaseUrl());
        responseValidator = new ResponseValidator();
        proxyConfig = getProxyConfig();
        testConfig = getTestConfig();

        specificStructureCases = getSpecificStructureCases().build().getGeneratedCases();
        dataCases = getDataCases().build().getGeneratedCases();
        availabilityCases = getAvailabilityCases().build().getGeneratedCases();

        restClient.postResponse(CONFIG_PATH, objectMapper.writeValueAsString(proxyConfig));
    }

    /**
     * Subclasses must provide registry configuration.
     */
    protected abstract ProxyConfiguration getProxyConfig();

    /**
     * Subclasses must provide test data with per-dataset/structure/availability configs.
     */
    protected abstract RegistryTestSuitConfiguration getTestConfig();

    Stream<Arguments> specificStructureCases() {
        return specificStructureCases.stream()
                .map(testCase -> Arguments.of(
                        testCase.get("artefact"),
                        testCase.get("detail"),
                        testCase.get("references"),
                        testCase.get("registryReturnFormat"),
                        testCase.get("proxyFormat")
                ));
    }

    @ParameterizedTest(name = "artefact: {0}, detail: {1}, references: {2}, registryReturnFormat: {3}, proxyFormat: {4}")
    @DisplayName("Specific Structure Cases")
    @MethodSource("specificStructureCases")
    void testSpecificStructureTypes(StructureTypeAndUrn artefact, StructureQueryDetail detail, StructureReferenceDetail references, ReturnFormat registryReturnFormat, String proxyFormat) {
        updateConfigToMatchRegistryReturnType(registryReturnFormat);

        String[] urnParts = parseUrn(artefact.getUrn());
        String agency = urnParts[0];
        String resourceId = urnParts[1];
        String version = urnParts[2];

        String path = String.format("%s/sdmx/3.0/structure/%s/%s/%s/%s?references=%s&detail=%s",
                BASE_PATH,
                artefact.getType(),
                agency,
                resourceId,
                version,
                references,
                detail
        );

        Response response = restClient.getResponseWithAccept(path, proxyFormat);

        assertThat(response.getStatusCode())
                .as("Structure endpoint should return HTTP 200 for specific artefact: %s (%s)", artefact.getType(), artefact.getUrn())
                .isEqualTo(200);

        String responseBody = response.getBody().asString();
        assertThat(responseBody)
                .as("Response body should not be empty")
                .isNotEmpty();

        assertThat(responseBody)
                .as("Response should contain %s element", artefact.getType())
                .containsIgnoringCase(artefact.getType());
    }

    Stream<Arguments> dataCases() {
        return dataCases.stream()
                .map(testCase -> {
                    DataflowKeyCase dc = (DataflowKeyCase) testCase.get("dataflowCase");
                    return Arguments.of(
                            dc.getUrn(),
                            dc.getKey(),
                            testCase.get("registryReturnFormat"),
                            testCase.get("proxyFormat")
                    );
                });
    }

    @ParameterizedTest(name = "dataflow: {0}, key: {1}, registryReturnFormat: {2}, proxyFormat: {3}")
    @DisplayName("Data Endpoint Cases")
    @MethodSource("dataCases")
    void testDataEndpoint(String dataflowUrn, String key, ReturnFormat registryReturnFormat, String proxyFormat) {
        updateDataConfigToMatchRegistryReturnType(registryReturnFormat);

        String[] urnParts = parseUrn(dataflowUrn);
        String agency = urnParts[0];
        String resourceId = urnParts[1];
        String version = urnParts[2];

        String path = String.format("%s/sdmx/3.0/data/dataflow/%s/%s/%s/%s",
                BASE_PATH,
                agency,
                resourceId,
                version,
                key
        );

        Response response = restClient.getResponseWithAccept(path, proxyFormat);

        assertThat(response.getStatusCode())
                .as("Data endpoint should return HTTP 200 for dataflow: %s, key: %s", dataflowUrn, key)
                .isEqualTo(200);

        String responseBody = response.getBody().asString();
        assertThat(responseBody)
                .as("Response body should not be empty")
                .isNotEmpty();
    }

    Stream<Arguments> availabilityCases() {
        return availabilityCases.stream()
                .map(testCase -> {
                    DataflowKeyCase dc = (DataflowKeyCase) testCase.get("dataflowCase");
                    return Arguments.of(
                            dc.getUrn(),
                            dc.getKey(),
                            testCase.get("mode"),
                            testCase.get("registryReturnFormat"),
                            testCase.get("proxyFormat")
                    );
                });
    }

    @ParameterizedTest(name = "dataflow: {0}, key: {1}, mode: {2}, registryReturnFormat: {3}, proxyFormat: {4}")
    @DisplayName("Availability Endpoint Cases")
    @MethodSource("availabilityCases")
    void testAvailabilityEndpoint(String dataflowUrn, String key, String mode, ReturnFormat registryReturnFormat, String proxyFormat) {
        updateAvailabilityConfigToMatchRegistryReturnType(registryReturnFormat);

        String[] urnParts = parseUrn(dataflowUrn);
        String agency = urnParts[0];
        String resourceId = urnParts[1];
        String version = urnParts[2];

        String path = String.format("%s/sdmx/3.0/availability/dataflow/%s/%s/%s/%s/all?mode=%s",
                BASE_PATH,
                agency,
                resourceId,
                version,
                key,
                mode
        );

        Response response = restClient.getResponseWithAccept(path, proxyFormat);

        assertThat(response.getStatusCode())
                .as("Availability endpoint should return HTTP 200 for dataflow: %s, key: %s, mode: %s", dataflowUrn, key, mode)
                .isEqualTo(200);

        String responseBody = response.getBody().asString();
        assertThat(responseBody)
                .as("Response body should not be empty")
                .isNotEmpty();
    }

    @SneakyThrows
    private void updateConfigToMatchRegistryReturnType(ReturnFormat registryReturnType) {
        Response getConfigResponse = restClient.getResponse(CONFIG_PATH);
        ProxyConfiguration proxyConfiguration = objectMapper.readValue(getConfigResponse.body().asString(), ProxyConfiguration.class);

        StructureEndpointConfiguration structureEndpointConfig =
                getVersionSpecificRegistryConfiguration(proxyConfiguration).getStructureEndpointConfig();

        int changes = 0;

        if (!structureEndpointConfig.getSupportedFormats().contains(registryReturnType)) {
            structureEndpointConfig.getSupportedFormats().add(registryReturnType);
            changes++;
        }
        if (structureEndpointConfig.getDefaultFormat() != registryReturnType) {
            structureEndpointConfig.setDefaultFormat(registryReturnType);
            changes++;
        }

        if (changes != 0) {
            restClient.postResponse(CONFIG_PATH, objectMapper.writeValueAsString(proxyConfiguration));
        }
    }

    @SneakyThrows
    private void updateDataConfigToMatchRegistryReturnType(ReturnFormat registryReturnType) {
        Response getConfigResponse = restClient.getResponse(CONFIG_PATH);
        ProxyConfiguration proxyConfiguration = objectMapper.readValue(getConfigResponse.body().asString(), ProxyConfiguration.class);

        DataEndpointConfiguration dataEndpointConfig =
                getVersionSpecificRegistryConfiguration(proxyConfiguration).getDataEndpointConfig();

        int changes = 0;

        if (!dataEndpointConfig.getSupportedFormats().contains(registryReturnType)) {
            dataEndpointConfig.getSupportedFormats().add(registryReturnType);
            changes++;
        }
        if (dataEndpointConfig.getDefaultFormat() != registryReturnType) {
            dataEndpointConfig.setDefaultFormat(registryReturnType);
            changes++;
        }

        if (changes != 0) {
            restClient.postResponse(CONFIG_PATH, objectMapper.writeValueAsString(proxyConfiguration));
        }
    }

    @SneakyThrows
    private void updateAvailabilityConfigToMatchRegistryReturnType(ReturnFormat registryReturnType) {
        Response getConfigResponse = restClient.getResponse(CONFIG_PATH);
        ProxyConfiguration proxyConfiguration = objectMapper.readValue(getConfigResponse.body().asString(), ProxyConfiguration.class);

        AvailabilityEndpointConfiguration availabilityEndpointConfig =
                getVersionSpecificRegistryConfiguration(proxyConfiguration).getAvailabilityEndpointConfig();

        int changes = 0;

        if (!availabilityEndpointConfig.getSupportedFormats().contains(registryReturnType)) {
            availabilityEndpointConfig.getSupportedFormats().add(registryReturnType);
            changes++;
        }
        if (availabilityEndpointConfig.getDefaultFormat() != registryReturnType) {
            availabilityEndpointConfig.setDefaultFormat(registryReturnType);
            changes++;
        }

        if (changes != 0) {
            restClient.postResponse(CONFIG_PATH, objectMapper.writeValueAsString(proxyConfiguration));
        }
    }

    // --- Limit diagnostics (design 014) -----------------------------------------------

    /**
     * Diagnostic: does the registry honor the SDMX 3.0 {@code limit} query parameter
     * natively (i.e. without emulation)? Soft-aborts with an actionable WARN if not --
     * the registry is a candidate for {@code supportsLimit: false}. A pass means emulation
     * is not needed for this registry.
     */
    @Test
    @DisplayName("Limit diagnostic: native `limit` honored by registry (SKIPPED if not)")
    void testLimitNativelyHonored() throws Exception {
        LimitTestSuitConfiguration cfg = testConfig.getLimitTestSuitConfiguration();
        Assumptions.assumeTrue(cfg != null,
                "No limitTestSuitConfiguration -- skipping limit diagnostics");

        ProxyConfiguration fresh = freshProxyConfig();
        setSupportsLimitOnAllVersions(fresh, true);
        restClient.postResponse(CONFIG_PATH, objectMapper.writeValueAsString(fresh));

        Response response = issueLimitRequest(cfg);
        assertThat(response.getStatusCode())
                .as("Limit request must return HTTP 200")
                .isEqualTo(200);

        int actual = countDistinctSeries(response.getBody().asString());
        if (actual > cfg.getLimit()) {
            String registryName = fresh.getConfigs().getFirst().getName();
            String msg = String.format(
                    "Registry '%s' does not honor native limit=%d (returned %d series). "
                            + "Action: set `supportsLimit: false` on its dataEndpointConfig to "
                            + "route through the proxy-side emulation (design 014).",
                    registryName, cfg.getLimit(), actual);
            log.warn(msg);
            Assumptions.abort(msg);
        }
        assertThat(actual)
                .as("Native limit is honored: returned series count must be <= limit")
                .isLessThanOrEqualTo(cfg.getLimit());
    }

    /**
     * Diagnostic: proxy-side emulation strictly caps series at the requested limit.
     * Parameterized per {@code registryReturnFormats} so a single test config exercises
     * JSON 1.0 / JSON 2.0 / CSV / XML truncators across all formats the registry can
     * emit. Hard-fails if emulation is broken for any tested format. Softly aborts when
     * the registry can't support emulation at all (no availability endpoint, or the
     * format has no truncator yet).
     */
    @ParameterizedTest(name = "registryReturnFormat: {0}")
    @DisplayName("Limit diagnostic: emulation strictly caps series at N (HARD FAIL)")
    @MethodSource("limitEmulationFormats")
    void testLimitEmulationStrict(ReturnFormat registryReturnFormat) throws Exception {
        LimitTestSuitConfiguration cfg = testConfig.getLimitTestSuitConfiguration();
        // limitEmulationFormats() already short-circuits to an empty stream when cfg is
        // null; reaching here means cfg is non-null. The parameterized source also filters
        // out formats that can't possibly run, so the check below is a tight last-line
        // guard rather than the primary skip mechanism.
        ProxyConfiguration fresh = freshProxyConfig();
        VersionSpecificRegistryConfiguration versionCfg = getVersionSpecificRegistryConfiguration(fresh);

        AvailabilityEndpointConfiguration availCfg = versionCfg.getAvailabilityEndpointConfig();
        Assumptions.assumeTrue(availCfg != null && availCfg.isAvailabilityEnabled(),
                "Registry has no enabled availabilityEndpointConfig -- emulation cannot run. "
                        + "Action: add availability endpoint before enabling `supportsLimit: false`.");

        Assumptions.assumeTrue(
                TRUNCATOR_SUPPORTED_FORMATS.contains(registryReturnFormat),
                "registryReturnFormat " + registryReturnFormat + " is not in the "
                        + "truncator-supported set " + TRUNCATOR_SUPPORTED_FORMATS
                        + " -- add a SeriesLimitTruncator for this format.");

        forceDataReturnFormat(versionCfg, registryReturnFormat);
        setSupportsLimitOnAllVersions(fresh, false);
        restClient.postResponse(CONFIG_PATH, objectMapper.writeValueAsString(fresh));

        Response response = issueLimitRequest(cfg);
        assertThat(response.getStatusCode())
                .as("Emulation path must return HTTP 200 (registryReturnFormat=%s)", registryReturnFormat)
                .isEqualTo(200);

        int actual = countDistinctSeries(response.getBody().asString());
        assertThat(actual)
                .as("Limit emulation MUST cap series count at limit=%d; got %d (registryReturnFormat=%s)",
                        cfg.getLimit(), actual, registryReturnFormat)
                .isLessThanOrEqualTo(cfg.getLimit());
    }

    /**
     * Yields the set of {@link ReturnFormat}s to parameterize the emulation test over.
     * If {@code limitTestSuitConfiguration} is absent or its {@code registryReturnFormats}
     * is empty, yields a single entry (the registry's own {@code defaultFormat}) or
     * nothing (the outer test then skips via assumption).
     */
    @SuppressWarnings("unused")
    Stream<ReturnFormat> limitEmulationFormats() {
        LimitTestSuitConfiguration cfg = testConfig.getLimitTestSuitConfiguration();
        if (cfg == null) {
            return Stream.empty();
        }
        List<ReturnFormat> formats = cfg.getRegistryReturnFormats();
        if (formats != null && !formats.isEmpty()) {
            return formats.stream();
        }
        DataEndpointConfiguration dataCfg =
                getVersionSpecificRegistryConfiguration(proxyConfig).getDataEndpointConfig();
        if (dataCfg != null && dataCfg.getDefaultFormat() != null) {
            return Stream.of(dataCfg.getDefaultFormat());
        }
        return Stream.empty();
    }

    /**
     * Forces {@code dataEndpointConfig.defaultFormat} to {@code format} and ensures it's
     * in {@code supportedFormats}. Needed so the proxy asks the registry for exactly this
     * format regardless of what the baseline config had -- parameterized per-format tests
     * must be able to exercise any truncator even if the registry's committed default is
     * one specific format.
     */
    private static void forceDataReturnFormat(VersionSpecificRegistryConfiguration versionCfg, ReturnFormat format) {
        DataEndpointConfiguration data = versionCfg.getDataEndpointConfig();
        if (data == null) {
            return;
        }
        data.setDefaultFormat(format);
        List<ReturnFormat> supported = data.getSupportedFormats() == null
                ? new ArrayList<>()
                : new ArrayList<>(data.getSupportedFormats());
        if (!supported.contains(format)) {
            supported.add(format);
        }
        data.setSupportedFormats(supported);
    }

    @SneakyThrows
    private ProxyConfiguration freshProxyConfig() {
        return objectMapper.readValue(objectMapper.writeValueAsString(proxyConfig), ProxyConfiguration.class);
    }

    private static void setSupportsLimitOnAllVersions(ProxyConfiguration cfg, boolean value) {
        for (var registry : cfg.getConfigs()) {
            if (registry.getVersions() == null) {
                continue;
            }
            registry.getVersions().values().forEach(v -> {
                DataEndpointConfiguration data = v.getDataEndpointConfig();
                if (data != null) {
                    data.setSupportsLimit(value);
                }
            });
        }
    }

    private Response issueLimitRequest(LimitTestSuitConfiguration cfg) {
        String[] urnParts = parseUrn(cfg.getUrn());
        String path = String.format("%s/sdmx/3.0/data/dataflow/%s/%s/%s/%s",
                BASE_PATH,
                urnParts[0], urnParts[1], urnParts[2],
                cfg.getKey() == null ? "*" : cfg.getKey());

        Map<String, Object> queryParams = new java.util.HashMap<>();
        queryParams.put("limit", cfg.getLimit());
        queryParams.put("attributes", "all");
        queryParams.put("dimensionAtObservation", "TIME_PERIOD");
        if (cfg.getFirstNObservations() != null) {
            queryParams.put("firstNObservations", cfg.getFirstNObservations());
        }
        if (cfg.getFilters() != null) {
            for (Map.Entry<String, String> e : cfg.getFilters().entrySet()) {
                queryParams.put("c[" + e.getKey() + "]", e.getValue());
            }
        }
        String acceptHeader = cfg.getMediaType() != null
                ? cfg.getMediaType()
                : "application/vnd.sdmx.data+json;version=2.0.0";
        return restClient.getResponseWithAccept(path, acceptHeader, queryParams);
    }

    /**
     * Counts series across all {@code data.dataSets[*].series} entries. Handles both
     * SDMX-JSON 1.0 (series is a map keyed by series-key string) and SDMX-JSON 2.0
     * (series is an array of objects). Assumes the proxy returned JSON; if the client
     * requested CSV/XML the content-type would differ and this would need a per-format
     * parser -- but e2e limit tests always request JSON as the outbound client format.
     */
    private int countDistinctSeries(String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode dataSets = root.path("data").path("dataSets");
        if (!dataSets.isArray()) {
            return 0;
        }
        int total = 0;
        for (JsonNode ds : dataSets) {
            JsonNode series = ds.path("series");
            if (series.isObject()) {
                total += series.size();
            } else if (series.isArray()) {
                total += series.size();
            }
        }
        return total;
    }
}
