package com.epam.sdmxproxy.services.adapter.statcan;

import com.epam.sdmxproxy.exception.UnexpectedStateException;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StatCanBundleRenamerTest {

    private static final Pattern CODELIST_URN = Pattern.compile("urn=\"(urn:sdmx:org\\.sdmx\\.infomodel\\.codelist\\.Codelist=[^\"]+)\"");

    private final StatCanBundleRenamer renamer = new StatCanBundleRenamer(new StatCanIdentifiers());

    @Test
    void shouldRenameCodelistsAndReferences() {
        String renamed = rename("Data_Structure_17100005.xml", "17100005");

        assertThat(renamed).contains("<str:Codelist id=\"CL_17100005_Geography\"");
        assertThat(renamed).contains("<Ref id=\"CL_17100005_Geography\" version=\"1.0\" agencyID=\"StatCan\" package=\"codelist\" class=\"Codelist\"");
        assertThat(renamed).doesNotContain("id=\"CL_Geography\"");
        assertThat(renamed).doesNotContainPattern("Codelist=StatCan:CL_[A-Za-z]");
    }

    @Test
    void shouldRenameConceptSchemeAndConceptRefs() {
        String renamed = rename("Data_Structure_17100005.xml", "17100005");

        assertThat(renamed).contains("<str:ConceptScheme id=\"CONCEPTS_17100005\"");
        assertThat(renamed).contains("maintainableParentID=\"CONCEPTS_17100005\"");
        assertThat(renamed).doesNotContain("maintainableParentID=\"CONCEPTS\"");
    }

    @Test
    void shouldRewriteUrns() {
        String renamed = rename("Data_Structure_17100005.xml", "17100005");

        assertThat(renamed).contains("urn=\"urn:sdmx:org.sdmx.infomodel.codelist.Codelist=StatCan:CL_17100005_Geography(1.0)\"");
        assertThat(renamed).contains("urn=\"urn:sdmx:org.sdmx.infomodel.codelist.Code=StatCan:CL_17100005_Geography(1.0).");
        assertThat(renamed).contains("urn=\"urn:sdmx:org.sdmx.infomodel.conceptscheme.ConceptScheme=StatCan:CONCEPTS_17100005(1.0)\"");
        assertThat(renamed).contains("urn=\"urn:sdmx:org.sdmx.infomodel.conceptscheme.Concept=StatCan:CONCEPTS_17100005(1.0).Geography\"");
    }

    @Test
    void shouldLeaveDataflowAndDsdIdsAlone() {
        String renamed = rename("Data_Structure_17100005.xml", "17100005");

        assertThat(renamed).contains("<str:Dataflow id=\"DF_17100005\"");
        assertThat(renamed).contains("<str:DataStructure id=\"Data_Structure_17100005\"");
    }

    @Test
    void shouldFailWhenDsdIdDoesNotMatchTable() {
        byte[] bundle = read("Data_Structure_17100005.xml");

        assertThatThrownBy(() -> renamer.rename(bundle, "18100004"))
                .isInstanceOf(UnexpectedStateException.class)
                .hasMessageContaining("Data_Structure_18100004");
    }

    @Test
    void shouldProduceDisjointUrnsForTwoTables() {
        Set<String> first = codelistUrns(rename("Data_Structure_17100005.xml", "17100005"));
        Set<String> second = codelistUrns(rename("Data_Structure_18100004.xml", "18100004"));

        assertThat(first).isNotEmpty();
        assertThat(second).isNotEmpty();
        assertThat(first).doesNotContainAnyElementsOf(second);
    }

    @Test
    void shouldBeIdempotent() {
        byte[] once = renamer.rename(read("Data_Structure_17100005.xml"), "17100005");
        byte[] twice = renamer.rename(once, "17100005");

        assertThat(new String(twice, StandardCharsets.UTF_8)).isEqualTo(new String(once, StandardCharsets.UTF_8));
    }

    private Set<String> codelistUrns(String document) {
        Set<String> urns = new HashSet<>();
        Matcher matcher = CODELIST_URN.matcher(document);
        while (matcher.find()) {
            urns.add(matcher.group(1));
        }
        return urns;
    }

    private String rename(String resource, String table) {
        return new String(renamer.rename(read(resource), table), StandardCharsets.UTF_8);
    }

    @SneakyThrows
    private byte[] read(String resource) {
        return getClass().getResourceAsStream(resource).readAllBytes();
    }
}
