package com.epam.sdmxproxy.e2e.tests.framework;

import com.epam.sdmxproxy.configuration.data.AvailabilityEndpointConfiguration;
import com.epam.sdmxproxy.configuration.data.DataEndpointConfiguration;
import com.epam.sdmxproxy.configuration.data.ProxyConfiguration;
import com.epam.sdmxproxy.configuration.data.ReturnFormat;
import com.epam.sdmxproxy.configuration.data.StructureEndpointConfiguration;
import com.epam.sdmxproxy.configuration.data.VersionSpecificRegistryConfiguration;
import com.epam.sdmxproxy.e2e.support.container.ContainerFixture;
import com.epam.sdmxproxy.e2e.support.fixtures.ResponseValidator;
import com.epam.sdmxproxy.e2e.support.logs.ContainerLogReporter;
import com.epam.sdmxproxy.e2e.support.logs.LogsGate;
import com.epam.sdmxproxy.e2e.support.sdmx.StructureQueryDetail;
import com.epam.sdmxproxy.e2e.support.sdmx.StructureReferenceDetail;
import com.epam.sdmxproxy.e2e.support.util.RestClient;
import com.epam.sdmxproxy.e2e.tests.framework.config.DataflowKeyCase;
import com.epam.sdmxproxy.e2e.tests.framework.config.RegistryTestSuitConfiguration;
import com.epam.sdmxproxy.e2e.tests.framework.config.StructureTypeAndUrn;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pavelicii.allpairs4j.AllPairs;
import io.github.pavelicii.allpairs4j.Case;
import io.github.pavelicii.allpairs4j.Parameter;
import io.restassured.response.Response;
import lombok.SneakyThrows;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
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
@ExtendWith({ContainerFixture.class, LogsGate.class, ContainerLogReporter.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("registry")
public abstract class BaseRegistryTestSuite {

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
    private List<Case> genericStructureCases;
    private List<Case> specificStructureCases;
    private List<Case> dataCases;
    private List<Case> availabilityCases;
    private static final Pattern SHORT_URN = Pattern.compile(
            "^(?<agency>[^:]+):(?<resourceId>.+)\\((?<version>[^)]+)\\)$");

    private static AllPairs.AllPairsBuilder getGenericStructureCases(ProxyConfiguration proxyConfig) {
        VersionSpecificRegistryConfiguration registryConfiguration = getVersionSpecificRegistryConfiguration(proxyConfig);

        return new AllPairs.AllPairsBuilder()
                .withParameter(new Parameter("supportedStructures", new ArrayList<>(registryConfiguration.getStructureEndpointConfig().getSupportedStructures())))
                .withParameter(new Parameter("details", List.of(StructureQueryDetail.values())))
                .withParameter(new Parameter("references", List.of(StructureReferenceDetail.values())))
                .withParameter(new Parameter("registryReturnTypes", registryConfiguration.getStructureEndpointConfig().getSupportedFormats()))
                .withParameter(new Parameter("supportedProxyFormats", STRUCTURE_SUPPORTED_PROXY_FORMATS));
    }

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

        var dataflowCases = new ArrayList<>(availConfig.getDataflowConfigs().stream()
                .flatMap(dc -> dc.getKeys().stream()
                        .map(key -> new DataflowKeyCase(dc.getUrn(), key)))
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
        restClient = new RestClient(ContainerFixture.getBaseUrl());
        responseValidator = new ResponseValidator();
        proxyConfig = getProxyConfig();
        testConfig = getTestConfig();

        genericStructureCases = getGenericStructureCases(proxyConfig).build().getGeneratedCases();
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

    Stream<Arguments> genericStructureCases() {
        return genericStructureCases.stream()
                .map(testCase -> Arguments.of(
                        testCase.get("supportedStructures"),
                        testCase.get("details"),
                        testCase.get("references"),
                        testCase.get("registryReturnTypes"),
                        testCase.get("supportedProxyFormats")
                ));
    }

    @ParameterizedTest(name = "supportedStructures: {0}, detail: {1}, references: {2}, registryReturnType: {3}, supportedProxyFormat: {4}")
    @DisplayName("Generic Structure Cases")
    @MethodSource("genericStructureCases")
    void testGenericStructureTypes(String supportedStructure, StructureQueryDetail detail, StructureReferenceDetail references, ReturnFormat registryReturnType, String supportedProxyFormat) {
        updateConfigToMatchRegistryReturnType(registryReturnType);

        String agency = "*";
        String id = "*";
        String version = "*";
        String path = String.format("%s/sdmx/3.0/structure/%s/%s/%s/%s?references=%s&detail=%s",
                BASE_PATH,
                supportedStructure,
                agency,
                id,
                version,
                references,
                detail
        );

        Response response = restClient.getResponseWithAccept(path, supportedProxyFormat);

        assertThat(response.getStatusCode())
                .as("Structure endpoint should return HTTP 200 for supported structure type: %s", supportedStructure)
                .isEqualTo(200);

        String responseBody = response.getBody().asString();
        assertThat(responseBody)
                .as("Response body should not be empty")
                .isNotEmpty();

        assertThat(responseBody)
                .as("Response should contain %s element", supportedStructure)
                .containsIgnoringCase(supportedStructure);
    }

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
}
