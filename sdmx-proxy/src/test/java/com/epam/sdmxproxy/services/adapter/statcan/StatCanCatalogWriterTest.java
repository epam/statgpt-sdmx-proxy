package com.epam.sdmxproxy.services.adapter.statcan;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class StatCanCatalogWriterTest {

    private static final Pattern DATAFLOW = Pattern.compile("<str:Dataflow ");

    private final StatCanCatalogWriter writer = new StatCanCatalogWriter(new StatCanIdentifiers());

    @Test
    void shouldWriteCurrentTablesOnly() {
        String catalog = write(false);

        assertThat(catalog).contains("id=\"DF_17100005\"").contains("id=\"DF_18100004\"");
        // 10100001 is archived ("1"): reachable by ID, not listed.
        assertThat(catalog).doesNotContain("id=\"DF_10100001\"");
        assertThat(count(catalog)).isEqualTo(12);
    }

    @Test
    void shouldIncludeArchivedWhenConfigured() {
        String catalog = write(true);

        assertThat(catalog).contains("id=\"DF_10100001\"");
        assertThat(count(catalog)).isEqualTo(20);
    }

    @Test
    void shouldWriteEnglishAndFrenchNames() {
        String catalog = write(false);

        assertThat(catalog).contains("<com:Name xml:lang=\"en\">Population estimates on July 1, by age and gender</com:Name>");
        // WDS itself serves "par �ge" for this title; the writer copies it verbatim.
        assertThat(catalog).contains("xml:lang=\"fr\">Estimations de la population au 1er juillet, par ");
    }

    @Test
    void shouldReferenceTheTableDsd() {
        String catalog = write(false);

        assertThat(catalog).contains("urn=\"urn:sdmx:org.sdmx.infomodel.datastructure.Dataflow=StatCan:DF_17100005(1.0)\"");
        assertThat(catalog).contains("<Ref id=\"Data_Structure_17100005\" version=\"1.0\" agencyID=\"StatCan\" package=\"datastructure\" class=\"DataStructure\"");
    }

    @Test
    void shouldEscapeTitles() {
        byte[] json = "[{\"productId\":12345678,\"cubeTitleEn\":\"Prices & <wages>\",\"archived\":\"2\"}]".getBytes(StandardCharsets.UTF_8);

        String catalog = new String(writer.write(new ByteArrayInputStream(json), false), StandardCharsets.UTF_8);

        assertThat(catalog).contains("Prices &amp; &lt;wages");
    }

    private String write(boolean includeArchived) {
        return new String(writer.write(getClass().getResourceAsStream("getAllCubesListLite-sample.json"), includeArchived), StandardCharsets.UTF_8);
    }

    private int count(String catalog) {
        Matcher matcher = DATAFLOW.matcher(catalog);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }
}
