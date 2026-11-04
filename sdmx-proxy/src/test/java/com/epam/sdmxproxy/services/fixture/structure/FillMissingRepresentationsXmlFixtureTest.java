package com.epam.sdmxproxy.services.fixture.structure;

import com.epam.sdmxproxy.services.adapter.statcan.StatCanBundleRenamer;
import com.epam.sdmxproxy.services.adapter.statcan.StatCanIdentifiers;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class FillMissingRepresentationsXmlFixtureTest {

    private static final String HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><mes:Structure xmlns:mes=\"http://www.sdmx.org/resources/sdmxml/schemas/v2_1/message\" xmlns:str=\"http://www.sdmx.org/resources/sdmxml/schemas/v2_1/structure\" xmlns:com=\"http://www.sdmx.org/resources/sdmxml/schemas/v2_1/common\"><mes:Structures>";

    private static final String TAIL = "</mes:Structures></mes:Structure>";

    private final FillMissingRepresentationsXmlFixture fixture = new FillMissingRepresentationsXmlFixture();

    @Test
    void shouldGiveObsValueStringRepresentation() {
        String fixed = apply(renamedBundle());

        assertThat(element(fixed, "str:PrimaryMeasure id=\"OBS_VALUE\"", "str:PrimaryMeasure"))
                .contains("<str:LocalRepresentation><str:TextFormat textType=\"String\"");
        assertThat(concept(fixed, "OBS_VALUE")).contains("<str:CoreRepresentation><str:TextFormat textType=\"String\"");
    }

    @Test
    void shouldCopyEnumerationToConcept() {
        String fixed = apply(renamedBundle());

        assertThat(concept(fixed, "Geography"))
                .contains("<str:CoreRepresentation><str:Enumeration><Ref id=\"CL_17100005_Geography\" version=\"1.0\" agencyID=\"StatCan\" package=\"codelist\" class=\"Codelist\"");
    }

    @Test
    void shouldCopyTextFormatToConcept() {
        String fixed = apply(renamedBundle());

        assertThat(element(fixed, "str:Attribute id=\"VECTOR_ID\"", "str:Attribute")).contains("<str:TextFormat textType=\"String\"");
        assertThat(concept(fixed, "VECTOR_ID")).contains("<str:CoreRepresentation><str:TextFormat textType=\"String\"");
    }

    @Test
    void shouldGiveTimeDimensionObservationalTimePeriod() {
        String fixed = apply(renamedBundle());

        assertThat(element(fixed, "str:TimeDimension id=\"TIME_PERIOD\"", "str:TimeDimension")).contains("<str:TextFormat textType=\"ObservationalTimePeriod\"");
        assertThat(concept(fixed, "TIME_PERIOD")).contains("<str:CoreRepresentation><str:TextFormat textType=\"ObservationalTimePeriod\"");
    }

    @Test
    void shouldGiveEveryConceptACoreRepresentation() {
        String fixed = apply(renamedBundle());

        Matcher concepts = Pattern.compile("<str:Concept id=\"([^\"]+)\".*?</str:Concept>", Pattern.DOTALL).matcher(fixed);
        int count = 0;
        while (concepts.find()) {
            count++;
            assertThat(concepts.group()).as("concept %s", concepts.group(1)).contains("<str:CoreRepresentation>");
        }
        assertThat(count).isEqualTo(14);
    }

    @Test
    void shouldBeIdempotent() {
        byte[] once = applyBytes(renamedBundle());
        byte[] twice = applyBytes(once);

        assertThat(twice).isEqualTo(once);
    }

    @Test
    void shouldKeepExistingRepresentations() {
        String document = HEAD + conceptScheme("<str:Concept id=\"C\"><com:Name xml:lang=\"en\">C</com:Name><str:CoreRepresentation><str:TextFormat textType=\"Double\"/></str:CoreRepresentation></str:Concept>")
                + dsd("<str:Dimension id=\"C\" position=\"1\">" + identity("C") + "<str:LocalRepresentation><str:TextFormat textType=\"Integer\"/></str:LocalRepresentation></str:Dimension>") + TAIL;

        assertThat(applyBytes(bytes(document))).isEqualTo(bytes(document));
    }

    @Test
    void shouldLetComponentInheritConceptRepresentation() {
        String document = HEAD + conceptScheme("<str:Concept id=\"OBS_VALUE\"><com:Name xml:lang=\"en\">Value</com:Name><str:CoreRepresentation><str:TextFormat textType=\"Double\"/></str:CoreRepresentation></str:Concept>")
                + dsd("<str:MeasureList id=\"MeasureDescriptor\"><str:PrimaryMeasure id=\"OBS_VALUE\">" + identity("OBS_VALUE") + "</str:PrimaryMeasure></str:MeasureList>", false) + TAIL;

        assertThat(applyBytes(bytes(document))).isEqualTo(bytes(document));
    }

    @Test
    void shouldCopyFirstRepresentationOnConflict() {
        String document = HEAD + conceptScheme("<str:Concept id=\"C\"><com:Name xml:lang=\"en\">C</com:Name></str:Concept>")
                + dsd("<str:Dimension id=\"D1\" position=\"1\">" + identity("C") + "<str:LocalRepresentation><str:TextFormat textType=\"Integer\"/></str:LocalRepresentation></str:Dimension>"
                + "<str:Dimension id=\"D2\" position=\"2\">" + identity("C") + "<str:LocalRepresentation><str:TextFormat textType=\"Double\"/></str:LocalRepresentation></str:Dimension>") + TAIL;

        String fixed = apply(bytes(document));

        assertThat(concept(fixed, "C")).contains("<str:CoreRepresentation><str:TextFormat textType=\"Integer\"").doesNotContain("Double");
    }

    @Test
    void shouldPreserveChildOrder() {
        String document = HEAD + conceptScheme("<str:Concept id=\"C\"><com:Name xml:lang=\"en\">C</com:Name><str:ISOConceptReference><str:ConceptAgency>X</str:ConceptAgency><str:ConceptSchemeID>Y</str:ConceptSchemeID><str:ConceptID>Z</str:ConceptID></str:ISOConceptReference></str:Concept>")
                + dsd("<str:Attribute id=\"A\" assignmentStatus=\"Conditional\">" + identity("C") + "<str:AttributeRelationship><str:None/></str:AttributeRelationship></str:Attribute>", true, "AttributeList", "AttributeDescriptor") + TAIL;

        String fixed = apply(bytes(document));

        String concept = concept(fixed, "C");
        assertThat(concept.indexOf("<str:CoreRepresentation>")).isGreaterThan(concept.indexOf("</com:Name>")).isLessThan(concept.indexOf("<str:ISOConceptReference>"));
        String attribute = element(fixed, "str:Attribute id=\"A\"", "str:Attribute");
        assertThat(attribute.indexOf("<str:LocalRepresentation>")).isGreaterThan(attribute.indexOf("</str:ConceptIdentity>")).isLessThan(attribute.indexOf("<str:AttributeRelationship>"));
    }

    @Test
    void shouldLeaveDsdAloneWithoutConcepts() {
        String document = HEAD + dsd("<str:Dimension id=\"D\" position=\"1\">" + identity("C") + "</str:Dimension>") + TAIL;

        assertThat(applyBytes(bytes(document))).isEqualTo(bytes(document));
    }

    @Test
    void shouldLeaveConceptSchemeAloneWithoutDsd() {
        String document = HEAD + conceptScheme("<str:Concept id=\"C\"><com:Name xml:lang=\"en\">C</com:Name></str:Concept>") + TAIL;

        assertThat(applyBytes(bytes(document))).isEqualTo(bytes(document));
    }

    @Test
    @SneakyThrows
    void shouldAcceptNullConfig() {
        try (InputStream fixed = fixture.apply(new ByteArrayInputStream(renamedBundle()), null)) {
            assertThat(fixed.readAllBytes()).isNotEmpty();
        }
    }

    private static String conceptScheme(String concepts) {
        return "<str:Concepts><str:ConceptScheme id=\"CS\" agencyID=\"AG\" version=\"1.0\"><com:Name xml:lang=\"en\">CS</com:Name>" + concepts + "</str:ConceptScheme></str:Concepts>";
    }

    private static String dsd(String dimensions) {
        return dsd(dimensions, true);
    }

    private static String dsd(String components, boolean dimensionList) {
        return dimensionList ? dsd(components, true, "DimensionList", "DimensionDescriptor") : dsd(components, false, null, null);
    }

    private static String dsd(String components, boolean wrap, String list, String listId) {
        String body = wrap ? "<str:" + list + " id=\"" + listId + "\">" + components + "</str:" + list + ">" : components;
        return "<str:DataStructures><str:DataStructure id=\"DSD\" agencyID=\"AG\" version=\"1.0\"><com:Name xml:lang=\"en\">DSD</com:Name><str:DataStructureComponents>" + body + "</str:DataStructureComponents></str:DataStructure></str:DataStructures>";
    }

    private static String identity(String concept) {
        return "<str:ConceptIdentity><Ref id=\"" + concept + "\" maintainableParentID=\"CS\" maintainableParentVersion=\"1.0\" agencyID=\"AG\" package=\"conceptscheme\" class=\"Concept\"/></str:ConceptIdentity>";
    }

    private static String concept(String document, String id) {
        return element(document, "str:Concept id=\"" + id + "\"", "str:Concept");
    }

    private static String element(String document, String startTag, String endTag) {
        int start = document.indexOf("<" + startTag);
        assertThat(start).as("element <%s> in output", startTag).isGreaterThanOrEqualTo(0);
        int end = document.indexOf("</" + endTag + ">", start);
        return document.substring(start, end);
    }

    private static byte[] bytes(String document) {
        return document.getBytes(StandardCharsets.UTF_8);
    }

    private String apply(byte[] input) {
        return new String(applyBytes(input), StandardCharsets.UTF_8).replaceAll(">\\s+<", "><");
    }

    @SneakyThrows
    private byte[] applyBytes(byte[] input) {
        try (InputStream fixed = fixture.apply(new ByteArrayInputStream(input), Map.of())) {
            return fixed.readAllBytes();
        }
    }

    @SneakyThrows
    private byte[] renamedBundle() {
        byte[] raw = StatCanBundleRenamer.class.getResourceAsStream("Data_Structure_17100005.xml").readAllBytes();
        return new StatCanBundleRenamer(new StatCanIdentifiers()).rename(raw, "17100005");
    }
}
