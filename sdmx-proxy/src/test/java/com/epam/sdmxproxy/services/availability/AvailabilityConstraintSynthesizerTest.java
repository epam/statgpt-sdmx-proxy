package com.epam.sdmxproxy.services.availability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.epam.jsdmx.infomodel.sdmx30.Artefacts;
import com.epam.sdmxproxy.common.data.TranslatedAvailabilityQuery;
import com.epam.sdmxproxy.services.adapter.conversion.StreamingAvailabilityConversionService;
import com.epam.sdmxproxy.services.availability.harvest.HarvestedCoverage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;
import io.sdmx.api.sdmx.model.beans.datastructure.DataStructureBean;
import io.sdmx.api.sdmx.model.beans.datastructure.DimensionBean;
import io.sdmx.api.sdmx.model.beans.datastructure.DimensionListBean;
import lombok.SneakyThrows;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * Pins the response schema the consumer requires. Every assertion here corresponds to something
 * that <em>raises</em> in {@code statgpt-backend} rather than degrading, so a change that breaks
 * one of them would surface as an opaque failure far from its cause.
 */
class AvailabilityConstraintSynthesizerTest {

    private static final MediaType JSON_2_0_0 =
            MediaType.parseMediaType("application/vnd.sdmx.structure+json;version=2.0.0");

    private final AvailabilityConstraintSynthesizer synthesizer = new AvailabilityConstraintSynthesizer();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final StreamingAvailabilityConversionService conversionService =
            new StreamingAvailabilityConversionService(objectMapper, null, null, null, null, null);

    @Test
    @DisplayName("renders exactly one data constraint holding exactly one cube region")
    void oneConstraintOneCubeRegion() {
        JsonNode json = render(coverage(Map.of(
                "freq", Set.of("A"),
                "unit", Set.of("CLV05_MEUR"),
                "na_item", Set.of("B1GQ"),
                "geo", Set.of("EL")
        )));

        JsonNode constraints = json.path("data").path("dataConstraints");
        assertEquals(1, constraints.size(), "a second constraint raises in the consumer");
        assertEquals(1, constraints.get(0).path("cubeRegions").size(), "a second cube region raises too");
    }

    @Test
    @DisplayName("emits a key value for every DSD dimension, empty where nothing was observed")
    void keyValueForEveryDimension() {
        // A dimension the probe never saw must come back with an empty value list, not be
        // missing: the consumer raises `Missing dimension(...) value in data content constraint`
        // and takes the dataset offline.
        Map<String, Set<String>> partial = new LinkedHashMap<>();
        partial.put("freq", Set.of("A"));
        partial.put("unit", Set.of());
        partial.put("na_item", Set.of("B1GQ"));
        partial.put("geo", Set.of());

        JsonNode region = render(coverage(partial)).path("data").path("dataConstraints").get(0)
                .path("cubeRegions").get(0);
        JsonNode keyValues = region.path("keyValues");

        assertEquals(4, keyValues.size());
        assertEquals(List.of("freq", "unit", "na_item", "geo"), ids(keyValues), "order follows the DSD");
        assertEquals(0, keyValueFor(keyValues, "unit").path("values").size());
        assertEquals(0, keyValueFor(keyValues, "geo").path("values").size());
    }

    @Test
    @DisplayName("every key value carries the fields the consumer's schema requires")
    void requiredFieldsArePresent() {
        JsonNode constraint = render(coverage(Map.of(
                "freq", Set.of("A"),
                "unit", Set.of("CLV05_MEUR"),
                "na_item", Set.of("B1GQ"),
                "geo", Set.of("EL")
        ))).path("data").path("dataConstraints").get(0);

        // Required on the constraint, values never inspected.
        assertFalse(constraint.path("id").asText().isEmpty());
        assertFalse(constraint.path("version").asText().isEmpty());
        assertFalse(constraint.path("agencyID").asText().isEmpty());
        assertTrue(constraint.has("name"));

        JsonNode region = constraint.path("cubeRegions").get(0);
        assertTrue(region.has("include"), "cubeRegions[].include is required");

        for (JsonNode keyValue : region.path("keyValues")) {
            assertTrue(keyValue.has("id"), "keyValues[].id is required");
            assertTrue(keyValue.has("include"), "keyValues[].include is required");
            // Declared required with no default; omitting it is a pydantic ValidationError.
            assertTrue(keyValue.has("removePrefix"), "keyValues[].removePrefix is required");
            for (JsonNode value : keyValue.path("values")) {
                assertTrue(value.has("value"), "keyValues[].values[].value is required");
            }
        }
    }

