package com.epam.sdmxproxy.services.adapter.statcan;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StatCanIdentifiersTest {

    private final StatCanIdentifiers identifiers = new StatCanIdentifiers();

    @Test
    void shouldResolveTableFromEachPattern() {
        assertThat(identifiers.tableOf("dataflow", "DF_17100005")).contains("17100005");
        assertThat(identifiers.tableOf("datastructure", "Data_Structure_17100005")).contains("17100005");
        assertThat(identifiers.tableOf("codelist", "CL_17100005_Geography")).contains("17100005");
        assertThat(identifiers.tableOf("conceptscheme", "CONCEPTS_17100005")).contains("17100005");
    }

    @Test
    void shouldRejectBareCodelistId() {
        // Edge case 6: CL_Geography exists in every table with different codes, so it is ambiguous.
        assertThat(identifiers.tableOf("codelist", "CL_Geography")).isEmpty();
        assertThat(identifiers.tableOf("conceptscheme", "CONCEPTS")).isEmpty();
    }

    @Test
    void shouldRejectNonEightDigitTable() {
        assertThat(identifiers.tableOf("dataflow", "DF_1710000")).isEmpty();
        assertThat(identifiers.tableOf("dataflow", "DF_171000050")).isEmpty();
    }

    @Test
    void shouldRejectIdOfAnotherType() {
        assertThat(identifiers.tableOf("codelist", "DF_17100005")).isEmpty();
        assertThat(identifiers.tableOf("hierarchy", "DF_17100005")).isEmpty();
    }

    @Test
    void shouldRenameOnceOnly() {
        assertThat(identifiers.renamedCodelistId("17100005", "CL_Geography")).isEqualTo("CL_17100005_Geography");
        assertThat(identifiers.renamedCodelistId("17100005", "CL_17100005_Geography")).isEqualTo("CL_17100005_Geography");
        assertThat(identifiers.renamedConceptSchemeId("17100005", "CONCEPTS")).isEqualTo("CONCEPTS_17100005");
        assertThat(identifiers.renamedConceptSchemeId("17100005", "CONCEPTS_17100005")).isEqualTo("CONCEPTS_17100005");
    }
}
