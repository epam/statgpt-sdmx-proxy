package com.epam.sdmxproxy.services.availability.harvest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.epam.sdmxproxy.configuration.data.SdmxFormat;
import com.epam.sdmxproxy.exception.AvailabilityEmulationException;
import com.epam.sdmxproxy.exception.ResponseTooLargeException;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;
import io.sdmx.api.sdmx.model.beans.datastructure.DataStructureBean;
import io.sdmx.api.sdmx.model.beans.datastructure.DimensionBean;
import io.sdmx.api.sdmx.model.beans.datastructure.DimensionListBean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SeriesKeyHarvesterTest {

    private static final String CSV_FIXTURE = "estat_nama_10_gdp_geo_el_serieskeysonly.csv";
    private static final String GENERIC_FIXTURE = "estat_nama_10_gdp_generic_serieskeysonly.xml";
    private static final String STRUCTURE_SPECIFIC_FIXTURE = "estat_nama_10_gdp_structurespecific_serieskeysonly.xml";
    private static final String GENERIC_WITH_ATTRIBUTES_FIXTURE = "estat_nama_10_gdp_generic_with_attributes.xml";

    private final CsvSeriesKeyHarvester csvHarvester = new CsvSeriesKeyHarvester();
    private final XmlSeriesKeyHarvester xmlHarvester = new XmlSeriesKeyHarvester();

    @Test
    @DisplayName("CSV: a real 819-series Eurostat probe harvests to its per-dimension coverage")
    void csvHarvestsRealProbe() {
        HarvestedCoverage coverage = csvHarvester.harvest(
                fixture(CSV_FIXTURE), namaBeans(), HarvestLimits.unlimited());

        assertEquals(819L, coverage.seriesCount());
        assertEquals(Set.of("freq", "unit", "na_item", "geo"), coverage.valuesByDimensionId().keySet());
        assertEquals(Set.of("A"), coverage.valuesByDimensionId().get("freq"));
        assertEquals(Set.of("EL"), coverage.valuesByDimensionId().get("geo"));
        assertEquals(32, coverage.valuesByDimensionId().get("unit").size());
        assertEquals(37, coverage.valuesByDimensionId().get("na_item").size());
    }

    @Test
    @DisplayName("CSV: coverage stays bounded by the DSD, not by the number of series")
    void csvCoverageIsBoundedByTheDsd() {
        // The property that makes emulation affordable at all: a 139 MB / 2 067 919-series probe
        // on Eurostat harvests to 327 strings. Asserted here in miniature -- 819 series over four
        // dimensions can never yield more than the sum of the observed value sets.
        HarvestedCoverage coverage = csvHarvester.harvest(
                fixture(CSV_FIXTURE), namaBeans(), HarvestLimits.unlimited());

        int totalValues = coverage.valuesByDimensionId().values().stream().mapToInt(Set::size).sum();
        assertEquals(71, totalValues);
        assertTrue(totalValues < coverage.seriesCount(),
                "coverage must be smaller than the key set it was derived from");
    }

    @Test
    @DisplayName("CSV: a dimension with no column fails loudly rather than reporting no data")
    void csvMissingDimensionColumnThrows() {
        // Reporting it as empty would tell the consumer the dimension has no data at all, which
        // takes the dataset out of every index. A response that cannot describe the cube is a
        // configuration problem.
        SdmxBeans beansWithExtraDimension = beansWithDimensions("freq", "unit", "na_item", "geo", "sex", "TIME_PERIOD");

        AvailabilityEmulationException e = assertThrows(AvailabilityEmulationException.class,
                () -> csvHarvester.harvest(fixture(CSV_FIXTURE), beansWithExtraDimension, HarvestLimits.unlimited()));
        assertTrue(e.getMessage().contains("sex"), e.getMessage());
    }

    @Test
    @DisplayName("CSV: an empty body is a broken response, not an empty result set")
    void csvEmptyBodyThrows() {
        InputStream empty = new ByteArrayInputStream(new byte[0]);
        assertThrows(AvailabilityEmulationException.class,
                () -> csvHarvester.harvest(empty, namaBeans(), HarvestLimits.unlimited()));
    }

    @Test
    @DisplayName("CSV: the row ceiling aborts instead of harvesting on")
    void csvSeriesLimitThrows() {
        assertThrows(ResponseTooLargeException.class,
                () -> csvHarvester.harvest(fixture(CSV_FIXTURE), namaBeans(), new HarvestLimits(Long.MAX_VALUE, 10L)));
    }

    @Test
    @DisplayName("CSV: the byte ceiling aborts instead of reading on")
    void csvByteLimitThrows() {
        assertThrows(ResponseTooLargeException.class,
                () -> csvHarvester.harvest(fixture(CSV_FIXTURE), namaBeans(), new HarvestLimits(1024L, Long.MAX_VALUE)));
    }

    @Test
    @DisplayName("XML: both SDMX-ML packagings harvest identically")
    void xmlPackagingsAgree() {
        HarvestedCoverage generic = xmlHarvester.harvest(
                fixture(GENERIC_FIXTURE), namaBeans(), HarvestLimits.unlimited());
        HarvestedCoverage structureSpecific = xmlHarvester.harvest(
                fixture(STRUCTURE_SPECIFIC_FIXTURE), namaBeans(), HarvestLimits.unlimited());

        assertEquals(2L, generic.seriesCount());
        assertEquals(2L, structureSpecific.seriesCount());
        assertEquals(Map.of(
                "freq", Set.of("A"),
                "unit", Set.of("CLV05_MEUR"),
                "na_item", Set.of("B1GQ"),
                "geo", Set.of("AT", "EL")
        ), generic.valuesByDimensionId());
        assertEquals(generic.valuesByDimensionId(), structureSpecific.valuesByDimensionId());
    }

    @Test
    @DisplayName("XML: series-level attributes are not folded into dimension coverage")
    void xmlIgnoresSeriesAttributes() {
        HarvestedCoverage coverage = xmlHarvester.harvest(
                fixture(GENERIC_WITH_ATTRIBUTES_FIXTURE), namaBeans(), HarvestLimits.unlimited());

        assertEquals(Set.of("AT"), coverage.valuesByDimensionId().get("geo"));
        assertFalse(coverage.valuesByDimensionId().get("geo").contains("NOT_A_DIMENSION_VALUE"),
                "an Attributes value must not become dimension coverage");
    }

    @Test
    @DisplayName("XML: the row ceiling aborts instead of harvesting on")
    void xmlSeriesLimitThrows() {
        assertThrows(ResponseTooLargeException.class,
                () -> xmlHarvester.harvest(fixture(GENERIC_FIXTURE), namaBeans(), new HarvestLimits(Long.MAX_VALUE, 1L)));
    }

    @Test
    @DisplayName("the two harvesters cover disjoint formats")
    void formatsAreDisjoint() {
        Set<SdmxFormat> csvFormats = csvHarvester.supportedFormats();
        Set<SdmxFormat> xmlFormats = xmlHarvester.supportedFormats();
        assertTrue(csvFormats.stream().noneMatch(xmlFormats::contains),
                "overlapping formats would make the provider ambiguous");
    }

    @Test
    @DisplayName("merging chunk harvests unions per dimension and sums the counts")
    void mergeUnionsCoverage() {
        HarvestedCoverage first = xmlHarvester.harvest(
                fixture(STRUCTURE_SPECIFIC_FIXTURE), namaBeans(), HarvestLimits.unlimited());
        HarvestedCoverage second = xmlHarvester.harvest(
                fixture(GENERIC_WITH_ATTRIBUTES_FIXTURE), namaBeans(), HarvestLimits.unlimited());

        HarvestedCoverage merged = first.merge(second);

        assertEquals(3L, merged.seriesCount());
        assertEquals(Set.of("AT", "EL"), merged.valuesByDimensionId().get("geo"));
    }

    private static InputStream fixture(String name) {
        String path = "/com/epam/sdmxproxy/services/availability/" + name;
        InputStream stream = SeriesKeyHarvesterTest.class.getResourceAsStream(path);
        if (stream == null) {
            throw new IllegalStateException("Missing test fixture: " + path);
        }
        return stream;
    }

    private static SdmxBeans namaBeans() {
        return beansWithDimensions("freq", "unit", "na_item", "geo", "TIME_PERIOD");
    }

    private static SdmxBeans beansWithDimensions(String... dimensionIds) {
        SdmxBeans beans = mock(SdmxBeans.class);
        DataStructureBean dsd = mock(DataStructureBean.class);
        DimensionListBean dimensionList = mock(DimensionListBean.class);
        when(beans.getDataStructures()).thenReturn(Set.of(dsd));
        when(dsd.getDimensionList()).thenReturn(dimensionList);

        List<DimensionBean> dimensions = new ArrayList<>();
        for (String id : dimensionIds) {
            DimensionBean dimension = mock(DimensionBean.class);
            when(dimension.getId()).thenReturn(id);
            when(dimension.isTimeDimension()).thenReturn("TIME_PERIOD".equals(id));
            dimensions.add(dimension);
        }
        when(dimensionList.getDimensions()).thenReturn(dimensions);
        return beans;
    }
}
