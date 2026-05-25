package com.epam.sdmxproxy.services.fixture;

import com.epam.sdmxproxy.configuration.data.fixture.FixtureConfiguration;
import com.epam.sdmxproxy.configuration.data.fixture.StructureFixtureType;
import com.epam.sdmxproxy.services.fixture.structure.MetadataAttributeUsagePreserver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MetadataAttributeUsagePreserverTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final MetadataAttributeUsagePreserver sut = new MetadataAttributeUsagePreserver(mapper);

    @Test
    void isEnabledTrueWhenPreserveMarkerInList() {
        FixtureConfiguration<StructureFixtureType> fc = new FixtureConfiguration<>();
        fc.setType(StructureFixtureType.PRESERVE_METADATA_ATTRIBUTE_USAGES);
        fc.setConfig(new HashMap<>());
        assertThat(sut.isEnabled(List.of(fc))).isTrue();
    }

    @Test
    void isEnabledFalseWhenMarkerAbsent() {
        FixtureConfiguration<StructureFixtureType> fc = new FixtureConfiguration<>();
        fc.setType(StructureFixtureType.VERSION_WILDCARD);
        fc.setConfig(new HashMap<>());
        assertThat(sut.isEnabled(List.of(fc))).isFalse();
    }

    @Test
    void isEnabledFalseWhenNull() {
        assertThat(sut.isEnabled(null)).isFalse();
    }

    @Test
    @SneakyThrows
    void capturesUsagesKeyedByAgencyIdVersion() {
        byte[] raw = """
                {"data":{"dataStructures":[{
                  "agencyID":"IMF.RES","id":"DSD_WEO","version":"9.0.0",
                  "dataStructureComponents":{
                    "attributeList":{"metadataAttributeUsages":[
                      {"metadataAttributeReference":"DOI"},
                      {"metadataAttributeReference":"AUTHOR"}
                    ]}
                  }
                }]}}
                """.getBytes(StandardCharsets.UTF_8);

        Map<String, JsonNode> captured = sut.capture(raw);

        assertThat(captured).hasSize(1).containsKey("IMF.RES|DSD_WEO|9.0.0");
        JsonNode arr = captured.get("IMF.RES|DSD_WEO|9.0.0");
        assertThat(arr.isArray()).isTrue();
        assertThat(arr.size()).isEqualTo(2);
        assertThat(arr.get(0).path("metadataAttributeReference").asText()).isEqualTo("DOI");
    }

    @Test
    @SneakyThrows
    void capturesNothingWhenUsagesAbsent() {
        byte[] raw = """
                {"data":{"dataStructures":[{
                  "agencyID":"IMF.RES","id":"DSD_X","version":"1.0.0",
                  "dataStructureComponents":{"attributeList":{}}
                }]}}
                """.getBytes(StandardCharsets.UTF_8);

        assertThat(sut.capture(raw)).isEmpty();
    }

    @Test
    @SneakyThrows
    void injectsUsagesOntoMatchingDsd() {
        byte[] converted = """
                {"data":{"dataStructures":[{
                  "agencyID":"IMF.RES","id":"DSD_WEO","version":"9.0.0",
                  "dataStructureComponents":{
                    "attributeList":{"metadataAttributeUsages":[]}
                  }
                }]}}
                """.getBytes(StandardCharsets.UTF_8);
        JsonNode usages = mapper.readTree("""
                [{"metadataAttributeReference":"DOI"},
                 {"metadataAttributeReference":"AUTHOR"}]
                """);

        byte[] result = sut.inject(converted, Map.of("IMF.RES|DSD_WEO|9.0.0", usages));

        JsonNode root = mapper.readTree(result);
        JsonNode injected = root.at("/data/dataStructures/0/dataStructureComponents/attributeList/metadataAttributeUsages");
        assertThat(injected.size()).isEqualTo(2);
        assertThat(injected.get(0).path("metadataAttributeReference").asText()).isEqualTo("DOI");
    }

    @Test
    @SneakyThrows
    void leavesNonEmptyExistingUsagesIntact() {
        byte[] converted = """
                {"data":{"dataStructures":[{
                  "agencyID":"IMF.RES","id":"DSD_WEO","version":"9.0.0",
                  "dataStructureComponents":{
                    "attributeList":{"metadataAttributeUsages":[{"metadataAttributeReference":"PREEXISTING"}]}
                  }
                }]}}
                """.getBytes(StandardCharsets.UTF_8);
        JsonNode usages = mapper.readTree("""
                [{"metadataAttributeReference":"NEW"}]
                """);

        byte[] result = sut.inject(converted, Map.of("IMF.RES|DSD_WEO|9.0.0", usages));

        JsonNode root = mapper.readTree(result);
        JsonNode keptUsages = root.at("/data/dataStructures/0/dataStructureComponents/attributeList/metadataAttributeUsages");
        assertThat(keptUsages.size()).isEqualTo(1);
        assertThat(keptUsages.get(0).path("metadataAttributeReference").asText()).isEqualTo("PREEXISTING");
    }

    @Test
    void injectReturnsUnchangedWhenNoMatch() {
        byte[] converted = """
                {"data":{"dataStructures":[{
                  "agencyID":"OTHER","id":"X","version":"1.0.0"
                }]}}
                """.getBytes(StandardCharsets.UTF_8);
        JsonNode usages = mapper.createArrayNode().add(mapper.createObjectNode().put("metadataAttributeReference", "DOI"));

        byte[] result = sut.inject(converted, Map.of("IMF.RES|DSD_WEO|9.0.0", usages));

        assertThat(result).isEqualTo(converted);
    }
}
