package com.epam.sdmxproxy.services.fixture;

import com.epam.sdmxproxy.configuration.data.FixtureType;
import com.epam.sdmxproxy.configuration.data.ReturnFormat;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionWildcardStructureJsonFixtureTest {

    private final VersionWildcardStructureJsonFixture fixture = new VersionWildcardStructureJsonFixture();

    @Test
    void shouldReturnCorrectType() {
        assertEquals(FixtureType.VERSION_WILDCARD, fixture.getType());
    }

    @Test
    void shouldSupportJsonStructure() {
        assertTrue(fixture.supportedFormats().contains(ReturnFormat.JSON_STRUCTURE_2_0_0));
    }

    @Test
    void shouldNotSupportXmlFormats() {
        assertFalse(fixture.supportedFormats().contains(ReturnFormat.XML_2_1));
        assertFalse(fixture.supportedFormats().contains(ReturnFormat.XML_GENERICDATA_2_1));
        assertFalse(fixture.supportedFormats().contains(ReturnFormat.XML_STRUCTURE_SPECIFIC_2_1));
    }

    @Test
    @SneakyThrows
    void shouldFixMajorWildcard_plusOperator() {
        String input = wrapInJson("(1+.5.1)");
        String result = applyFixture(input);
        assertTrue(result.contains("(1+.0.0)"), "Expected (1+.5.1) -> (1+.0.0), got: " + result);
    }

    @Test
    @SneakyThrows
    void shouldFixMajorWildcard_tildeOperator() {
        String input = wrapInJson("(2~.3.4)");
        String result = applyFixture(input);
        assertTrue(result.contains("(2~.0.0)"), "Expected (2~.3.4) -> (2~.0.0), got: " + result);
    }

    @Test
    @SneakyThrows
    void shouldFixMajorWildcard_starOperator() {
        String input = wrapInJson("(1*.2.3)");
        String result = applyFixture(input);
        assertTrue(result.contains("(1*.0.0)"), "Expected (1*.2.3) -> (1*.0.0), got: " + result);
    }

    @Test
    @SneakyThrows
    void shouldFixMinorWildcard_plusOperator() {
        String input = wrapInJson("(1.3+.1)");
        String result = applyFixture(input);
        assertTrue(result.contains("(1.3+.0)"), "Expected (1.3+.1) -> (1.3+.0), got: " + result);
    }

    @Test
    @SneakyThrows
    void shouldFixMinorWildcard_tildeOperator() {
        String input = wrapInJson("(1.5~.7)");
        String result = applyFixture(input);
        assertTrue(result.contains("(1.5~.0)"), "Expected (1.5~.7) -> (1.5~.0), got: " + result);
    }

    @Test
    @SneakyThrows
    void shouldFixMinorWildcard_starOperator() {
        String input = wrapInJson("(2.1*.9)");
        String result = applyFixture(input);
        assertTrue(result.contains("(2.1*.0)"), "Expected (2.1*.9) -> (2.1*.0), got: " + result);
    }

    @Test
    @SneakyThrows
    void shouldNotModifyPatchWildcard() {
        String input = wrapInJson("1.5.1+");
        String result = applyFixture(input);
        assertTrue(result.contains("1.5.1+"), "Patch wildcard should remain unchanged, got: " + result);
    }

    @Test
    @SneakyThrows
    void shouldNotModifyAlreadyCompliantMajorWildcard() {
        String input = wrapInJson("(1+.0.0)");
        String result = applyFixture(input);
        assertTrue(result.contains("(1+.0.0)"), "Already compliant version should remain unchanged");
    }

    @Test
    @SneakyThrows
    void shouldNotModifyAlreadyCompliantMinorWildcard() {
        String input = wrapInJson("(1.0+.0)");
        String result = applyFixture(input);
        assertTrue(result.contains("(1.0+.0)"), "Already compliant version should remain unchanged");
    }

    @Test
    @SneakyThrows
    void shouldNotModifyVersionsWithoutWildcards() {
        String input = wrapInJson("1.2.3");
        String result = applyFixture(input);
        assertTrue(result.contains("1.2.3"), "Plain version should remain unchanged, got: " + result);
    }

    @Test
    @SneakyThrows
    void shouldNotReplaceVersionInFreeTextWithoutParentheses() {
        String input = "{\"description\": \"Use version 1.3+.1 for compatibility\", \"name\": \"Schema 2.0+.5\"}";
        String result = applyFixture(input);

        assertTrue(result.contains("1.3+.1"), "Version in description without parentheses must not be replaced");
        assertTrue(result.contains("2.0+.5"), "Version in name without parentheses must not be replaced");
    }

    @Test
    @SneakyThrows
    void shouldFixVersionInsideUrn() {
        String urn = "urn:sdmx:org.sdmx.infomodel.conceptscheme.Concept=IMF:CS_MASTER_SYSTEM(1.3+.1).OBS_VALUE";
        String input = "{\"conceptIdentity\": \"" + urn + "\"}";
        String result = applyFixture(input);

        String expectedUrn = "urn:sdmx:org.sdmx.infomodel.conceptscheme.Concept=IMF:CS_MASTER_SYSTEM(1.3+.0).OBS_VALUE";
        assertTrue(result.contains(expectedUrn),
                "URN version should be fixed. Expected: " + expectedUrn + ", got: " + result);
    }

    @Test
    @SneakyThrows
    void shouldFixMultipleOccurrencesInSameDocument() {
        String input = "{\"a\": \"(1.3+.1)\", \"b\": \"(2+.5.3)\", \"c\": \"(1.0+.0)\"}";
        String result = applyFixture(input);

        assertTrue(result.contains("(1.3+.0)"), "First wildcard should be fixed");
        assertTrue(result.contains("(2+.0.0)"), "Second wildcard should be fixed");
        assertTrue(result.contains("(1.0+.0)"), "Already compliant should remain unchanged");
    }

    @Test
    @SneakyThrows
    void shouldHandleEmptyJson() {
        String input = "{}";
        String result = applyFixture(input);
        assertEquals("{}", result);
    }

    @Test
    @SneakyThrows
    void shouldHandleMinimalJson() {
        String input = "{\"data\": {}}";
        String result = applyFixture(input);
        assertEquals("{\"data\": {}}", result);
    }

    @Test
    @SneakyThrows
    void shouldFixVersionsInRealDsdFile() {
        InputStream input = getClass().getResourceAsStream(
                "/com/epam/sdmxproxy/services/adapter/structure_conversion/imf/3.0/dsd_detail_full.json");

        InputStream result = fixture.apply(input, Collections.emptyMap());
        String content = new String(result.readAllBytes(), StandardCharsets.UTF_8);

        assertFalse(content.matches(".*\\(\\d+[+~*]\\.(?!0\\.0\\))\\d+\\.\\d+\\).*"),
                "No major wildcard in URN context should have non-zero minor or patch part after fix");
        assertFalse(content.matches(".*\\(\\d+\\.\\d+[+~*]\\.([1-9]\\d*)\\).*"),
                "No minor wildcard in URN context should have non-zero patch part after fix");

        assertTrue(content.contains("(1.0+.0)"),
                "Already compliant versions in URN context should still be present");
    }

    private String wrapInJson(String version) {
        return "{\"version\": \"" + version + "\"}";
    }

    @SneakyThrows
    private String applyFixture(String json) {
        InputStream input = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
        InputStream result = fixture.apply(input, Collections.emptyMap());
        return new String(result.readAllBytes(), StandardCharsets.UTF_8);
    }
}
