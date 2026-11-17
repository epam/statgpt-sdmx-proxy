package com.epam.sdmxproxy.services.cache;

import com.epam.sdmxproxy.common.data.Structure;
import com.epam.sdmxproxy.common.data.TranslatedAvailabilityQuery;
import com.epam.sdmxproxy.common.data.TranslatedDataQuery;
import com.epam.sdmxproxy.common.data.TranslatedStructureQuery;
import com.epam.sdmxproxy.configuration.data.AvailabilityEndpointConfiguration;
import com.epam.sdmxproxy.configuration.data.DataEndpointConfiguration;
import com.epam.sdmxproxy.configuration.data.RegistryConfiguration;
import com.epam.sdmxproxy.configuration.data.SdmxFormat;
import com.epam.sdmxproxy.configuration.data.SdmxVersion;
import com.epam.sdmxproxy.configuration.data.StructureEndpointConfiguration;
import com.epam.sdmxproxy.configuration.data.VersionSpecificRegistryConfiguration;
import com.epam.sdmxproxy.configuration.data.availability.AvailabilityEmulationConfiguration;
import com.epam.sdmxproxy.configuration.data.availability.AvailabilityEmulationType;
import com.epam.sdmxproxy.services.availability.AvailabilityQueryCanonicalizer;
import com.epam.sdmxproxy.services.translator.Sdmx21QueryNormalizer;
import com.epam.sdmxproxy.services.translator.Sdmx21QueryNormalizerImpl;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CacheKeyGeneratorTest {

    @Test
    void generateLimitEmulationKey_isDeterministicForSameInputs() {
        TranslatedDataQuery q1 = baseQuery("M.*.*.*", filtersOf("FREQ", "M"), 80);
        TranslatedDataQuery q2 = baseQuery("M.*.*.*", filtersOf("FREQ", "M"), 80);

        assertThat(CacheKeyGenerator.generateLimitEmulationKey(q1))
                .isEqualTo(CacheKeyGenerator.generateLimitEmulationKey(q2));
    }

    @Test
    void generateLimitEmulationKey_isStableUnderFilterInsertionOrder() {
        MultiValueMap<String, String> a = new LinkedMultiValueMap<>();
        a.add("FREQ", "M");
        a.add("REF_AREA", "AE");
        a.add("REF_AREA", "AR");

        MultiValueMap<String, String> b = new LinkedMultiValueMap<>();
        b.add("REF_AREA", "AR");
        b.add("REF_AREA", "AE");
        b.add("FREQ", "M");

        assertThat(CacheKeyGenerator.generateLimitEmulationKey(baseQuery("*", a, 80)))
                .isEqualTo(CacheKeyGenerator.generateLimitEmulationKey(baseQuery("*", b, 80)));
    }

    @Test
    void generateLimitEmulationKey_changesWhenLimitChanges() {
        TranslatedDataQuery q80 = baseQuery("*", filtersOf("FREQ", "M"), 80);
        TranslatedDataQuery q160 = baseQuery("*", filtersOf("FREQ", "M"), 160);

        assertThat(CacheKeyGenerator.generateLimitEmulationKey(q80))
                .isNotEqualTo(CacheKeyGenerator.generateLimitEmulationKey(q160));
    }

    @Test
    void generateLimitEmulationKey_changesWhenFiltersChange() {
        TranslatedDataQuery q1 = baseQuery("*", filtersOf("FREQ", "M"), 80);
        TranslatedDataQuery q2 = baseQuery("*", filtersOf("FREQ", "D"), 80);

        assertThat(CacheKeyGenerator.generateLimitEmulationKey(q1))
                .isNotEqualTo(CacheKeyGenerator.generateLimitEmulationKey(q2));
    }

    @Test
    void generateLimitEmulationKey_changesWhenKeyChanges() {
        TranslatedDataQuery q1 = baseQuery("M.*.*.*", new LinkedMultiValueMap<>(), 80);
        TranslatedDataQuery q2 = baseQuery("D.*.*.*", new LinkedMultiValueMap<>(), 80);

        assertThat(CacheKeyGenerator.generateLimitEmulationKey(q1))
                .isNotEqualTo(CacheKeyGenerator.generateLimitEmulationKey(q2));
    }

    @Test
    void generateLimitEmulationKey_changesWhenToleranceChanges() {
        TranslatedDataQuery q1 = baseQuery("*", filtersOf("FREQ", "M"), 80);
        DataEndpointConfiguration cfg = q1.getVersionConfiguration().getDataEndpointConfig();
        cfg.setLimitEmulationTolerance(1.5);

        TranslatedDataQuery q2 = baseQuery("*", filtersOf("FREQ", "M"), 80);
        // q2 keeps default tolerance 1.2

        assertThat(CacheKeyGenerator.generateLimitEmulationKey(q1))
                .isNotEqualTo(CacheKeyGenerator.generateLimitEmulationKey(q2));
    }

    @Test
    void generateLimitEmulationKey_includesDatasetIdentity() {
        TranslatedDataQuery q1 = baseQuery("*", new LinkedMultiValueMap<>(), 80);
        TranslatedDataQuery q2 = q1.toBuilder().resourceID("DIFFERENT_DATAFLOW").build();

        assertThat(CacheKeyGenerator.generateLimitEmulationKey(q1))
                .isNotEqualTo(CacheKeyGenerator.generateLimitEmulationKey(q2));
    }

    @Test
    void generateLimitEmulationKey_keyShapeMatchesExpectedPrefix() {
        String key = CacheKeyGenerator.generateLimitEmulationKey(
                baseQuery("*", new LinkedMultiValueMap<>(), 80));
        assertThat(key).startsWith("limit_emu:TEST:AGY:RES:1.0:");
    }

    @Test
    void generateFanOutResponseKey_isStableForSameInputs() {
        String k1 = CacheKeyGenerator.generateFanOutResponseKey("dataflow", "*", "*", null, "full", MediaType.APPLICATION_JSON, 42);
        String k2 = CacheKeyGenerator.generateFanOutResponseKey("dataflow", "*", "*", null, "full", MediaType.APPLICATION_JSON, 42);
        assertThat(k1).isEqualTo(k2);
    }

    @Test
    void generateFanOutResponseKey_differsByStructureType() {
        String k1 = CacheKeyGenerator.generateFanOutResponseKey("dataflow", "*", "*", null, "full", MediaType.APPLICATION_JSON, 42);
        String k2 = CacheKeyGenerator.generateFanOutResponseKey("codelist", "*", "*", null, "full", MediaType.APPLICATION_JSON, 42);
        assertThat(k1).isNotEqualTo(k2);
    }

    @Test
    void generateFanOutResponseKey_differsByMediaType() {
        String k1 = CacheKeyGenerator.generateFanOutResponseKey("dataflow", "*", "*", null, "full", MediaType.APPLICATION_JSON, 42);
        String k2 = CacheKeyGenerator.generateFanOutResponseKey("dataflow", "*", "*", null, "full", MediaType.APPLICATION_XML, 42);
        assertThat(k1).isNotEqualTo(k2);
    }

    @Test
    void generateFanOutResponseKey_differsByConfigsHash() {
        String k1 = CacheKeyGenerator.generateFanOutResponseKey("dataflow", "*", "*", null, "full", MediaType.APPLICATION_JSON, 42);
        String k2 = CacheKeyGenerator.generateFanOutResponseKey("dataflow", "*", "*", null, "full", MediaType.APPLICATION_JSON, 4242);
        assertThat(k1).isNotEqualTo(k2);
    }

    @Test
    void generateFanOutResponseKey_differsByResourceVersionRefDetail() {
        String base = CacheKeyGenerator.generateFanOutResponseKey("dataflow", "*", "*", null, "full", MediaType.APPLICATION_JSON, 42);
        assertThat(CacheKeyGenerator.generateFanOutResponseKey("dataflow", "ABC", "*", null, "full", MediaType.APPLICATION_JSON, 42)).isNotEqualTo(base);
        assertThat(CacheKeyGenerator.generateFanOutResponseKey("dataflow", "*", "1.0", null, "full", MediaType.APPLICATION_JSON, 42)).isNotEqualTo(base);
        assertThat(CacheKeyGenerator.generateFanOutResponseKey("dataflow", "*", "*", "all", "full", MediaType.APPLICATION_JSON, 42)).isNotEqualTo(base);
        assertThat(CacheKeyGenerator.generateFanOutResponseKey("dataflow", "*", "*", null, "allstubs", MediaType.APPLICATION_JSON, 42)).isNotEqualTo(base);
    }

    @Test
    void generateFanOutResponseKey_handlesNullOptionalFields() {
        // Nulls in optional positions become empty segments, not "null" strings.
        String key = CacheKeyGenerator.generateFanOutResponseKey("dataflow", "*", "*", null, null, MediaType.APPLICATION_JSON, 0);
        assertThat(key).startsWith("response:structure:fanout:dataflow:*:*:::");
    }

    @Test
    void generateFanOutResponseKey_keyShapeStartsWithFanOutPrefix() {
        String key = CacheKeyGenerator.generateFanOutResponseKey("codelist", "*", "*", "all", "full", MediaType.APPLICATION_JSON, 42);
        assertThat(key).startsWith("response:structure:fanout:codelist:*:*:all:full:");
        // Distinguishable from single-registry response keys (which would start with "response:structure:{type}:{registryName}:...").
        assertThat(key).doesNotStartWith("response:structure:codelist:");
    }

    private static MultiValueMap<String, String> filtersOf(String dim, String value) {
        LinkedMultiValueMap<String, String> filters = new LinkedMultiValueMap<>();
        filters.add(dim, value);
        return filters;
    }

    @Test
    void generateAvailabilityEmulationKey_collapsesEveryUnfilteredShapeToOneEntry() {
        // The consumer issues the unfiltered availability request from two places in two shapes:
        // an absent key on the GET route, and a body of {"filters": []} on the POST route. One of
        // them bypasses its own cache entirely, so without collapsing both to a single entry the
        // most expensive request in the system runs twice. See design 040.
        AvailabilityQueryCanonicalizer canonicalizer = new AvailabilityQueryCanonicalizer();

        String absentKeyNoFilters = CacheKeyGenerator.generateAvailabilityEmulationKey(
                availabilityQuery(null, null), canonicalizer);
        String emptyFilterMap = CacheKeyGenerator.generateAvailabilityEmulationKey(
                availabilityQuery(null, new LinkedMultiValueMap<>()), canonicalizer);
        String allKeyword = CacheKeyGenerator.generateAvailabilityEmulationKey(
                availabilityQuery("all", null), canonicalizer);
        String starKey = CacheKeyGenerator.generateAvailabilityEmulationKey(
                availabilityQuery("*", null), canonicalizer);
        String allWildcardPositionalKey = CacheKeyGenerator.generateAvailabilityEmulationKey(
                availabilityQuery("*.*.*.*", new LinkedMultiValueMap<>()), canonicalizer);

        assertThat(List.of(absentKeyNoFilters, emptyFilterMap, allKeyword, starKey, allWildcardPositionalKey))
                .containsOnly(absentKeyNoFilters);
    }

    @Test
    void generateAvailabilityEmulationKey_separatesNarrowedRequests() {
        AvailabilityQueryCanonicalizer canonicalizer = new AvailabilityQueryCanonicalizer();

        String unfiltered = CacheKeyGenerator.generateAvailabilityEmulationKey(
                availabilityQuery("*", null), canonicalizer);
        String narrowedKey = CacheKeyGenerator.generateAvailabilityEmulationKey(
                availabilityQuery("A...EL", null), canonicalizer);
        String narrowedFilter = CacheKeyGenerator.generateAvailabilityEmulationKey(
                availabilityQuery("*", filtersOf("geo", "EL")), canonicalizer);
        String otherFilterValue = CacheKeyGenerator.generateAvailabilityEmulationKey(
                availabilityQuery("*", filtersOf("geo", "AT")), canonicalizer);

        assertThat(List.of(unfiltered, narrowedKey, narrowedFilter, otherFilterValue))
                .doesNotHaveDuplicates();
    }

    @Test
    void generateAvailabilityEmulationKey_separatesMediaTypesAndComponentIds() {
        AvailabilityQueryCanonicalizer canonicalizer = new AvailabilityQueryCanonicalizer();

        TranslatedAvailabilityQuery json = availabilityQuery("*", null);
        TranslatedAvailabilityQuery otherComponent = availabilityQuery("*", null).toBuilder()
                .componentId("geo")
                .build();
        TranslatedAvailabilityQuery otherMediaType = availabilityQuery("*", null).toBuilder()
                .contentType(MediaType.APPLICATION_JSON)
                .build();

        assertThat(List.of(
                CacheKeyGenerator.generateAvailabilityEmulationKey(json, canonicalizer),
                CacheKeyGenerator.generateAvailabilityEmulationKey(otherComponent, canonicalizer),
                CacheKeyGenerator.generateAvailabilityEmulationKey(otherMediaType, canonicalizer)
        )).doesNotHaveDuplicates();
    }

    private static TranslatedAvailabilityQuery availabilityQuery(String key, MultiValueMap<String, String> filters) {
        AvailabilityEmulationConfiguration emulation = new AvailabilityEmulationConfiguration();
        emulation.setType(AvailabilityEmulationType.DATA_QUERY);

        AvailabilityEndpointConfiguration availabilityConfig = new AvailabilityEndpointConfiguration();
        availabilityConfig.setEmulation(emulation);

        VersionSpecificRegistryConfiguration versionConfig = new VersionSpecificRegistryConfiguration();
        versionConfig.setAvailabilityEndpointConfig(availabilityConfig);

        RegistryConfiguration registryConfig = new RegistryConfiguration();
        registryConfig.setName("TEST");

        return TranslatedAvailabilityQuery.builder()
                .registryConfiguration(registryConfig)
                .versionConfiguration(versionConfig)
                .agencyID("AGY")
                .resourceID("RES")
                .version("1.0")
                .key(key)
                .filters(filters)
                .mode("exact")
                .contentType(MediaType.parseMediaType("application/vnd.sdmx.structure+json;version=2.0.0"))
                .build();
    }

    // --- Canonical structure key (design 042) ---
    // The real normalizer is used on purpose: these tests fail if the adapter's outbound
    // normalization and the cache key drift apart.

    private static final Sdmx21QueryNormalizer NORMALIZER = new Sdmx21QueryNormalizerImpl();

    @Test
    void generateStructureKey_sdmx21_plusAndLatestShareKey() {
        assertThat(structureKey(structureQuery(SdmxVersion.SDMX_2_1, "dataflow", "OECD", "DF", "+")))
                .isEqualTo(structureKey(structureQuery(SdmxVersion.SDMX_2_1, "dataflow", "OECD", "DF", "latest")));
    }

    @Test
    void generateStructureKey_sdmx21_wildcardsShareKey() {
        String all = structureKey(structureQuery(SdmxVersion.SDMX_2_1, "dataflow", "all", "all", "all"));
        assertThat(structureKey(structureQuery(SdmxVersion.SDMX_2_1, "dataflow", "*", "*", "*"))).isEqualTo(all);
        assertThat(structureKey(structureQuery(SdmxVersion.SDMX_2_1, "dataflow", "", "", ""))).isEqualTo(all);
        // Structure requires agency and id; only the version slot can be null.
        assertThat(structureKey(structureQuery(SdmxVersion.SDMX_2_1, "dataflow", "all", "all", null))).isEqualTo(all);
    }

    @Test
    void generateStructureKey_sdmx21_dataconstraintAndContentconstraintShareKey() {
        assertThat(structureKey(structureQuery(SdmxVersion.SDMX_2_1, "dataconstraint", "OECD", "CC", "1.0")))
                .isEqualTo(structureKey(structureQuery(SdmxVersion.SDMX_2_1, "contentconstraint", "OECD", "CC", "1.0")));
    }

    @Test
    void generateStructureKey_sdmx30_plusAndTildeDiffer() {
        assertThat(structureKey(structureQuery(SdmxVersion.SDMX_3_0, "dataflow", "BIS", "DF", "+")))
                .isNotEqualTo(structureKey(structureQuery(SdmxVersion.SDMX_3_0, "dataflow", "BIS", "DF", "~")));
    }

    @Test
    void generateStructureKey_referencesAbsentAndNoneDiffer() {
        TranslatedStructureQuery none = structureQuery(SdmxVersion.SDMX_2_1, "dataflow", "OECD", "DF", "1.0");
        TranslatedStructureQuery absent = structureQuery(SdmxVersion.SDMX_2_1, "dataflow", "OECD", "DF", "1.0");
        absent.setReferences(null);
        assertThat(structureKey(none)).isNotEqualTo(structureKey(absent));
    }

    @Test
    void generateStructureKey_agencyCasePreserved() {
        assertThat(structureKey(structureQuery(SdmxVersion.SDMX_2_1, "dataflow", "OECD", "DF", "1.0")))
                .isNotEqualTo(structureKey(structureQuery(SdmxVersion.SDMX_2_1, "dataflow", "oecd", "DF", "1.0")));
    }

    @Test
    void generateStructureKey_differsBySdmxVersion() {
        assertThat(structureKey(structureQuery(SdmxVersion.SDMX_2_1, "dataflow", "OECD", "DF", "1.0")))
                .isNotEqualTo(structureKey(structureQuery(SdmxVersion.SDMX_3_0, "dataflow", "OECD", "DF", "1.0")));
    }

    @Test
    void generateStructureKey_differsByReturnFormat() {
        TranslatedStructureQuery xml = structureQuery(SdmxVersion.SDMX_3_0, "dataflow", "BIS", "DF", "1.0");
        TranslatedStructureQuery json = structureQuery(SdmxVersion.SDMX_3_0, "dataflow", "BIS", "DF", "1.0");
        json.setRegistryReturnFormat(SdmxFormat.JSON_STRUCTURE_2_0_0);
        assertThat(structureKey(xml)).isNotEqualTo(structureKey(json));
    }

    @Test
    void generateStructureKey_differsByStructureUrl() {
        TranslatedStructureQuery a = structureQuery(SdmxVersion.SDMX_2_1, "dataflow", "OECD", "DF", "1.0");
        TranslatedStructureQuery b = structureQuery(SdmxVersion.SDMX_2_1, "dataflow", "OECD", "DF", "1.0");
        b.getVersionConfiguration().getStructureEndpointConfig().setUrl("https://other.example.com/rest");
        assertThat(structureKey(a)).isNotEqualTo(structureKey(b));
    }

    @Test
    void generateResponseKey_usesCanonicalStructureKey() {
        MediaType accept = MediaType.APPLICATION_JSON;
        assertThat(CacheKeyGenerator.generateResponseKey(structureQuery(SdmxVersion.SDMX_2_1, "dataflow", "OECD", "DF", "+"), accept, Map.of(), NORMALIZER))
                .isEqualTo(CacheKeyGenerator.generateResponseKey(structureQuery(SdmxVersion.SDMX_2_1, "dataflow", "OECD", "DF", "latest"), accept, Map.of(), NORMALIZER));
    }

    private static String structureKey(TranslatedStructureQuery query) {
        return CacheKeyGenerator.generateStructureKey(query, NORMALIZER);
    }

    private static TranslatedStructureQuery structureQuery(SdmxVersion sdmxVersion, String type, String agency, String id, String version) {
        StructureEndpointConfiguration structureEndpoint = new StructureEndpointConfiguration();
        structureEndpoint.setUrl("https://registry.example.com/rest");
        VersionSpecificRegistryConfiguration versionConfig = new VersionSpecificRegistryConfiguration();
        versionConfig.setSdmxVersion(sdmxVersion);
        versionConfig.setStructureEndpointConfig(structureEndpoint);
        RegistryConfiguration registryConfig = new RegistryConfiguration();
        registryConfig.setName("TEST");
        return TranslatedStructureQuery.builder()
                .registryConfiguration(registryConfig)
                .versionConfiguration(versionConfig)
                .structure(new Structure(type, agency, id, version))
                .references("none")
                .detail("full")
                .registryReturnFormat(SdmxFormat.XML_STRUCTURE_2_1)
                .build();
    }

    private static TranslatedDataQuery baseQuery(String key, MultiValueMap<String, String> filters, Integer limit) {
        DataEndpointConfiguration dataCfg = new DataEndpointConfiguration();
        dataCfg.setLimitEmulationTolerance(1.2);
        dataCfg.setLimitEmulationProbeBudget(8);

        VersionSpecificRegistryConfiguration versionConfig = new VersionSpecificRegistryConfiguration();
        versionConfig.setDataEndpointConfig(dataCfg);

        RegistryConfiguration registryConfig = new RegistryConfiguration();
        registryConfig.setName("TEST");

        return TranslatedDataQuery.builder()
                .registryConfiguration(registryConfig)
                .versionConfiguration(versionConfig)
                .agencyID("AGY")
                .resourceID("RES")
                .version("1.0")
                .key(key)
                .filters(filters)
                .limit(limit)
                .build();
    }
}
