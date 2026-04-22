package com.epam.sdmxproxy.services.fixture.data;

import com.epam.sdmxproxy.configuration.data.ReturnFormat;
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

class TimePeriodMonthlyNormalizationXmlStructureSpecificDataFixtureTest {

    private TimePeriodMonthlyNormalizationXmlStructureSpecificDataFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new TimePeriodMonthlyNormalizationXmlStructureSpecificDataFixture(new StreamingFixtureIO());
    }

    @Test
    void getType_returnsMonthlyNormalization() {
        assertEquals(DataFixtureType.TIME_PERIOD_MONTHLY_NORMALIZATION, fixture.getType());
    }

    @Test
    void supportedFormats_containsOnlyStructureSpecific() {
        assertEquals(1, fixture.supportedFormats().size());
        assertTrue(fixture.supportedFormats().contains(ReturnFormat.XML_STRUCTURE_SPECIFIC_2_1));
    }

    @Test
    void apply_rewritesTimePeriodAttributeOnObs() throws IOException {
        String input = """
                <?xml version="1.0" encoding="UTF-8"?>
                <StructureSpecificData>
                  <DataSet>
                    <Series FREQ="M" REF_AREA="US">
                      <Obs TIME_PERIOD="2024-03" OBS_VALUE="5.25"/>
                      <Obs TIME_PERIOD="2024-04" OBS_VALUE="5.5"/>
                    </Series>
                  </DataSet>
                </StructureSpecificData>
                """;

        String output = applyAsString(input);
        assertTrue(output.contains("TIME_PERIOD=\"2024-M03\""), output);
        assertTrue(output.contains("TIME_PERIOD=\"2024-M04\""), output);
        assertFalse(output.contains("TIME_PERIOD=\"2024-03\""));
        assertTrue(output.contains("OBS_VALUE=\"5.25\""));
    }

    @Test
    void apply_leavesCanonicalValuesUnchanged() throws IOException {
        String input = """
                <?xml version="1.0" encoding="UTF-8"?>
                <DataSet><Obs TIME_PERIOD="2024-M03"/></DataSet>
                """;

        String output = applyAsString(input);
        assertTrue(output.contains("TIME_PERIOD=\"2024-M03\""));
    }

    @Test
    void apply_ignoresAnnualQuarterlyDailyValues() throws IOException {
        String input = """
                <?xml version="1.0" encoding="UTF-8"?>
                <DataSet>
                  <Obs TIME_PERIOD="2024"/>
                  <Obs TIME_PERIOD="2024-Q1"/>
                  <Obs TIME_PERIOD="2024-03-15"/>
                </DataSet>
                """;

        String output = applyAsString(input);
        assertTrue(output.contains("TIME_PERIOD=\"2024\""));
        assertTrue(output.contains("TIME_PERIOD=\"2024-Q1\""));
        assertTrue(output.contains("TIME_PERIOD=\"2024-03-15\""));
    }

    @Test
    void apply_ignoresOtherAttributesWithSameValue() throws IOException {
        String input = """
                <?xml version="1.0" encoding="UTF-8"?>
                <DataSet>
                  <Obs TIME_PERIOD="2024-03" COMMENT="2024-03"/>
                </DataSet>
                """;

        String output = applyAsString(input);
        assertTrue(output.contains("TIME_PERIOD=\"2024-M03\""), output);
        assertTrue(output.contains("COMMENT=\"2024-03\""), "COMMENT attribute must pass through unchanged");
    }

    private String applyAsString(String input) throws IOException {
        InputStream source = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8));
        try (InputStream result = fixture.apply(source, null, new HashMap<>())) {
            return new String(result.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
