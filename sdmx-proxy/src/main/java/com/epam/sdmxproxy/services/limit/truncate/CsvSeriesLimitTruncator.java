package com.epam.sdmxproxy.services.limit.truncate;

import com.epam.sdmxproxy.configuration.data.SdmxFormat;
import com.epam.sdmxproxy.services.fixture.data.StreamingFixtureIO;
import com.opencsv.RFC4180Parser;
import com.opencsv.RFC4180ParserBuilder;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;
import io.sdmx.api.sdmx.model.beans.base.IdentifiableBean;
import io.sdmx.api.sdmx.model.beans.datastructure.DataStructureBean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Streaming truncator for SDMX-CSV 2.0.0 data responses. Parses the header row to locate
 * dimension columns by name (DSD-driven, excluding the time dimension); for each data row
 * builds a series key from those columns; keeps rows whose key is among the first {@code
 * N} distinct keys seen, drops the rest.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CsvSeriesLimitTruncator implements SeriesLimitTruncator {

    private static final String LINE_SEPARATOR = "\n";
    private static final char SEPARATOR = ',';

    private final StreamingFixtureIO streamingFixtureIO;

    @Override
    public Set<SdmxFormat> supportedFormats() {
        return Set.of(SdmxFormat.CSV_DATA_2_0_0);
    }

    @Override
    public InputStream truncate(InputStream rawData, int n, SdmxBeans sdmxBeans) {
        Set<String> nonTimeDimensionIds = nonTimeDimensionIds(sdmxBeans);
        return streamingFixtureIO.runOnVirtualThread(
                rawData,
                "limit-truncate-csv",
                (in, out) -> {
                    RFC4180Parser parser = new RFC4180ParserBuilder().build();
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                         BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8))) {
                        transform(reader, writer, parser, n, nonTimeDimensionIds);
                    }
                }
        );
    }

    @Override
    public InputStream emptyStream(SdmxBeans sdmxBeans) {
        List<String> dimensionIds = orderedDimensionIds(sdmxBeans);
        StringBuilder header = new StringBuilder("DATAFLOW");
        for (String id : dimensionIds) {
            header.append(SEPARATOR).append(id);
        }
        header.append(SEPARATOR).append("OBS_VALUE").append(LINE_SEPARATOR);
        return new ByteArrayInputStream(header.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void transform(
            BufferedReader reader,
            BufferedWriter writer,
            RFC4180Parser parser,
            int n,
            Set<String> nonTimeDimensionIds
    ) throws IOException {
        String headerLine = reader.readLine();
        if (headerLine == null) {
            return;
        }
        writer.write(headerLine);
        writer.write(LINE_SEPARATOR);

        int[] dimensionColumnIndices = findDimensionColumnIndices(
                parser.parseLine(headerLine), nonTimeDimensionIds);
        if (dimensionColumnIndices.length == 0) {
            // No DSD-matched columns -- cannot truncate reliably. Fall through: emit all rows.
            passThrough(reader, writer);
            return;
        }

        Set<String> keptKeys = new HashSet<>();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                writer.write(LINE_SEPARATOR);
                continue;
            }
            String[] fields = parser.parseLine(line);
            String seriesKey = buildSeriesKey(fields, dimensionColumnIndices);
            if (keptKeys.contains(seriesKey)) {
                writer.write(line);
                writer.write(LINE_SEPARATOR);
            } else if (keptKeys.size() < n) {
                keptKeys.add(seriesKey);
                writer.write(line);
                writer.write(LINE_SEPARATOR);
            }
            // else: skip row, key not in kept set
        }
    }

    private static int[] findDimensionColumnIndices(String[] header, Set<String> nonTimeDimensionIds) {
        List<Integer> indices = new ArrayList<>(nonTimeDimensionIds.size());
        for (int i = 0; i < header.length; i++) {
            if (nonTimeDimensionIds.contains(header[i])) {
                indices.add(i);
            }
        }
        int[] result = new int[indices.size()];
        for (int i = 0; i < indices.size(); i++) {
            result[i] = indices.get(i);
        }
        return result;
    }

    private static String buildSeriesKey(String[] fields, int[] dimensionColumnIndices) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < dimensionColumnIndices.length; i++) {
            if (i > 0) {
                sb.append('.');
            }
            int idx = dimensionColumnIndices[i];
            if (idx < fields.length) {
                String v = fields[idx];
                if (v != null) {
                    sb.append(v);
                }
            }
        }
        return sb.toString();
    }

    private static Set<String> nonTimeDimensionIds(SdmxBeans sdmxBeans) {
        DataStructureBean dsd = firstDsdOrNull(sdmxBeans);
        if (dsd == null) {
            return Set.of();
        }
        return dsd.getDimensionList().getDimensions().stream()
                .filter(d -> !d.isTimeDimension())
                .map(IdentifiableBean::getId)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    private static List<String> orderedDimensionIds(SdmxBeans sdmxBeans) {
        DataStructureBean dsd = firstDsdOrNull(sdmxBeans);
        if (dsd == null) {
            return List.of();
        }
        return dsd.getDimensionList().getDimensions().stream()
                .map(IdentifiableBean::getId)
                .toList();
    }

    private static DataStructureBean firstDsdOrNull(SdmxBeans sdmxBeans) {
        if (sdmxBeans == null || sdmxBeans.getDataStructures() == null) {
            return null;
        }
        return sdmxBeans.getDataStructures().stream().findFirst().orElse(null);
    }

    private static void passThrough(BufferedReader reader, BufferedWriter writer) throws IOException {
        char[] buf = new char[8192];
        int read;
        while ((read = reader.read(buf)) >= 0) {
            writer.write(buf, 0, read);
        }
    }
}
