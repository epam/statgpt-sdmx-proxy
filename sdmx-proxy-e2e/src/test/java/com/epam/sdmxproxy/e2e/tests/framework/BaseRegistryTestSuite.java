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
import com.epam.sdmxproxy.e2e.support.util.ProxyConfigPusher;
import com.epam.sdmxproxy.e2e.support.util.RestClient;
import com.epam.sdmxproxy.e2e.tests.framework.config.DataflowKeyCase;
import com.epam.sdmxproxy.e2e.tests.framework.config.DsdFidelityTestSuitConfiguration;
import com.epam.sdmxproxy.e2e.tests.framework.config.LimitTestSuitConfiguration;
import com.epam.sdmxproxy.e2e.tests.framework.config.MetadataPreservationTestSuitConfiguration;
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
import java.util.HashSet;
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

        ProxyConfigPusher.push(restClient, CONFIG_PATH, objectMapper.writeValueAsString(proxyConfig));
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

        if ("hierarchy".equalsIgnoreCase(artefact.getType())) {
            // SDMX-JSON 2.0 emits 3.0 Hierarchy under "hierarchies"; SDMX-ML 2.1 downgrades it into
            // <str:HierarchicalCodelist>. Neither plural form contains the bare "hierarchy" substring,
            // so accept either marker.
            assertThat(responseBody.toLowerCase())
                    .as("Response should carry the SDMX-JSON or SDMX-ML 2.1 hierarchy marker")
                    .containsAnyOf("hierarchies", "hierarchicalcodelist");
        } else {
            assertThat(responseBody)
                    .as("Response should contain %s element", artefact.getType())
                    .containsIgnoringCase(artefact.getType());
        }
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

    /**
     * Conditional pin for SDMX-JSON 2.0 dimension-group attribute round-trip
     * (issue #83 / design 030). Runs the data endpoint with {@code attributes=all}
     * and {@code Accept: application/vnd.sdmx.data+json;version=2.0.0}, then for
     * every dataflow whose response carries {@code data.structures[0].attributes.dimensionGroup}
     * asserts:
     *
     * <ul>
     *   <li>{@code attributes.dimensionGroup} is non-empty (registries that
     *       expose group-level attribute definitions must not have them
     *       silently dropped).</li>
     *   <li>None of the {@code attributes.dimensionGroup} attribute IDs leak
     *       into {@code attributes.series} (the pre-fix folding behaviour).</li>
     *   <li>If the response carries {@code data.dataSets[0].dimensionGroupAttributes},
     *       it is a non-empty object.</li>
     * </ul>
     *
     * Dataflows whose DSD has no group attributes return an empty
     * {@code attributes.dimensionGroup} (or omit it) — the test softly skips
     * those via {@code Assumptions.assumeTrue}.
     */
    @ParameterizedTest(name = "dataflow: {0}, key: {1}, registryReturnFormat: {2}")
    @DisplayName("Data Endpoint preserves dimensionGroupAttributes (SDMX-JSON 2.0)")
    @MethodSource("dataCases")
    @SneakyThrows
    void testDataDimensionGroupAttributesPreserved(String dataflowUrn, String key, ReturnFormat registryReturnFormat, String proxyFormat) {
        Assumptions.assumeTrue(
                "application/vnd.sdmx.data+json;version=2.0.0".equals(proxyFormat),
                "dimension-group assertion runs only for SDMX-JSON 2.0 output"
        );
        updateDataConfigToMatchRegistryReturnType(registryReturnFormat);

        String[] urnParts = parseUrn(dataflowUrn);
        String path = String.format("%s/sdmx/3.0/data/dataflow/%s/%s/%s/%s?attributes=all",
                BASE_PATH, urnParts[0], urnParts[1], urnParts[2], key);

        Response response = restClient.getResponseWithAccept(path, proxyFormat);
        assertThat(response.getStatusCode())
                .as("Data endpoint should return HTTP 200")
                .isEqualTo(200);

        JsonNode root = objectMapper.readTree(response.getBody().asString());
        JsonNode structure = root.path("data").path("structures").get(0);
        Assumptions.assumeTrue(structure != null && !structure.isMissingNode(),
                "Response has no structure sidecar");
        JsonNode attrs = structure.path("attributes");
        JsonNode dimensionGroup = attrs.path("dimensionGroup");

        Assumptions.assumeTrue(dimensionGroup.isArray() && !dimensionGroup.isEmpty(),
                "DSD has no dimension-group attributes for this dataflow");

        List<String> dimensionGroupAttrIds = new ArrayList<>();
        for (JsonNode attr : dimensionGroup) {
            dimensionGroupAttrIds.add(attr.path("id").asText());
        }
        assertThat(dimensionGroupAttrIds)
                .as("attributes.dimensionGroup must be populated when the DSD declares group attrs")
                .isNotEmpty();

        JsonNode series = attrs.path("series");
        if (series.isArray()) {
            for (JsonNode attr : series) {
                String id = attr.path("id").asText();
                assertThat(dimensionGroupAttrIds)
                        .as("Group-attached attribute %s must not leak into attributes.series", id)
                        .doesNotContain(id);
            }
        }

        JsonNode dga = root.path("data").path("dataSets").get(0).path("dimensionGroupAttributes");
        if (!dga.isMissingNode() && !dga.isNull()) {
            assertThat(dga.isObject())
                    .as("dimensionGroupAttributes must be an object when present")
                    .isTrue();
            assertThat(dga.size())
                    .as("dimensionGroupAttributes must be non-empty when present")
                    .isGreaterThan(0);
        }
    }

    /**
     * Pin for the PRESERVE_METADATA_ATTRIBUTES data fixture (design 031): the proxy must
     * surface MSD-derived metadata attribute usages exactly when the client opts in via
     * {@code attributes=all}, and must keep them out of the default response.
     * <p>
     * Gated by {@code metadataPreservationTestSuitConfiguration} in the registry test
     * config -- registries that have no MSD usages, or whose data fixture is not yet
     * configured, omit the block and the test softly aborts. When the block is present,
     * the assertions are hard:
     * <ul>
     *   <li>{@code attributes=all} attribute IDs are a superset of the default-response
     *       IDs (no metadata attribute IDs leak when {@code attributes} is absent),</li>
     *   <li>the {@code attributes=all} response carries at least one attribute ID not in
     *       the default response (proves the fixture is surfacing something, not no-op),</li>
     *   <li>at least one of those metadata-only attributes carries a non-null value
     *       somewhere in {@code dataSets[0]} (the fixture's value-injection path runs,
     *       not just the structure-side definitions).</li>
     * </ul>
     */
    @Test
    @DisplayName("Data Endpoint: MSD-derived metadata attributes preserved only with attributes=all")
    @SneakyThrows
    void testDataMetadataAttributesPreservation() {
        MetadataPreservationTestSuitConfiguration cfg = testConfig.getMetadataPreservationTestSuitConfiguration();
        Assumptions.assumeTrue(cfg != null,
                "No metadataPreservationTestSuitConfiguration -- skipping metadata-attribute preservation pin");

        String[] urnParts = parseUrn(cfg.getDataflowUrn());
        String basePath = String.format("%s/sdmx/3.0/data/dataflow/%s/%s/%s/%s",
                BASE_PATH, urnParts[0], urnParts[1], urnParts[2], cfg.getKey() == null ? "*" : cfg.getKey());
        String accept = cfg.getMediaType() != null ? cfg.getMediaType() : "application/vnd.sdmx.data+json;version=2.0.0";

        Response noAttrsResp = restClient.getResponseWithAccept(basePath, accept);
        assertThat(noAttrsResp.getStatusCode())
                .as("Data endpoint must return HTTP 200 without attributes param (dataflow=%s, key=%s)", cfg.getDataflowUrn(), cfg.getKey())
                .isEqualTo(200);

        Response allAttrsResp = restClient.getResponseWithAccept(basePath, accept, Map.of("attributes", "all"));
        assertThat(allAttrsResp.getStatusCode())
                .as("Data endpoint must return HTTP 200 with attributes=all (dataflow=%s, key=%s)", cfg.getDataflowUrn(), cfg.getKey())
                .isEqualTo(200);

        JsonNode allAttrsRoot = objectMapper.readTree(allAttrsResp.getBody().asByteArray());
        Set<String> noAttrsIds = collectDataAttributeIds(objectMapper.readTree(noAttrsResp.getBody().asByteArray()));
        Set<String> allAttrsIds = collectDataAttributeIds(allAttrsRoot);

        assertThat(allAttrsIds)
                .as("attributes=all attribute IDs must be a superset of the default-attrs IDs -- a metadata attribute leaking into the default response would break this")
                .containsAll(noAttrsIds);

        Set<String> metadataOnlyIds = new HashSet<>(allAttrsIds);
        metadataOnlyIds.removeAll(noAttrsIds);
        assertThat(metadataOnlyIds)
                .as("attributes=all must surface MSD-derived metadata attribute usages absent from the default response. "
                        + "If the configured dataflow has no MSD usages, point metadataPreservationTestSuitConfiguration at one that does -- or drop the config block to skip this pin.")
                .isNotEmpty();

        JsonNode allStructAttrs = allAttrsRoot.path("data").path("structures").get(0).path("attributes");
        JsonNode allDataset = allAttrsRoot.path("data").path("dataSets").get(0);
        assertThat(hasPopulatedMetadataAttrValue(allDataset, allStructAttrs, metadataOnlyIds))
                .as("At least one MSD-derived metadata attribute must carry a non-null value in the attributes=all response. "
                        + "Empty values across the board would mean the fixture's value-injection path is broken.")
                .isTrue();
    }

    private static Set<String> collectDataAttributeIds(JsonNode dataResponseRoot) {
        Set<String> ids = new HashSet<>();
        JsonNode attrs = dataResponseRoot.path("data").path("structures").get(0).path("attributes");
        for (String bucket : List.of("dataSet", "dimensionGroup", "series", "observation")) {
            JsonNode arr = attrs.path(bucket);
            if (!arr.isArray()) {
                continue;
            }
            for (JsonNode a : arr) {
                String id = a.path("id").asText(null);
                if (id != null) {
                    ids.add(id);
                }
            }
        }
        return ids;
    }

    /**
     * Scans {@code dataSets[0]} for any non-null attribute value whose definition ID is in
     * {@code metadataOnlyIds}. Covers dataset-level ({@code attributes}), dimension-group-level
     * ({@code dimensionGroupAttributes}), and series-level ({@code series[k].attributes}); the
     * observation bucket is intentionally skipped here because its value layout depends on the
     * {@code dimensionAtObservation} the proxy chose, and the dataset/dim-group/series buckets
     * are sufficient evidence that the fixture's value-injection path is running.
     */
    private static boolean hasPopulatedMetadataAttrValue(JsonNode datasetNode, JsonNode structAttrs, Set<String> metadataOnlyIds) {
        if (anyPopulatedAtBucket(datasetNode.path("attributes"), structAttrs.path("dataSet"), metadataOnlyIds)) {
            return true;
        }
        JsonNode dga = datasetNode.path("dimensionGroupAttributes");
        if (dga.isObject()) {
            for (Iterator<JsonNode> it = dga.elements(); it.hasNext(); ) {
                if (anyPopulatedAtBucket(it.next(), structAttrs.path("dimensionGroup"), metadataOnlyIds)) {
                    return true;
                }
            }
        }
        JsonNode series = datasetNode.path("series");
        if (series.isObject()) {
            for (Iterator<JsonNode> it = series.elements(); it.hasNext(); ) {
                if (anyPopulatedAtBucket(it.next().path("attributes"), structAttrs.path("series"), metadataOnlyIds)) {
                    return true;
                }
            }
        } else if (series.isArray()) {
            for (JsonNode s : series) {
                if (anyPopulatedAtBucket(s.path("attributes"), structAttrs.path("series"), metadataOnlyIds)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean anyPopulatedAtBucket(JsonNode valueArr, JsonNode defsArr, Set<String> targetIds) {
        if (!valueArr.isArray() || !defsArr.isArray()) {
            return false;
        }
        int n = Math.min(valueArr.size(), defsArr.size());
        for (int i = 0; i < n; i++) {
            String id = defsArr.get(i).path("id").asText(null);
            if (id == null || !targetIds.contains(id)) {
                continue;
            }
            JsonNode v = valueArr.get(i);
            if (v != null && !v.isNull() && !v.isMissingNode()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Pin for the structure-side DSD round-trip (designs 022, 027): the proxy must
     * preserve the moving parts of a DSD response that sdmx-core's bean model loses
     * or that registry-specific fixtures rescue. Gated by {@code dsdFidelityTestSuitConfiguration}
     * in the registry test config -- absent block skips the pin entirely; sub-assertions
     * inside the test are individually opt-in via the populated fields.
     *
     * <p>Originally landed as the IMF-specific {@code testDsdConversionFidelity_issue79}
     * regression for issue #79 (see #81); generalised here so any registry that wants
     * to lock in the same invariants populates its sub-config and inherits the test.
     */
    @Test
    @DisplayName("Structure Endpoint: DSD round-trip preserves metadata URN, conceptRoles, annotation text, and metadataAttributeUsages")
    @SneakyThrows
    void testDsdConversionFidelity() {
        DsdFidelityTestSuitConfiguration cfg = testConfig.getDsdFidelityTestSuitConfiguration();
        Assumptions.assumeTrue(cfg != null,
                "No dsdFidelityTestSuitConfiguration -- skipping DSD round-trip fidelity pin");

        String[] urnParts = parseUrn(cfg.getDsdUrn());
        String path = String.format("%s/sdmx/3.0/structure/datastructure/%s/%s/%s?references=none&detail=full",
                BASE_PATH, urnParts[0], urnParts[1], urnParts[2]);
        String accept = cfg.getMediaType() != null ? cfg.getMediaType() : "application/vnd.sdmx.structure+json;version=2.0.0";

        Response response = restClient.getResponseWithAccept(path, accept);
        assertThat(response.getStatusCode())
                .as("DSD structure request must return HTTP 200 (dsd=%s)", cfg.getDsdUrn())
                .isEqualTo(200);

        JsonNode root = objectMapper.readTree(response.getBody().asByteArray());
        JsonNode dsd = root.path("data").path("dataStructures").get(0);
        assertThat(dsd).as("dataStructures[0] must be present in response for %s", cfg.getDsdUrn()).isNotNull();
        assertThat(dsd.path("id").asText())
                .as("DSD response must echo the requested DSD id")
                .isEqualTo(urnParts[1]);
        assertThat(dsd.path("agencyID").asText())
                .as("DSD response must echo the requested agency id")
                .isEqualTo(urnParts[0]);

        if (cfg.getExpectedMetadataUrnContains() != null && !cfg.getExpectedMetadataUrnContains().isBlank()) {
            String metadataUrn = dsd.path("metadata").asText();
            assertThat(metadataUrn)
                    .as("DSD `metadata` URN must contain %s (Stage 1 mapper fix preserves the MSD reference)", cfg.getExpectedMetadataUrnContains())
                    .contains(cfg.getExpectedMetadataUrnContains());
        }

        Map<String, String> dimRoles = cfg.getDimensionConceptRoleSuffixes();
        if (dimRoles != null && !dimRoles.isEmpty()) {
            JsonNode dimensions = dsd.path("dataStructureComponents").path("dimensionList").path("dimensions");
            assertThat(dimensions.isArray()).as("dimensions must be an array").isTrue();
            for (Map.Entry<String, String> e : dimRoles.entrySet()) {
                JsonNode dim = null;
                for (JsonNode d : dimensions) {
                    if (e.getKey().equals(d.path("id").asText())) {
                        dim = d;
                        break;
                    }
                }
                assertThat(dim).as("Dimension %s must be present in DSD %s", e.getKey(), cfg.getDsdUrn()).isNotNull();
                JsonNode roles = dim.path("conceptRoles");
                assertThat(roles.isArray() && !roles.isEmpty())
                        .as("Dimension %s must carry a non-empty conceptRoles array, got: %s", e.getKey(), roles)
                        .isTrue();
                assertThat(roles.get(0).asText())
                        .as("Dimension %s conceptRoles[0] must end with %s", e.getKey(), e.getValue())
                        .endsWith(e.getValue());
            }
        }

        List<String> annotationIds = cfg.getExpectedAnnotationsWithText();
        if (annotationIds != null && !annotationIds.isEmpty()) {
            JsonNode annotations = dsd.path("annotations");
            assertThat(annotations.isArray() && !annotations.isEmpty())
                    .as("DSD annotations must be non-empty when expectedAnnotationsWithText is configured")
                    .isTrue();
            for (String annId : annotationIds) {
                JsonNode found = null;
                for (JsonNode a : annotations) {
                    if (annId.equals(a.path("id").asText())) {
                        found = a;
                        break;
                    }
                }
                assertThat(found).as("DSD annotation `%s` must be present in %s", annId, cfg.getDsdUrn()).isNotNull();
                assertThat(found.path("text").asText())
                        .as("DSD annotation `%s` text must be non-empty (ANNOTATION_VALUE_TO_TEXT fixture rescues the original `value`)", annId)
                        .isNotEmpty();
            }
        }

        if (cfg.isExpectNonEmptyMetadataAttributeUsages()) {
            JsonNode usages = dsd.path("dataStructureComponents").path("attributeList").path("metadataAttributeUsages");
            assertThat(usages.isArray() && !usages.isEmpty())
                    .as("metadataAttributeUsages must be a non-empty array (PRESERVE_METADATA_ATTRIBUTE_USAGES fixture restores the MSD usage list)")
                    .isTrue();
        }
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
