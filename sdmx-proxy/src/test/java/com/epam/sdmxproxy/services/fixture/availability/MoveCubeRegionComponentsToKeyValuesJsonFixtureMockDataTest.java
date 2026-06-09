package com.epam.sdmxproxy.services.fixture.availability;

import com.epam.sdmxproxy.configuration.data.SdmxFormat;
import com.epam.sdmxproxy.configuration.data.fixture.AvailabilityFixtureType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;
import io.sdmx.api.sdmx.model.beans.datastructure.DataStructureBean;
import io.sdmx.api.sdmx.model.beans.datastructure.DimensionBean;
import lombok.SneakyThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MoveCubeRegionComponentsToKeyValuesJsonFixtureMockDataTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final MoveCubeRegionComponentsToKeyValuesJsonFixture fixture = new MoveCubeRegionComponentsToKeyValuesJsonFixture(new ObjectMapper());

    private SdmxBeans sdmxBeansWithDimensions;

    private static SdmxBeans createSdmxBeansWithDimensions(String... dimensionIds) {
        SdmxBeans beans = mock(SdmxBeans.class);
        DataStructureBean dsd = mock(DataStructureBean.class);
        List<DimensionBean> dimensions = Stream.of(dimensionIds)
                .map(id -> {
                    DimensionBean dim = mock(DimensionBean.class);
                    when(dim.getId()).thenReturn(id);
                    return dim;
                })
                .toList();
        when(dsd.getDimensions()).thenReturn(dimensions);
        when(beans.getDataStructures()).thenReturn(Set.of(dsd));
        return beans;
    }

    @BeforeEach
    void setUp() {
        sdmxBeansWithDimensions = createSdmxBeansWithDimensions("COUNTRY", "FREQUENCY", "INDICATOR");
    }

    @Test
    void shouldReturnCorrectType() {
        assertEquals(AvailabilityFixtureType.MOVE_CUBE_REGION_COMPONENTS_TO_KEY_VALUES, fixture.getType());
    }

    @Test
    void shouldSupportJsonStructure() {
        assertTrue(fixture.supportedFormats().contains(SdmxFormat.JSON_STRUCTURE_2_0_0));
    }

    @Test
    void shouldNotSupportXmlFormats() {
        assertFalse(fixture.supportedFormats().contains(SdmxFormat.XML_STRUCTURE_2_1));
    }

    @Test
    @SneakyThrows
    void shouldMoveDimensionsFromComponentsToKeyValues() {
        //GIVEN
        InputStream input = getClass().getResourceAsStream("availability_with_components_in_wrong_place.json");

        //WHEN
        InputStream result = fixture.apply(input, sdmxBeansWithDimensions, Map.of());


        //THEN
        JsonNode root = MAPPER.readTree(result);

        JsonNode cubeRegion = root.path("data").path("dataConstraints").get(0).path("cubeRegions").get(0);

        JsonNode keyValues = cubeRegion.path("keyValues");
        assertTrue(keyValues.isArray(), "keyValues should be an array");
        assertEquals(3, keyValues.size(), "Should have 3 dimension entries in keyValues");

        Set<String> keyValueIds = new java.util.HashSet<>();
        for (JsonNode kv : keyValues) {
            keyValueIds.add(kv.path("id").asText());
        }
        assertTrue(keyValueIds.contains("COUNTRY"));
        assertTrue(keyValueIds.contains("FREQUENCY"));
        assertTrue(keyValueIds.contains("INDICATOR"));

        JsonNode components = cubeRegion.path("components");
        assertTrue(components.isArray(), "components should be an array");
        assertEquals(1, components.size(), "Should have 1 non-dimension entry in components");
        assertEquals("SOME_ATTRIBUTE", components.get(0).path("id").asText());
    }

    @Test
    @SneakyThrows
    void shouldPreserveKeyValuesStructure() {
        //GIVEN
        InputStream input = getClass().getResourceAsStream(
                "availability_with_components_in_wrong_place.json");

        //WHEN
        InputStream result = fixture.apply(input, sdmxBeansWithDimensions, Map.of());
        JsonNode root = MAPPER.readTree(result);

        //THEN
        JsonNode keyValues = root.path("data").path("dataConstraints").get(0)
                .path("cubeRegions").get(0).path("keyValues");

        JsonNode countryKey = keyValues.get(0);
        assertEquals("COUNTRY", countryKey.path("id").asText());
        assertTrue(countryKey.path("include").asBoolean());
        assertFalse(countryKey.path("removePrefix").asBoolean());
        JsonNode values = countryKey.path("values");
        assertEquals(2, values.size());
        assertEquals("USA", values.get(0).path("value").asText());
        assertEquals("GBR", values.get(1).path("value").asText());
    }

    @Test
    @SneakyThrows
    void shouldReturnOriginalStreamWhenSdmxBeansIsNull() {
        //GIVEN
        String json = "{\"data\": {\"dataConstraints\": [{\"cubeRegions\": [{\"components\": [{\"id\": \"COUNTRY\"}]}]}]}}";
        InputStream input = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));

        //WHEN
        InputStream result = fixture.apply(input, null, Map.of());

        //THEN
        JsonNode root = MAPPER.readTree(result);
        JsonNode components = root.path("data").path("dataConstraints").get(0).path("cubeRegions").get(0).path("components");
        assertEquals(1, components.size(), "Should not modify when sdmxBeans is null");
        assertEquals("COUNTRY", components.get(0).path("id").asText());
    }

    @Test
    @SneakyThrows
    void shouldReturnOriginalStreamWhenSdmxBeansHasNoDataStructures() {
        //GIVEN
        String json = "{\"data\": {\"dataConstraints\": [{\"cubeRegions\": [{\"components\": [{\"id\": \"COUNTRY\"}]}]}]}}";
        InputStream input = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
        SdmxBeans emptyBeans = mock(SdmxBeans.class);
        when(emptyBeans.getDataStructures()).thenReturn(Collections.emptySet());

        //WHEN
        InputStream result = fixture.apply(input, emptyBeans, Map.of());

        //THEN
        JsonNode root = MAPPER.readTree(result);
        JsonNode components = root.path("data").path("dataConstraints").get(0).path("cubeRegions").get(0).path("components");
        assertEquals(1, components.size(), "Should not modify when no DSD");
    }

    @Test
    @SneakyThrows
    void shouldHandleJsonWithNoDataConstraints() {
        //GIVEN
        String json = "{\"data\": {}}";
        InputStream input = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));

        //WHEN
        InputStream result = fixture.apply(input, sdmxBeansWithDimensions, Map.of());

        //THEN
        JsonNode root = MAPPER.readTree(result);
        assertTrue(root.path("data").isObject());
    }

    @Test
    @SneakyThrows
    void shouldHandleCubeRegionWithNoComponents() {
        //GIVEN
        String json = "{\"data\": {\"dataConstraints\": [{\"cubeRegions\": [{\"include\": true}]}]}}";
        InputStream input = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));

        //WHEN
        InputStream result = fixture.apply(input, sdmxBeansWithDimensions, Map.of());

        //THEN
        JsonNode root = MAPPER.readTree(result);
        JsonNode cubeRegion = root.path("data").path("dataConstraints").get(0).path("cubeRegions").get(0);
        assertTrue(cubeRegion.path("include").asBoolean());
    }

}
