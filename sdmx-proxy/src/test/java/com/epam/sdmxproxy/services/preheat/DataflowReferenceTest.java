package com.epam.sdmxproxy.services.preheat;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataflowReferenceTest {

    @Test
    void shouldParseStatGptAdminReference() {
        DataflowReference ref = DataflowReference.parse("OECD.SDD.TPS:DSD_BATIS@DF_BATIS(latest)");

        assertThat(ref.agency()).isEqualTo("OECD.SDD.TPS");
        assertThat(ref.id()).isEqualTo("DSD_BATIS@DF_BATIS");
        assertThat(ref.version()).isEqualTo("latest");
        assertThat(ref).hasToString("OECD.SDD.TPS:DSD_BATIS@DF_BATIS(latest)");
    }

    @Test
    void shouldRejectMissingVersion() {
        assertThatThrownBy(() -> DataflowReference.parse("OECD:DF_X")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectExtraColon() {
        assertThatThrownBy(() -> DataflowReference.parse("OECD:SUB:DF_X(1.0)")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNull() {
        assertThatThrownBy(() -> DataflowReference.parse(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