    @Test
    @DisplayName("no TIME_PERIOD key value is emitted")
    void noTimePeriodKeyValue() {
        // The consumer cannot represent a time range on this path, and an enumerated list would
        // make the two emulation paths structurally different -- see design 040.
        JsonNode keyValues = render(coverage(Map.of("freq", Set.of("A"), "unit", Set.of("CLV05_MEUR"),
                "na_item", Set.of("B1GQ"), "geo", Set.of("EL"))))
                .path("data").path("dataConstraints").get(0).path("cubeRegions").get(0).path("keyValues");

        assertFalse(ids(keyValues).contains("TIME_PERIOD"));
    }

    @Test
    @DisplayName("a wildcard version slot still yields a parseable artefact version")
    void wildcardVersionIsReplaced() {
        // '*' is legal in a request path and illegal on an artefact.
        JsonNode constraint = render(
                query("*"),
                coverage(Map.of("freq", Set.of("A"), "unit", Set.of("CLV05_MEUR"),
                        "na_item", Set.of("B1GQ"), "geo", Set.of("EL")))
        ).path("data").path("dataConstraints").get(0);

        assertEquals("1.0", constraint.path("version").asText());
    }

    @SneakyThrows
    private JsonNode render(HarvestedCoverage coverage) {
        return render(query("1.0"), coverage);
    }

    @SneakyThrows
    private JsonNode render(TranslatedAvailabilityQuery query, HarvestedCoverage coverage) {
        Artefacts artefacts = synthesizer.synthesize(query, namaBeans(), coverage);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        conversionService.write(artefacts, out, JSON_2_0_0);
        return objectMapper.readTree(out.toByteArray());
    }

    private static HarvestedCoverage coverage(Map<String, Set<String>> values) {
        Map<String, Set<String>> ordered = new LinkedHashMap<>();
        for (String dimensionId : List.of("freq", "unit", "na_item", "geo")) {
            ordered.put(dimensionId, new LinkedHashSet<>(values.getOrDefault(dimensionId, Set.of())));
        }
        return new HarvestedCoverage(ordered, 819L);
    }

    private static TranslatedAvailabilityQuery query(String version) {
        return TranslatedAvailabilityQuery.builder()
                .agencyID("ESTAT")
                .resourceID("NAMA_10_GDP")
                .version(version)
                .contentType(JSON_2_0_0)
                .build();
    }

    private static List<String> ids(JsonNode keyValues) {
        List<String> ids = new ArrayList<>();
        keyValues.forEach(keyValue -> ids.add(keyValue.path("id").asText()));
        return ids;
    }

    private static JsonNode keyValueFor(JsonNode keyValues, String id) {
        for (JsonNode keyValue : keyValues) {
            if (id.equals(keyValue.path("id").asText())) {
                return keyValue;
            }
        }
        throw new AssertionError("No key value for dimension " + id);
    }

    private static SdmxBeans namaBeans() {
        SdmxBeans beans = mock(SdmxBeans.class);
        DataStructureBean dsd = mock(DataStructureBean.class);
        DimensionListBean dimensionList = mock(DimensionListBean.class);
        when(beans.getDataStructures()).thenReturn(Set.of(dsd));
        when(dsd.getDimensionList()).thenReturn(dimensionList);

        List<DimensionBean> dimensions = new ArrayList<>();
        for (String id : List.of("freq", "unit", "na_item", "geo", "TIME_PERIOD")) {
            DimensionBean dimension = mock(DimensionBean.class);
            when(dimension.getId()).thenReturn(id);
            when(dimension.isTimeDimension()).thenReturn("TIME_PERIOD".equals(id));
            dimensions.add(dimension);
        }
        when(dimensionList.getDimensions()).thenReturn(dimensions);
        return beans;
    }
}
