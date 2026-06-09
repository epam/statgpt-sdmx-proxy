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

class TimePeriodMonthlyNormalizationCsvDataFixtureTest {

    private TimePeriodMonthlyNormalizationCsvDataFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new TimePeriodMonthlyNormalizationCsvDataFixture(new StreamingFixtureIO());
    }

    @Test
    void getType_returnsMonthlyNormalization() {
        assertEquals(DataFixtureType.TIME_PERIOD_MONTHLY_NORMALIZATION, fixture.getType());
    }

    @Test
    void supportedFormats_containsBothCsvVersions() {
        assertEquals(2, fixture.supportedFormats().size());
        assertTrue(fixture.supportedFormats().contains(SdmxFormat.CSV_DATA_1_0_0));
        assertTrue(fixture.supportedFormats().contains(SdmxFormat.CSV_DATA_2_0_0));
    }

    @Test
    void apply_rewritesMonthlyTimePeriodColumn() throws IOException {
        String input = """
                FREQ,REF_AREA,TIME_PERIOD,OBS_VALUE
                M,US,2024-03,5.25
                M,US,2024-04,5.5
                """;

        String output = applyAsString(input);
        assertTrue(output.contains("M,US,2024-M03,5.25"), output);
        assertTrue(output.contains("M,US,2024-M04,5.5"), output);
        assertFalse(output.contains("M,US,2024-03,"));
    }

    @Test
    void apply_preservesHeaderVerbatim() throws IOException {
        String input = "FREQ,REF_AREA,TIME_PERIOD,OBS_VALUE\nM,US,2024-03,5.25\n";
        String output = applyAsString(input);
        assertTrue(output.startsWith("FREQ,REF_AREA,TIME_PERIOD,OBS_VALUE\n"));
    }

    @Test
    void apply_leavesCanonicalValuesUnchanged() throws IOException {
        String input = """
                FREQ,REF_AREA,TIME_PERIOD,OBS_VALUE
                M,US,2024-M03,5.25
                """;

        String output = applyAsString(input);
        assertTrue(output.contains("2024-M03"));
    }

    @Test
    void apply_ignoresAnnualAndQuarterlyValues() throws IOException {
        String input = """
                FREQ,REF_AREA,TIME_PERIOD,OBS_VALUE
                A,US,2024,5.25
                Q,US,2024-Q1,5.5
                D,US,2024-03-15,5.75
                """;

        String output = applyAsString(input);
        assertTrue(output.contains(",2024,"));
        assertTrue(output.contains(",2024-Q1,"));
        assertTrue(output.contains(",2024-03-15,"));
    }

    @Test
    void apply_passesThroughWhenNoTimePeriodColumn() throws IOException {
        String input = """
                FREQ,REF_AREA,OBS_PERIOD,OBS_VALUE
                M,US,2024-03,5.25
                """;

        String output = applyAsString(input);
        assertTrue(output.contains("M,US,2024-03,5.25"), "OBS_PERIOD column (not TIME_PERIOD) must not be rewritten: " + output);
    }

    @Test
    void apply_handlesQuotedFieldsInOtherColumns() throws IOException {
        String input = """
                FREQ,LABEL,TIME_PERIOD,OBS_VALUE
                M,"US, flagship",2024-03,5.25
                """;

        String output = applyAsString(input);
        assertTrue(output.contains("\"US, flagship\""), "Quoted field with comma must be preserved: " + output);
        assertTrue(output.contains("2024-M03"), output);
    }

    @Test
    void apply_handlesSdmxCsv20Shape() throws IOException {
        String input = """
                STRUCTURE,STRUCTURE_ID,ACTION,FREQ,REF_AREA,TIME_PERIOD,OBS_VALUE
                dataflow,BIS:WS_CBPOL(1.0),I,M,US,2023-11,5.25
                dataflow,BIS:WS_CBPOL(1.0),I,M,US,2023-12,5.25
                dataflow,BIS:WS_CBPOL(1.0),I,M,US,2024-01,5.5
                """;

        String output = applyAsString(input);
        assertTrue(output.contains(",M,US,2023-M11,5.25"), output);
        assertTrue(output.contains(",M,US,2023-M12,5.25"));
        assertTrue(output.contains(",M,US,2024-M01,5.5"));
    }

    @Test
    void apply_preservesEmptyValuesAndRowsWithoutMatch() throws IOException {
        String input = "FREQ,REF_AREA,TIME_PERIOD,OBS_VALUE\nM,US,,\nM,US,2024-03,5.25\n";
        String output = applyAsString(input);
        assertTrue(output.contains("M,US,,"), "Empty TIME_PERIOD row must be preserved: " + output);
        assertTrue(output.contains("2024-M03"));
    }

    private String applyAsString(String input) throws IOException {
        InputStream source = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8));
        try (InputStream result = fixture.apply(source, null, new HashMap<>())) {
            return new String(result.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
