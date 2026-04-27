package com.epam.sdmxproxy.services.limit.truncate;

import com.epam.sdmxproxy.services.fixture.data.StreamingFixtureIO;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;
import io.sdmx.api.sdmx.model.beans.datastructure.DataStructureBean;
import io.sdmx.api.sdmx.model.beans.datastructure.DimensionBean;
import io.sdmx.api.sdmx.model.beans.datastructure.DimensionListBean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CsvSeriesLimitTruncatorTest {

    private CsvSeriesLimitTruncator truncator;

    @BeforeEach
    void setUp() {
        truncator = new CsvSeriesLimitTruncator(new StreamingFixtureIO());
    }

    @Test
    void truncate_emitsFirstNDistinctKeys_multipleRowsPerKey() throws Exception {
        SdmxBeans beans = beansWithDimensions("FREQ", "COUNTRY", "INDICATOR", "TIME_PERIOD");

        String csv = """
                DATAFLOW,FREQ,COUNTRY,INDICATOR,TIME_PERIOD,OBS_VALUE
                BIS:WS/1.0,A,UK,I1,2020,1.0
                BIS:WS/1.0,A,UK,I1,2021,1.1
                BIS:WS/1.0,A,PL,I1,2020,2.0
                BIS:WS/1.0,A,JP,I1,2020,3.0
                BIS:WS/1.0,D,UK,I1,2020,4.0
                """;

        byte[] out;
        try (InputStream in = truncator.truncate(toStream(csv), 2, beans)) {
            out = in.readAllBytes();
        }
        String output = new String(out, StandardCharsets.UTF_8);
        String[] lines = output.split("\n");
        assertThat(lines[0]).contains("DATAFLOW").contains("FREQ").contains("OBS_VALUE");
        // First 2 distinct (FREQ,COUNTRY,INDICATOR) keys kept: A.UK.I1 (2 rows) + A.PL.I1 (1 row)
        long dataRows = output.lines().skip(1).filter(s -> !s.isBlank()).count();
        assertThat(dataRows).isEqualTo(3);
    }

    @Test
    void truncate_undershootEmitsAll() throws Exception {
        SdmxBeans beans = beansWithDimensions("FREQ", "TIME_PERIOD");

        String csv = """
                DATAFLOW,FREQ,TIME_PERIOD,OBS_VALUE
                BIS:WS/1.0,A,2020,1.0
                BIS:WS/1.0,M,2020,2.0
                """;

        byte[] out;
        try (InputStream in = truncator.truncate(toStream(csv), 10, beans)) {
            out = in.readAllBytes();
        }
        long dataRows = new String(out, StandardCharsets.UTF_8).lines().skip(1).filter(s -> !s.isBlank()).count();
        assertThat(dataRows).isEqualTo(2);
    }

    @Test
    void emptyStream_emitsValidHeader() throws Exception {
        SdmxBeans beans = beansWithDimensions("FREQ", "COUNTRY", "TIME_PERIOD");
        try (InputStream in = truncator.emptyStream(beans)) {
            String output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(output).contains("DATAFLOW").contains("FREQ").contains("COUNTRY").contains("TIME_PERIOD");
            long dataRows = output.lines().skip(1).filter(s -> !s.isBlank()).count();
            assertThat(dataRows).isZero();
        }
    }

    @Test
    void emptyStream_handlesNullBeans() throws Exception {
        try (InputStream in = truncator.emptyStream(null)) {
            byte[] out = in.readAllBytes();
            assertThat(out.length).isGreaterThan(0);
            String output = new String(out, StandardCharsets.UTF_8);
            assertThat(output).contains("DATAFLOW");
        }
    }

    private static SdmxBeans beansWithDimensions(String... ids) {
        SdmxBeans beans = mock(SdmxBeans.class);
        DataStructureBean dsd = mock(DataStructureBean.class);
        DimensionListBean dimList = mock(DimensionListBean.class);
        when(beans.getDataStructures()).thenReturn(Set.of(dsd));
        when(dsd.getDimensionList()).thenReturn(dimList);
        List<DimensionBean> dims = new java.util.ArrayList<>();
        for (String id : ids) {
            DimensionBean d = mock(DimensionBean.class);
            when(d.getId()).thenReturn(id);
            when(d.isTimeDimension()).thenReturn("TIME_PERIOD".equals(id));
            dims.add(d);
        }
        when(dimList.getDimensions()).thenReturn(dims);
        return beans;
    }

    private static InputStream toStream(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }
}
