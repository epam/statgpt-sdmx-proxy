package com.epam.sdmxproxy.services.limit.truncate;

import com.epam.sdmxproxy.configuration.data.SdmxFormat;
import com.epam.sdmxproxy.services.fixture.data.StreamingFixtureIO;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Streaming truncator for SDMX-CSV 1.0.0 data responses, the CSV format of SDMX 2.1
 * registries such as OECD. The header opens with a single {@code DATAFLOW} column.
 */
@Component
public class CsvDataV10SeriesLimitTruncator extends CsvSeriesLimitTruncator {

    public CsvDataV10SeriesLimitTruncator(StreamingFixtureIO streamingFixtureIO) {
        super(streamingFixtureIO);
    }

    @Override
    public Set<SdmxFormat> supportedFormats() {
        return Set.of(SdmxFormat.CSV_DATA_1_0_0);
    }

    @Override
    protected List<String> envelopeColumns() {
        return List.of("DATAFLOW");
    }
}
