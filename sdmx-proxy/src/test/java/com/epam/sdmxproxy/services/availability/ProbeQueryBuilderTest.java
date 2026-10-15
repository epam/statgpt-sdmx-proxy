package com.epam.sdmxproxy.services.availability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;

import com.epam.sdmxproxy.common.data.TranslatedAvailabilityQuery;
import com.epam.sdmxproxy.common.data.TranslatedDataQuery;
import com.epam.sdmxproxy.configuration.data.AvailabilityEndpointConfiguration;
import com.epam.sdmxproxy.configuration.data.DataEndpointConfiguration;
import com.epam.sdmxproxy.configuration.data.RegistryConfiguration;
import com.epam.sdmxproxy.configuration.data.SdmxFormat;
import com.epam.sdmxproxy.configuration.data.SdmxVersion;
import com.epam.sdmxproxy.configuration.data.VersionSpecificRegistryConfiguration;
import com.epam.sdmxproxy.configuration.data.availability.AvailabilityEmulationConfiguration;
import com.epam.sdmxproxy.configuration.data.availability.AvailabilityEmulationType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

class ProbeQueryBuilderTest {

    private final ProbeQueryBuilder builder = new ProbeQueryBuilder();

    @Test
    @DisplayName("the client's key and filters reach the probe unchanged")
    void keyAndFiltersAreCarriedThrough() {
        // The whole point of emulating availability with a data query: the probe must ask the
        // same question the client did. A probe that widened the narrowing would answer with the
        // full cube dressed up as a narrowed one.
        MultiValueMap<String, String> filters = new LinkedMultiValueMap<>();
        filters.add("geo", "EL");
        filters.add("na_item", "B1GQ");

        TranslatedAvailabilityQuery query = query(SdmxVersion.SDMX_2_1, "A...EL", filters);

        TranslatedDataQuery probe = builder.build(query);

        assertEquals("A...EL", probe.getKey());
        assertSame(filters, probe.getFilters());
        assertEquals("ESTAT", probe.getAgencyID());
        assertEquals("NAMA_10_GDP", probe.getResourceID());
        assertEquals("1.0", probe.getVersion());
    }

    @Test
    @DisplayName("SDMX 2.1 asks for series keys with detail, and never with attributes/measures")
    void sdmx21UsesDetail() {
        TranslatedDataQuery probe = builder.build(query(SdmxVersion.SDMX_2_1, "*", null));

        assertEquals("serieskeysonly", probe.getDetail());
        assertNull(probe.getAttributes(), "attributes does not exist in SDMX-REST 1.5.0");
        assertNull(probe.getMeasures(), "measures does not exist in SDMX-REST 1.5.0");
    }

    @Test
    @DisplayName("SDMX 3.0 asks for series keys with attributes/measures, and never with detail")
    void sdmx30UsesAttributesAndMeasures() {
        TranslatedDataQuery probe = builder.build(query(SdmxVersion.SDMX_3_0, "*", null));

        assertEquals("none", probe.getAttributes());
        assertEquals("none", probe.getMeasures());
        assertNull(probe.getDetail(), "SDMX-REST 2.x dropped detail; sending it risks a 400");
    }

    @Test
    @DisplayName("the probe never sets limit, firstNObservations or lastNObservations")
    void noObservationTrimmingParameters() {
        // lastNObservations=1 was the original idea for this feature and is actively harmful:
        // Eurostat sizes a request from the requested cube before applying it, so on a broad key
        // it both collapses the time coverage and trips the 413 extraction-size gate.
        for (SdmxVersion sdmxVersion : SdmxVersion.values()) {
            TranslatedDataQuery probe = builder.build(query(sdmxVersion, "*", null));

            assertNull(probe.getLimit(), sdmxVersion + ": limit must not be set");
            assertNull(probe.getFirstNObservations(), sdmxVersion + ": firstNObservations must not be set");
            assertNull(probe.getLastNObservations(), sdmxVersion + ": lastNObservations must not be set");
        }
    }

    @Test
    @DisplayName("the probe format defaults to the cheapest CSV the data endpoint serves")
    void probeFormatPrefersCsv() {
        TranslatedAvailabilityQuery query = query(SdmxVersion.SDMX_2_1, "*", null);

        TranslatedDataQuery probe = builder.build(query);

        // CSV_DATA_1_0_0 is the only CSV in the fixture's supportedFormats; 2.0.0 would win if
        // both were present.
        assertEquals(SdmxFormat.CSV_DATA_1_0_0, probe.getReturnFormat());
    }

    @Test
    @DisplayName("a configured probe format wins over the CSV preference")
    void configuredProbeFormatWins() {
        TranslatedAvailabilityQuery query = query(SdmxVersion.SDMX_2_1, "*", null);
        emulationOf(query).setProbeFormat(SdmxFormat.XML_GENERIC_DATA_2_1);

        assertEquals(SdmxFormat.XML_GENERIC_DATA_2_1, builder.build(query).getReturnFormat());
    }

    @Test
    @DisplayName("an explicit key and filters override the query's own, for a decomposed chunk")
    void explicitNarrowingOverridesTheQuery() {
        MultiValueMap<String, String> chunkFilters = new LinkedMultiValueMap<>();
        chunkFilters.add("geo", "AT,BE,BG");

        TranslatedDataQuery probe = builder.build(
                query(SdmxVersion.SDMX_3_0, "A...EL", null), "*", chunkFilters);

        assertEquals("*", probe.getKey());
        assertSame(chunkFilters, probe.getFilters());
    }

    private static AvailabilityEmulationConfiguration emulationOf(TranslatedAvailabilityQuery query) {
        return query.getVersionConfiguration().getAvailabilityEndpointConfig().getEmulation();
    }

    private static TranslatedAvailabilityQuery query(
            SdmxVersion sdmxVersion,
            String key,
            MultiValueMap<String, String> filters
    ) {
        AvailabilityEmulationConfiguration emulation = new AvailabilityEmulationConfiguration();
        emulation.setType(AvailabilityEmulationType.DATA_QUERY);

        AvailabilityEndpointConfiguration availabilityConfig = new AvailabilityEndpointConfiguration();
        availabilityConfig.setAvailabilityEnabled(false);
        availabilityConfig.setEmulation(emulation);

        DataEndpointConfiguration dataConfig = new DataEndpointConfiguration();
        dataConfig.setSupportedFormats(List.of(SdmxFormat.XML_GENERIC_DATA_2_1, SdmxFormat.CSV_DATA_1_0_0));
        dataConfig.setDefaultFormat(SdmxFormat.XML_GENERIC_DATA_2_1);

        VersionSpecificRegistryConfiguration versionConfig = new VersionSpecificRegistryConfiguration();
        versionConfig.setSdmxVersion(sdmxVersion);
        versionConfig.setAvailabilityEndpointConfig(availabilityConfig);
        versionConfig.setDataEndpointConfig(dataConfig);

        RegistryConfiguration registryConfig = new RegistryConfiguration();
        registryConfig.setName("ESTAT");

        return TranslatedAvailabilityQuery.builder()
                .registryConfiguration(registryConfig)
                .versionConfiguration(versionConfig)
                .context("dataflow")
                .agencyID("ESTAT")
                .resourceID("NAMA_10_GDP")
                .version("1.0")
                .key(key)
                .filters(filters)
                .build();
    }
}
