package com.epam.sdmxproxy.services.fixture.data;

import com.epam.sdmxproxy.configuration.data.SdmxFormat;
import com.epam.sdmxproxy.configuration.data.fixture.DataFixtureType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimePeriodMonthlyNormalizationXmlGenericDataFixtureTest {

    private TimePeriodMonthlyNormalizationXmlGenericDataFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new TimePeriodMonthlyNormalizationXmlGenericDataFixture(new StreamingFixtureIO());
    }

    @Test
    void getType_returnsMonthlyNormalization() {
        assertEquals(DataFixtureType.TIME_PERIOD_MONTHLY_NORMALIZATION, fixture.getType());
    }

    @Test
    void supportedFormats_containsOnlyXmlGeneric() {
        assertEquals(1, fixture.supportedFormats().size());
        assertTrue(fixture.supportedFormats().contains(SdmxFormat.XML_GENERIC_DATA_2_1));
    }

    @Test
    void apply_rewritesMonthlyObsDimensionValue() throws IOException {
        String input = """
                <?xml version="1.0" encoding="UTF-8"?>
                <msg:GenericData xmlns:msg="http://www.sdmx.org/resources/sdmxml/schemas/v2_1/message" xmlns:g="http://www.sdmx.org/resources/sdmxml/schemas/v2_1/data/generic">
                  <msg:DataSet>
                    <g:Series>
                      <g:Obs>
                        <g:ObsDimension id="TIME_PERIOD" value="2024-03"/>
                        <g:ObsValue value="5.25"/>
                      </g:Obs>
                      <g:Obs>
                        <g:ObsDimension id="TIME_PERIOD" value="2024-04"/>
                        <g:ObsValue value="5.5"/>
                      </g:Obs>
                    </g:Series>
                  </msg:DataSet>
                </msg:GenericData>
                """;

        String output = applyAsString(input);
        assertTrue(output.contains("value=\"2024-M03\""), "March value should be normalized: " + output);
        assertTrue(output.contains("value=\"2024-M04\""), "April value should be normalized: " + output);
        assertFalse(output.contains("value=\"2024-03\""), "Original March value should not remain");
        assertTrue(output.contains("value=\"5.25\""), "ObsValue should pass through");
    }

    @Test
    void apply_leavesCanonicalValuesUnchanged() throws IOException {
        String input = """
                <?xml version="1.0" encoding="UTF-8"?>
                <GenericData xmlns="http://www.sdmx.org/resources/sdmxml/schemas/v2_1/data/generic">
                  <Obs>
                    <ObsDimension id="TIME_PERIOD" value="2024-M03"/>
                  </Obs>
                </GenericData>
                """;

        String output = applyAsString(input);
        assertTrue(output.contains("value=\"2024-M03\""));
    }

    @Test
    void apply_ignoresAnnualAndQuarterlyValues() throws IOException {
        String input = """
                <?xml version="1.0" encoding="UTF-8"?>
                <GenericData xmlns="http://www.sdmx.org/resources/sdmxml/schemas/v2_1/data/generic">
                  <Obs><ObsDimension id="TIME_PERIOD" value="2024"/></Obs>
                  <Obs><ObsDimension id="TIME_PERIOD" value="2024-Q1"/></Obs>
                  <Obs><ObsDimension id="TIME_PERIOD" value="2024-03-15"/></Obs>
                </GenericData>
                """;

        String output = applyAsString(input);
        assertTrue(output.contains("value=\"2024\""));
        assertTrue(output.contains("value=\"2024-Q1\""));
        assertTrue(output.contains("value=\"2024-03-15\""));
    }

    @Test
    void apply_rewritesImplicitObsDimensionWithoutIdAttribute() throws IOException {
        // SDMX-ML 2.1 Generic omits the `id` attribute when the observation dimension is the
        // implicit TIME_PERIOD. BIS emits responses in this shape.
        String input = """
                <?xml version="1.0" encoding="UTF-8"?>
                <msg:GenericData xmlns:msg="http://www.sdmx.org/resources/sdmxml/schemas/v2_1/message" xmlns:g="http://www.sdmx.org/resources/sdmxml/schemas/v2_1/data/generic">
                  <msg:DataSet>
                    <g:Series>
                      <g:Obs>
                        <g:ObsDimension value="2024-03"/>
                        <g:ObsValue value="5.25"/>
                      </g:Obs>
                    </g:Series>
                  </msg:DataSet>
                </msg:GenericData>
                """;

        String output = applyAsString(input);
        assertTrue(output.contains("value=\"2024-M03\""),
                "Implicit ObsDimension (no id attribute) must be treated as TIME_PERIOD: " + output);
    }

    @Test
    void apply_ignoresNonTimePeriodObsDimension() throws IOException {
        String input = """
                <?xml version="1.0" encoding="UTF-8"?>
                <GenericData xmlns="http://www.sdmx.org/resources/sdmxml/schemas/v2_1/data/generic">
                  <Obs><ObsDimension id="FREQ" value="2024-03"/></Obs>
                </GenericData>
                """;

        String output = applyAsString(input);
        assertTrue(output.contains("value=\"2024-03\""), "Non-TIME_PERIOD ObsDimension must not be rewritten");
    }

    @Test
    void apply_ignoresAttributeNamedValueOnOtherElements() throws IOException {
        String input = """
                <?xml version="1.0" encoding="UTF-8"?>
                <GenericData xmlns="http://www.sdmx.org/resources/sdmxml/schemas/v2_1/data/generic">
                  <SeriesKey>
                    <Value id="FREQ" value="2024-03"/>
                  </SeriesKey>
                  <Obs>
                    <ObsDimension id="TIME_PERIOD" value="2024-03"/>
                  </Obs>
                </GenericData>
                """;

        String output = applyAsString(input);
        // The <Value id="FREQ" value="2024-03"/> is NOT <ObsDimension> so should pass through.
        assertTrue(output.contains("<Value") && output.contains("value=\"2024-03\""));
        assertTrue(output.contains("value=\"2024-M03\""), "ObsDimension value should be normalized");
    }

    private String applyAsString(String input) throws IOException {
        InputStream source = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8));
        try (InputStream result = fixture.apply(source, null, new HashMap<>())) {
            return new String(result.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
