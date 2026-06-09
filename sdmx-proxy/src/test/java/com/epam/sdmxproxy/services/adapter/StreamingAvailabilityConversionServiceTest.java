package com.epam.sdmxproxy.services.adapter;

import com.epam.sdmxproxy.common.data.SdmxMediaTypeResolver;
import com.epam.sdmxproxy.configuration.data.SdmxMediaTypes;
import com.epam.sdmxproxy.configuration.data.SdmxFormat;
import com.epam.sdmxproxy.configuration.data.fixture.AvailabilityFixtureType;
import com.epam.sdmxproxy.configuration.data.fixture.FixtureConfiguration;
import com.epam.sdmxproxy.services.adapter.conversion.StreamingAvailabilityConversionService;
import com.epam.sdmxproxy.services.adapter.conversion.StreamingStructureConversionService;
import com.epam.sdmxproxy.services.fixture.availability.AvailabilityFixtureService;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(classes = com.epam.sdmxproxy.SdmxApiProxyApplication.class)
@TestPropertySource(properties = {
        "spring.main.allow-bean-definition-overriding=true"
})
public class StreamingAvailabilityConversionServiceTest {
    private static final String FIXTURE_AVAILABILITY_RESOURCE_PATH = "com/epam/sdmxproxy/services/fixture/availability/";

    @Autowired
    private StreamingAvailabilityConversionService sut;

    @Autowired
    private AvailabilityFixtureService availabilityFixtureService;

    @Autowired
    private StreamingStructureConversionService streamingStructureConversionService;

    @Test
    @SneakyThrows
    void shouldConvertAvailabilityJsonFrom3_0_registry() {
        //GIVEN: availability JSON with dimensions in components (3.0 style) + structures to resolve DSD
        InputStream structuresInput = getClass().getClassLoader().getResourceAsStream(
                FIXTURE_AVAILABILITY_RESOURCE_PATH + "structures_dataflow_imf_res_weo_9_0_0_detail_full_references_descendants.json");
        InputStream availabilityInput = getClass().getClassLoader().getResourceAsStream(
                FIXTURE_AVAILABILITY_RESOURCE_PATH + "imf_weo_availability_response.json");

        SdmxBeans sdmxBeans = streamingStructureConversionService.parseStructures(structuresInput, SdmxFormat.JSON_STRUCTURE_2_0_0);

        FixtureConfiguration<AvailabilityFixtureType> availabilityFixtureConfig = new FixtureConfiguration<>();
        availabilityFixtureConfig.setType(AvailabilityFixtureType.MOVE_CUBE_REGION_COMPONENTS_TO_KEY_VALUES);
        availabilityFixtureConfig.setConfig(new HashMap<>());
        InputStream fixedAvailability = availabilityFixtureService.applyFixtures(
                availabilityInput,
                SdmxFormat.JSON_STRUCTURE_2_0_0,
                sdmxBeans,
                List.of(availabilityFixtureConfig));

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        MediaType targetMediaType = MediaType.valueOf(SdmxMediaTypes.STRUCTURE_JSON_2_0_0);

        //WHEN
        sut.convert(fixedAvailability, outputStream, SdmxFormat.JSON_STRUCTURE_2_0_0, targetMediaType);

        //THEN: dimensions must appear in keyValues (SDMX-JSON 2.0 compliance)
        JsonNode jsonNode = new ObjectMapper().readTree(outputStream.toByteArray());
        JsonNode cubeRegion = jsonNode.path("data").path("dataConstraints").get(0).path("cubeRegions").get(0);
        JsonNode keyValues = cubeRegion.path("keyValues");
        assertEquals(3, keyValues.size(), "Cube region should have 3 dimension keys (COUNTRY, FREQUENCY, INDICATOR) in keyValues");
        JsonNode countryKey = null;
        for (JsonNode kv : keyValues) {
            if ("COUNTRY".equals(kv.path("id").asText())) {
                countryKey = kv;
                break;
            }
        }
        assertNotNull(countryKey, "COUNTRY must be present in keyValues");
        assertEquals("COUNTRY", countryKey.path("id").asString());
        assertEquals(210, countryKey.path("values").size());
    }

    @Test
    @SneakyThrows
    void shouldConvertAvailabilityJsonFrom2_1_registry() {
        //GIVEN
        InputStream input = getClass().getResourceAsStream("imf_2_1_availability_response.xml");
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        MediaType targetMediaType = MediaType.valueOf(SdmxMediaTypes.STRUCTURE_JSON_2_0_0);

        //WHEN
        sut.convert(input, outputStream, SdmxFormat.XML_STRUCTURE_2_1, targetMediaType);

        //THEN
        JsonNode jsonNode = new ObjectMapper().readTree(outputStream.toByteArray());
        JsonNode countryComponent = jsonNode.path("data").path("dataConstraints").get(0).path("cubeRegions").get(0).path("keyValues").get(0);
        assertEquals("COUNTRY", countryComponent.path("id").asString());
        assertEquals(106, countryComponent.path("values").size());
    }
}
