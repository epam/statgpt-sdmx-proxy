package com.epam.sdmxproxy.services.availability.harvest;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.epam.sdmxproxy.configuration.data.SdmxFormat;
import com.epam.sdmxproxy.exception.AvailabilityEmulationException;
import com.epam.sdmxproxy.exception.ResponseTooLargeException;
import com.opencsv.RFC4180Parser;
import com.opencsv.RFC4180ParserBuilder;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Harvests per-dimension coverage from an SDMX-CSV series-key response -- see design 040.
 * <p>
 * CSV is the preferred probe format, and this is why: with the observations dropped the registry
 * also drops the {@code TIME_PERIOD} and {@code OBS_VALUE} columns, so every row is already a
 * distinct series key and the header names the dimensions. No client-side deduplication, no
 * structure lookup to map positions.
 * <pre>
 * DATAFLOW,LAST UPDATE,freq,unit,na_item,geo             (SDMX-CSV 1.0)
 * STRUCTURE,STRUCTURE_ID,freq,unit,na_item,geo           (SDMX-CSV 2.0)
 * </pre>
 * Dimension columns are located by DSD ID rather than by excluding known metadata column names,
 * so a registry adding an envelope column cannot be mistaken for a dimension.
 */
@Slf4j
@Component
public class CsvSeriesKeyHarvester implements SeriesKeyHarvester {

    @Override
    public Set<SdmxFormat> supportedFormats() {
        return Set.of(SdmxFormat.CSV_DATA_1_0_0, SdmxFormat.CSV_DATA_2_0_0);
    }

    @Override
    @SneakyThrows(IOException.class)
    public HarvestedCoverage harvest(InputStream probeResponse, SdmxBeans sdmxBeans, HarvestLimits limits) {
        List<String> dimensionIds = DimensionIds.nonTimeDimensionIds(sdmxBeans);
        InputStream limited = new ByteLimitingInputStream(probeResponse, limits.maxBytes(), "csv probe");
        RFC4180Parser parser = new RFC4180ParserBuilder().build();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(limited, StandardCharsets.UTF_8))) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                // A body with no header at all is not an empty result set, it is a broken
                // response. Reporting empty coverage would read as "nothing is available".
                throw new AvailabilityEmulationException("Probe response carried no CSV header row");
            }
            int[] columnIndexes = resolveColumnIndexes(parser.parseLine(headerLine), dimensionIds);

            Map<String, Set<String>> values = new LinkedHashMap<>();
            for (String dimensionId : dimensionIds) {
                values.put(dimensionId, new LinkedHashSet<>());
            }

            long rows = 0;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                rows++;
                if (rows > limits.maxSeries()) {
                    throw new ResponseTooLargeException(
                            "Availability emulation probe returned more than " + limits.maxSeries()
                                    + " series; narrow the request or raise "
                                    + "availabilityEndpointConfig.emulation.maxProbeSeries");
                }
                String[] row = parser.parseLine(line);
                for (int i = 0; i < dimensionIds.size(); i++) {
                    int column = columnIndexes[i];
                    if (column < row.length) {
                        values.get(dimensionIds.get(i)).add(row[column]);
                    }
                }
            }

            log.info("CSV probe harvested {} series into {}", rows, describe(values));
            return new HarvestedCoverage(values, rows);
        }
    }

    /**
     * Maps each DSD dimension to its column. A dimension with no column is fatal: the emulated
     * response must carry a key value for every coded dimension, and inferring one from a column
     * that is not there would mean reporting a dimension as having no data at all.
     */
    private int[] resolveColumnIndexes(String[] header, List<String> dimensionIds) {
        int[] indexes = new int[dimensionIds.size()];
        for (int i = 0; i < dimensionIds.size(); i++) {
            String dimensionId = dimensionIds.get(i);
            int found = -1;
            for (int column = 0; column < header.length; column++) {
                if (dimensionId.equals(header[column].trim())) {
                    found = column;
                    break;
                }
            }
            if (found < 0) {
                throw new AvailabilityEmulationException(
                        "Probe response has no column for dimension '" + dimensionId
                                + "'; columns were " + String.join(",", header));
            }
            indexes[i] = found;
        }
        return indexes;
    }

    private static String describe(Map<String, Set<String>> values) {
        StringBuilder out = new StringBuilder();
        values.forEach((dim, vals) -> {
            if (!out.isEmpty()) {
                out.append(", ");
            }
            out.append(dim).append('=').append(vals.size());
        });
        return out.toString();
    }
}
