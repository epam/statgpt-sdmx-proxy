package com.epam.sdmxproxy.services.cache;

import com.epam.sdmxproxy.common.data.TranslatedDataQuery;
import com.epam.sdmxproxy.configuration.data.DataEndpointConfiguration;
import com.epam.sdmxproxy.configuration.data.RegistryConfiguration;
import com.epam.sdmxproxy.configuration.data.VersionSpecificRegistryConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

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
