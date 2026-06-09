package com.epam.sdmxproxy.services.adapter;

import com.epam.sdmxproxy.common.data.SdmxMediaTypeResolver;
import com.epam.sdmxproxy.configuration.data.SdmxMediaTypes;
import com.epam.sdmxproxy.configuration.data.SdmxFormat;
import com.epam.sdmxproxy.services.adapter.conversion.StreamingDataConversionService;
import com.epam.sdmxproxy.services.adapter.conversion.StreamingStructureConversionService;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end conversion tests for SDMX-JSON 2.0 dimension-group attribute
 * round-trip (issue #83 / design 030). Exercises the full pipeline: reader →
 * bean stream → writer, for a dataflow whose DSD declares group-attached
 * attributes (IMF WEO).
 *
 * <p>Pre-fix behaviour: the proxy emitted neither
 * {@code data.dataSets[*].dimensionGroupAttributes} (value side) nor
 * {@code data.structures[*].attributes.dimensionGroup} (structure side); the
 * group-attached attribute definitions were folded into
 * {@code attributes.series} instead. These tests pin the corrected behaviour.
 */
@SpringBootTest(classes = com.epam.sdmxproxy.SdmxApiProxyApplication.class)
@TestPropertySource(properties = {
        "spring.main.allow-bean-definition-overriding=true"
})
public class DimensionGroupAttributesEndToEndTest {

    private static final String DATA_FIXTURE = "data_conversion/data_weo_misroute_3countries.json";
    private static final String STRUCTURES_FIXTURE = "data_conversion/structures_dataflow_imf_res_weo_9_0_0_detail_full_references_descendants.json";

    @Autowired
    private StreamingStructureConversionService streamingStructureConversionService;

    @Autowired
    private StreamingDataConversionService streamingDataConversionService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @SneakyThrows
    void shouldEmitDimensionGroupAttributesValueSide() {
        JsonNode root = convertWeoJson20ToJson20();
        JsonNode firstDataSet = root.path("data").path("dataSets").get(0);
        JsonNode dga = firstDataSet.path("dimensionGroupAttributes");

        assertTrue(dga.isObject(), "data.dataSets[0].dimensionGroupAttributes should be an object");
        assertFalse(dga.isEmpty(), "dimensionGroupAttributes should not be empty for a flow with group attrs");

        Set<String> keys = new LinkedHashSet<>(dga.propertyNames());
        for (String k : keys) {
            String[] positions = k.split(":", -1);
            assertEquals(4, positions.length,
                    "Group key '" + k + "' should have 4 positions (3 series dims + 1 obs dim) per SDMX-JSON 2.0 spec");
            assertTrue(positions[0].isEmpty(),
                    "Position 0 (COUNTRY) should be empty for INDICATOR-only group: '" + k + "'");
            assertTrue(positions[2].isEmpty(),
                    "Position 2 (FREQUENCY) should be empty for INDICATOR-only group: '" + k + "'");
            assertTrue(positions[3].isEmpty(),
                    "Position 3 (TIME_PERIOD) should be empty for any group: '" + k + "'");
            assertFalse(positions[1].isEmpty(),
                    "Position 1 (INDICATOR) should carry the index for an INDICATOR group: '" + k + "'");
        }
    }

    @Test
    @SneakyThrows
    void shouldEmitDimensionGroupAttributesStructureSide() {
        JsonNode root = convertWeoJson20ToJson20();
        JsonNode attributes = root.path("data").path("structures").get(0).path("attributes");

        JsonNode dimensionGroup = attributes.path("dimensionGroup");
        assertTrue(dimensionGroup.isArray(),
                "data.structures[0].attributes.dimensionGroup should be an array");
        assertFalse(dimensionGroup.isEmpty(),
                "attributes.dimensionGroup should contain the DSD's group-attached attribute defs");

        Set<String> groupAttrIds = new HashSet<>();
        for (JsonNode attr : dimensionGroup) {
            groupAttrIds.add(attr.path("id").asText());
        }
        assertTrue(groupAttrIds.contains("FUNCTIONAL_CAT"),
                "FUNCTIONAL_CAT (INDICATOR-grouped) should be in attributes.dimensionGroup");
        assertTrue(groupAttrIds.contains("UNIT"),
                "UNIT (INDICATOR-grouped) should be in attributes.dimensionGroup");
    }

    @Test
    @SneakyThrows
    void shouldNotFoldGroupAttrsIntoSeries() {
        JsonNode root = convertWeoJson20ToJson20();
        JsonNode attributes = root.path("data").path("structures").get(0).path("attributes");

        JsonNode series = attributes.path("series");
        assertTrue(series.isArray(), "attributes.series should be an array");

        Set<String> seriesAttrIds = new HashSet<>();
        for (JsonNode attr : series) {
            seriesAttrIds.add(attr.path("id").asText());
        }
        // Pre-fix the WEO DSD's INDICATOR-grouped attributes (FUNCTIONAL_CAT etc.) were
        // folded into attributes.series alongside the genuine series-level attrs
        // (SCALE / DECIMALS_DISPLAYED / OVERLAP / COUNTRY_UPDATE_DATE).
        assertFalse(seriesAttrIds.contains("FUNCTIONAL_CAT"),
                "FUNCTIONAL_CAT is group-attached and must not leak into attributes.series");
        assertFalse(seriesAttrIds.contains("UNIT"),
                "UNIT is group-attached and must not leak into attributes.series");
    }

    @Test
    @SneakyThrows
    void shouldPreserveDimensionGroupAttributeValuesAcrossRoundTrip() {
        // Compare the proxy's output dimensionGroupAttributes against the upstream input.
        // The proxy should emit the same set of group keys with the same number of non-null
        // positional values per key (positions may be renumbered if the structure sidecar's
        // values arrays differ, but the cardinality of non-null values must match).
        InputStream raw = getClass().getResourceAsStream(DATA_FIXTURE);
        assertNotNull(raw, "fixture must be present");
        JsonNode input = objectMapper.readTree(raw);
        JsonNode inputDga = input.path("data").path("dataSets").get(0).path("dimensionGroupAttributes");
        assertTrue(inputDga.isObject() && !inputDga.isEmpty(), "fixture must contain dimensionGroupAttributes");

        JsonNode output = convertWeoJson20ToJson20();
        JsonNode outputDga = output.path("data").path("dataSets").get(0).path("dimensionGroupAttributes");
        assertTrue(outputDga.isObject() && !outputDga.isEmpty(), "output should preserve dimensionGroupAttributes");

        Set<String> inputKeys = new LinkedHashSet<>(inputDga.propertyNames());
        Set<String> outputKeys = new LinkedHashSet<>(outputDga.propertyNames());

        // The exact key strings depend on value-table indexing on the writer side; their
        // count and overall non-null value count should match the input.
        assertEquals(inputKeys.size(), outputKeys.size(),
                "Group-key count should match: input had " + inputKeys.size() + ", output had " + outputKeys.size());

        int inputNonNullValues = countNonNullPositionalValues(inputDga);
        int outputNonNullValues = countNonNullPositionalValues(outputDga);
        assertEquals(inputNonNullValues, outputNonNullValues,
                "Total non-null group attribute positional values should round-trip");
    }

    @Test
    @SneakyThrows
    void shouldRoundTripThroughJsonAndBack() {
        // JSON-in → JSON-out → JSON-in: the second conversion should still see
        // dimensionGroupAttributes coming back through the reader.
        SdmxBeans sdmxBeans = loadWeoStructures();
        InputStream input = getClass().getResourceAsStream(DATA_FIXTURE);
        ByteArrayOutputStream firstPass = new ByteArrayOutputStream();
        streamingDataConversionService.convert(input, firstPass, sdmxBeans, SdmxFormat.JSON_DATA_2_0_0,
                MediaType.valueOf(SdmxMediaTypes.DATA_JSON_2_0_0));

        ByteArrayOutputStream secondPass = new ByteArrayOutputStream();
        streamingDataConversionService.convert(new ByteArrayInputStream(firstPass.toByteArray()), secondPass, sdmxBeans,
                SdmxFormat.JSON_DATA_2_0_0, MediaType.valueOf(SdmxMediaTypes.DATA_JSON_2_0_0));

        JsonNode root = objectMapper.readTree(secondPass.toByteArray());
        JsonNode dga = root.path("data").path("dataSets").get(0).path("dimensionGroupAttributes");
        assertTrue(dga.isObject() && !dga.isEmpty(),
                "Second round trip should still expose dimensionGroupAttributes");
        JsonNode dimGroup = root.path("data").path("structures").get(0).path("attributes").path("dimensionGroup");
        assertTrue(dimGroup.isArray() && !dimGroup.isEmpty(),
                "Second round trip should still expose attributes.dimensionGroup");
    }

    @SneakyThrows
    private JsonNode convertWeoJson20ToJson20() {
        SdmxBeans sdmxBeans = loadWeoStructures();
        InputStream input = getClass().getResourceAsStream(DATA_FIXTURE);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        streamingDataConversionService.convert(input, out, sdmxBeans, SdmxFormat.JSON_DATA_2_0_0,
                MediaType.valueOf(SdmxMediaTypes.DATA_JSON_2_0_0));
        return objectMapper.readTree(out.toByteArray());
    }

    @SneakyThrows
    private SdmxBeans loadWeoStructures() {
        InputStream structures = getClass().getResourceAsStream(STRUCTURES_FIXTURE);
        return streamingStructureConversionService.parseStructures(structures, SdmxFormat.JSON_STRUCTURE_2_0_0);
    }

    private static int countNonNullPositionalValues(JsonNode dga) {
        int count = 0;
        for (JsonNode arr : dga) {
            if (!arr.isArray()) {
                continue;
            }
            for (JsonNode v : arr) {
                if (!v.isNull()) {
                    count++;
                }
            }
        }
        return count;
    }
}
