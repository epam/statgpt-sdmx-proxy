package com.epam.sdmxproxy.services.fixture.availability;

import com.epam.sdmxproxy.configuration.data.ReturnFormat;
import com.epam.sdmxproxy.services.adapter.conversion.StreamingStructureConversionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test using real IMF WEO availability and structure data.
 * Parses the structures JSON to obtain SdmxBeans and verifies the fixture
 * correctly moves dimensions from components to keyValues.
 */
@SpringBootTest(classes = com.epam.sdmxproxy.SdmxApiProxyApplication.class)
@TestPropertySource(properties = {
        "spring.main.allow-bean-definition-overriding=true"
})
class MoveCubeRegionComponentsToKeyValuesJsonFixtureRealDataTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private StreamingStructureConversionService streamingStructureConversionService;

    @Autowired
    private MoveCubeRegionComponentsToKeyValuesJsonFixture fixture;

    private static JsonNode findKeyValuesById(JsonNode keyValues, String id) {
        for (JsonNode kv : keyValues) {
            if (id.equals(kv.path("id").asText())) {
                return kv;
            }
        }
        return null;
    }

    @Test
    @SneakyThrows
    void shouldMoveDimensionsFromComponentsToKeyValues_withRealImfWeoData() {
        //GIVEN
        InputStream availabilityInput = getClass().getResourceAsStream("imf_weo_availability_response.json");
        InputStream structuresInput = getClass().getResourceAsStream("structures_dataflow_imf_res_weo_9_0_0_detail_full_references_descendants.json");

        SdmxBeans sdmxBeans = streamingStructureConversionService.parseStructures(structuresInput, ReturnFormat.JSON_STRUCTURE_2_0_0);

        //WHEN
        InputStream result = fixture.apply(availabilityInput, sdmxBeans, new HashMap<>());

        //THEN
        JsonNode root = MAPPER.readTree(result);
        JsonNode cubeRegion = root.path("data").path("dataConstraints").get(0).path("cubeRegions").get(0);

        JsonNode keyValues = cubeRegion.path("keyValues");
        assertTrue(keyValues.isArray(), "keyValues should be an array");
        assertTrue(keyValues.size() >= 3, "Should have at least 3 dimension entries (COUNTRY, FREQUENCY, INDICATOR) in keyValues");

        Set<String> keyValueIds = StreamSupport.stream(keyValues.spliterator(), false)
                .map(kv -> kv.path("id").asText())
                .collect(Collectors.toSet());
        assertTrue(keyValueIds.contains("COUNTRY"), "COUNTRY should be in keyValues");
        assertTrue(keyValueIds.contains("FREQUENCY"), "FREQUENCY should be in keyValues");
        assertTrue(keyValueIds.contains("INDICATOR"), "INDICATOR should be in keyValues");

        JsonNode components = cubeRegion.path("components");
        assertTrue(components.isArray(), "components should be an array");
        for (JsonNode comp : components) {
            String id = comp.path("id").asText();
            assertFalse(keyValueIds.contains(id), "Component " + id + " should not be in keyValues (non-dimension must stay in components)");
        }

        JsonNode countryKeyValues = findKeyValuesById(keyValues, "COUNTRY");
        assertTrue(countryKeyValues != null && countryKeyValues.path("values").size() > 0, "COUNTRY keyValues should have values");

        assertEquals("ABW", countryKeyValues.path("values").get(0).path("value").asText(), "First COUNTRY value should be preserved");
    }
}
