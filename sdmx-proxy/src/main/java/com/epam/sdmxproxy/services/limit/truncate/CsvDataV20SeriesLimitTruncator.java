package com.epam.sdmxproxy.services.limit.truncate;

import com.epam.sdmxproxy.configuration.data.SdmxFormat;
import com.epam.sdmxproxy.services.fixture.data.StreamingFixtureIO;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Streaming truncator for SDMX-CSV 2.0.0 data responses. The header opens with the
 * {@code STRUCTURE,STRUCTURE_ID} columns.
 */
@Component
public class CsvDataV20SeriesLimitTruncator extends CsvSeriesLimitTruncator {

    public CsvDataV20SeriesLimitTruncator(StreamingFixtureIO streamingFixtureIO) {
        super(streamingFixtureIO);
    }

    @Override
    public Set<SdmxFormat> supportedFormats() {
        return Set.of(SdmxFormat.CSV_DATA_2_0_0);
    }

    @Override
    protected List<String> envelopeColumns() {
        return List.of("STRUCTURE", "STRUCTURE_ID");
    }
}
