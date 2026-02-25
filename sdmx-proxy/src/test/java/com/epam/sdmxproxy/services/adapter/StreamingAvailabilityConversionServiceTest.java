package com.epam.sdmxproxy.services.adapter;

import com.epam.sdmxproxy.common.data.SdmxMediaType;
import com.epam.sdmxproxy.configuration.data.ReturnFormat;
import com.epam.sdmxproxy.services.adapter.conversion.StreamingAvailabilityConversionService;
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

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(classes = com.epam.sdmxproxy.SdmxApiProxyApplication.class)
@TestPropertySource(properties = {
        "spring.main.allow-bean-definition-overriding=true"
})
public class StreamingAvailabilityConversionServiceTest {
    @Autowired
    private StreamingAvailabilityConversionService sut;

    @Test
    @SneakyThrows
    void shouldConvertAvailabilityJsonFrom3_0_registry() {
        //GIVEN
        InputStream input = getClass().getResourceAsStream("imf_3_0_availability_response.json");
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        MediaType targetMediaType = MediaType.valueOf(SdmxMediaType.STRUCTURE_SDMX_JSON_2_0_0_VALUE);

        //WHEN
        sut.convert(input, outputStream, ReturnFormat.JSON_STRUCTURE_2_0_0, targetMediaType);

        //THEN
        JsonNode jsonNode = new ObjectMapper().readTree(outputStream.toByteArray());
        JsonNode countryComponent = jsonNode.path("data").path("dataConstraints").get(0).path("cubeRegions").get(0).path("components").get(0);
        assertEquals("COUNTRY", countryComponent.path("id").asString());
        assertEquals(106, countryComponent.path("values").size());

    }

    @Test
    @SneakyThrows
    void shouldConvertAvailabilityJsonFrom2_1_registry() {
        //GIVEN
        InputStream input = getClass().getResourceAsStream("imf_2_1_availability_response.xml");
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        MediaType targetMediaType = MediaType.valueOf(SdmxMediaType.STRUCTURE_SDMX_JSON_2_0_0_VALUE);

        //WHEN
        sut.convert(input, outputStream, ReturnFormat.XML_2_1, targetMediaType);

        //THEN
        JsonNode jsonNode = new ObjectMapper().readTree(outputStream.toByteArray());
        JsonNode countryComponent = jsonNode.path("data").path("dataConstraints").get(0).path("cubeRegions").get(0).path("components").get(0);
        assertEquals("COUNTRY", countryComponent.path("id").asString());
        assertEquals(106, countryComponent.path("values").size());
    }
}
