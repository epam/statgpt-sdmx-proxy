package com.epam.sdmxproxy.services.fixture;

import com.epam.sdmxproxy.configuration.data.ReturnFormat;
import com.epam.sdmxproxy.configuration.data.fixture.StructureFixtureType;
import com.epam.sdmxproxy.services.fixture.structure.AnnotationValueToTextJsonFixture;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnnotationValueToTextJsonFixtureTest {

    private final AnnotationValueToTextJsonFixture fixture = new AnnotationValueToTextJsonFixture();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void typeIsAnnotationValueToText() {
        assertEquals(StructureFixtureType.ANNOTATION_VALUE_TO_TEXT, fixture.getType());
    }

    @Test
    void supportsJsonStructure20() {
        assertTrue(fixture.supportedFormats().contains(ReturnFormat.JSON_STRUCTURE_2_0_0));
    }

    @Test
    @SneakyThrows
    void rewritesValueToTextWhenTextAbsent() {
        String input = """
                {"data":{"dataStructures":[{"annotations":[{"id":"origin","value":"INTEGRATION"}]}]}}
                """;

        JsonNode root = applyToTree(input);
        JsonNode annotation = root.path("data").path("dataStructures").get(0).path("annotations").get(0);
        assertThat(annotation.has("value")).isFalse();
        assertThat(annotation.path("text").asText()).isEqualTo("INTEGRATION");
    }

    @Test
    @SneakyThrows
    void leavesAnnotationUntouchedWhenTextAlreadyPresent() {
        String input = """
                {"data":{"dataStructures":[{"annotations":[{"id":"origin","value":"INTEGRATION","text":"existing"}]}]}}
                """;

        JsonNode annotation = applyToTree(input).path("data").path("dataStructures").get(0).path("annotations").get(0);
        assertThat(annotation.path("value").asText()).isEqualTo("INTEGRATION");
        assertThat(annotation.path("text").asText()).isEqualTo("existing");
    }

    @Test
    @SneakyThrows
    void leavesAnnotationUntouchedWhenTextsAlreadyPresent() {
        String input = """
                {"data":{"dataStructures":[{"annotations":[{"id":"x","value":"V","texts":{"en":"hello"}}]}]}}
                """;

        JsonNode annotation = applyToTree(input).path("data").path("dataStructures").get(0).path("annotations").get(0);
        assertThat(annotation.path("value").asText()).isEqualTo("V");
        assertThat(annotation.path("texts").path("en").asText()).isEqualTo("hello");
        assertThat(annotation.has("text")).isFalse();
    }

    @Test
    @SneakyThrows
    void rewritesNestedAnnotationsAcrossArtefacts() {
        String input = """
                {"data":{
                  "dataStructures":[{"annotations":[{"id":"a","value":"A"}],
                                     "dataStructureComponents":{
                                       "attributeList":{"annotations":[{"id":"b","value":"B"}]}
                                     }}],
                  "codelists":[{"annotations":[{"id":"c","value":"C"}]}]
                }}
                """;

        JsonNode root = applyToTree(input);
        assertThat(root.at("/data/dataStructures/0/annotations/0/text").asText()).isEqualTo("A");
        assertThat(root.at("/data/dataStructures/0/dataStructureComponents/attributeList/annotations/0/text").asText()).isEqualTo("B");
        assertThat(root.at("/data/codelists/0/annotations/0/text").asText()).isEqualTo("C");
    }

    @Test
    @SneakyThrows
    void leavesAnnotationsWithoutValueAlone() {
        String input = """
                {"data":{"dataStructures":[{"annotations":[{"id":"isFinal"}]}]}}
                """;

        JsonNode annotation = applyToTree(input).path("data").path("dataStructures").get(0).path("annotations").get(0);
        assertThat(annotation.has("text")).isFalse();
        assertThat(annotation.path("id").asText()).isEqualTo("isFinal");
    }

    @SneakyThrows
    private JsonNode applyToTree(String json) {
        InputStream in = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
        InputStream out = fixture.apply(in, Collections.emptyMap());
        return mapper.readTree(new String(out.readAllBytes(), StandardCharsets.UTF_8));
    }
}
