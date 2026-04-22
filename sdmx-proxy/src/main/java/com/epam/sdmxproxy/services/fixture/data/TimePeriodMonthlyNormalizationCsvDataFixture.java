package com.epam.sdmxproxy.services.fixture.data;

import com.epam.sdmxproxy.configuration.data.ReturnFormat;
import com.epam.sdmxproxy.configuration.data.fixture.DataFixtureType;
import com.opencsv.RFC4180Parser;
import com.opencsv.RFC4180ParserBuilder;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalizes monthly {@code TIME_PERIOD} values in SDMX-CSV 1.0.0 and 2.0.0 data responses.
 * <p>
 * In SDMX-CSV, {@code TIME_PERIOD} is a column in each data row:
 * <pre>
 * FREQ,REF_AREA,TIME_PERIOD,OBS_VALUE
 * M,US,2024-03,5.25
 * </pre>
 * The fixture finds the {@code TIME_PERIOD} column by header name and rewrites monthly
 * values in that column to canonical {@code YYYY-Mmm}.
 * <p>
 * Streaming end-to-end: reads line by line, parses each line with OpenCSV's
 * {@link RFC4180Parser} (the same parser sdmx-core uses for CSV input), and emits rewritten
 * rows with minimal quoting. Rows that do not need rewriting pass through verbatim.
 * <p>
 * Limitation: values spanning multiple physical lines via quoted newlines are not handled
 * correctly. SDMX-CSV TIME_PERIOD values are simple scalar strings (e.g. {@code 2024-03})
 * so this limitation does not affect the fixture's correctness for its target column.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TimePeriodMonthlyNormalizationCsvDataFixture implements DataFixture {

    private static final Pattern MONTHLY = Pattern.compile("^(\\d{4})-(\\d{2})$");
    private static final String TIME_PERIOD = "TIME_PERIOD";
    private static final String LINE_SEPARATOR = "\n";

    private final StreamingFixtureIO streamingFixtureIO;

    @Override
    public DataFixtureType getType() {
        return DataFixtureType.TIME_PERIOD_MONTHLY_NORMALIZATION;
    }

    @Override
    public Set<ReturnFormat> supportedFormats() {
        return Set.of(ReturnFormat.CSV_DATA_1_0_0, ReturnFormat.CSV_DATA_2_0_0);
    }

    @Override
    public InputStream apply(InputStream input, SdmxBeans sdmxBeans, Map<String, String> config) {
        return streamingFixtureIO.runOnVirtualThread(input, "data-fixture-monthly-time-period-csv", (in, out) -> {
            RFC4180Parser parser = new RFC4180ParserBuilder().build();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                 BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8))) {
                transform(reader, writer, parser);
            }
        });
    }

    private void transform(BufferedReader reader, BufferedWriter writer, RFC4180Parser parser) throws IOException {
        String headerLine = reader.readLine();
        if (headerLine == null) {
            return;
        }
        writer.write(headerLine);
        writer.write(LINE_SEPARATOR);

        int timePeriodIdx = findColumnIndex(parser.parseLine(headerLine), TIME_PERIOD);
        if (timePeriodIdx < 0) {
            passThrough(reader, writer);
            return;
        }

        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                writer.write(LINE_SEPARATOR);
                continue;
            }
            String rewritten = maybeRewriteLine(line, parser, timePeriodIdx);
            writer.write(rewritten);
            writer.write(LINE_SEPARATOR);
        }
    }

    private String maybeRewriteLine(String line, RFC4180Parser parser, int timePeriodIdx) throws IOException {
        String[] fields = parser.parseLine(line);
        if (timePeriodIdx >= fields.length) {
            return line;
        }
        String currentValue = fields[timePeriodIdx];
        if (currentValue == null) {
            return line;
        }
        Matcher matcher = MONTHLY.matcher(currentValue);
        if (!matcher.matches()) {
            return line;
        }
        fields[timePeriodIdx] = matcher.group(1) + "-M" + matcher.group(2);
        return writeCsvLine(fields);
    }

    private int findColumnIndex(String[] header, String name) {
        for (int i = 0; i < header.length; i++) {
            if (name.equals(header[i])) {
                return i;
            }
        }
        return -1;
    }

    private void passThrough(BufferedReader reader, Writer writer) throws IOException {
        char[] buf = new char[8192];
        int n;
        while ((n = reader.read(buf)) >= 0) {
            writer.write(buf, 0, n);
        }
    }

    /**
     * Joins CSV fields with commas, applying RFC 4180 quoting only to fields that contain
     * commas, quotes, or line separators.
     */
    private String writeCsvLine(String[] fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            String f = fields[i];
            if (f == null) {
                continue;
            }
            if (needsQuoting(f)) {
                sb.append('"').append(f.replace("\"", "\"\"")).append('"');
            } else {
                sb.append(f);
            }
        }
        return sb.toString();
    }

    private boolean needsQuoting(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ',' || c == '"' || c == '\n' || c == '\r') {
                return true;
            }
        }
        return false;
    }
}
