package com.epam.sdmxproxy.services.fixture.data;

import com.epam.sdmxproxy.configuration.data.SdmxFormat;
import com.epam.sdmxproxy.configuration.data.fixture.DataFixtureType;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

/**
 * Repairs the malformed empty result Statistics Canada answers when nothing matches -- see design
 * 043, {@code B7}:
 * <pre>{@code <message:DataSet ...></Series> </message:DataSet>}</pre>
 * ({@code </generic:Series>} in generic data). The stray end tag makes the body malformed XML, so
 * StAX cannot read it and the fixture works on bytes: it buffers from the start up to the tag that
 * follows the dataset start tag and drops that tag when it is an end tag named {@code Series}.
 * Everything else passes through unchanged.
 * <p>
 * The dataset start tag is an element whose local name is exactly {@code DataSet}, with any
 * prefix, followed by whitespace, {@code >}, or {@code />}; the header elements
 * {@code DataSetID} and {@code DataSetAction} do not match. The buffer is capped at
 * {@value #MAX_BUFFER} bytes, which a header never reaches; past the cap the stream passes through
 * untouched, so memory stays bounded as the {@link DataFixture} contract requires.
 */
@Slf4j
@Component
public class RepairEmptyDataSetXmlDataFixture implements DataFixture {

    static final int MAX_BUFFER = 64 * 1024;

    @Override
    public DataFixtureType getType() {
        return DataFixtureType.REPAIR_EMPTY_DATASET;
    }

    @Override
    public Set<SdmxFormat> supportedFormats() {
        return Set.of(SdmxFormat.XML_STRUCTURE_SPECIFIC_DATA_2_1, SdmxFormat.XML_GENERIC_DATA_2_1);
    }

    @Override
    public InputStream apply(InputStream input, SdmxBeans sdmxBeans, Map<String, String> config) {
        return new RepairingInputStream(input);
    }

    /**
     * Finds the stray end tag in {@code head}. Returns {@code [start, endExclusive]} of the tag to
     * drop, {@code null} when there is nothing to drop, or {@code int[0]} when more bytes are
     * needed to decide.
     */
    static int[] findStrayEndTag(byte[] head, int length, boolean eof) {
        int dataSetEnd = findDataSetStartTagEnd(head, length);
        if (dataSetEnd == -2) {
            return eof ? null : new int[0];
        }
        if (dataSetEnd < 0) {
            return null;
        }
        if (dataSetEnd >= 2 && head[dataSetEnd - 2] == '/') {
            return null;
        }
        int i = dataSetEnd;
        while (i < length && isWhitespace(head[i])) {
            i++;
        }
        if (i + 2 > length) {
            return eof ? null : new int[0];
        }
        if (head[i] != '<' || head[i + 1] != '/') {
            return null;
        }
        int nameStart = i + 2;
        int j = nameStart;
        while (j < length && head[j] != '>') {
            j++;
        }
        if (j >= length) {
            return eof ? null : new int[0];
        }
        String name = new String(head, nameStart, j - nameStart, StandardCharsets.US_ASCII).trim();
        String localName = name.substring(name.indexOf(':') + 1);
        return "Series".equals(localName) ? new int[]{i, j + 1} : null;
    }

    /**
     * Index just past the {@code >} that closes the dataset start tag; {@code -2} when the tag is
     * not complete yet in the buffer.
     */
    private static int findDataSetStartTagEnd(byte[] head, int length) {
        byte[] name = "DataSet".getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < length; i++) {
            if (head[i] != '<' || i + 1 >= length || head[i + 1] == '/' || head[i + 1] == '?' || head[i + 1] == '!') {
                continue;
            }
            int nameStart = i + 1;
            int nameEnd = nameStart;
            while (nameEnd < length && !isWhitespace(head[nameEnd]) && head[nameEnd] != '>' && head[nameEnd] != '/') {
                nameEnd++;
            }
            if (nameEnd >= length) {
                return -2;
            }
            int localStart = nameStart;
            for (int k = nameStart; k < nameEnd; k++) {
                if (head[k] == ':') {
                    localStart = k + 1;
                }
            }
            if (nameEnd - localStart != name.length || !regionMatches(head, localStart, name)) {
                continue;
            }
            int quote = 0;
            for (int k = nameEnd; k < length; k++) {
                byte b = head[k];
                if (quote != 0) {
                    if (b == quote) {
                        quote = 0;
                    }
                } else if (b == '"' || b == '\'') {
                    quote = b;
                } else if (b == '>') {
                    return k + 1;
                }
            }
            return -2;
        }
        return -2;
    }

    private static boolean regionMatches(byte[] bytes, int offset, byte[] expected) {
        for (int k = 0; k < expected.length; k++) {
            if (bytes[offset + k] != expected[k]) {
                return false;
            }
        }
        return true;
    }

    private static boolean isWhitespace(byte b) {
        return b == ' ' || b == '\t' || b == '\r' || b == '\n';
    }

    /**
     * Serves the repaired head first, then the rest of the upstream stream as it is.
     */
    private static final class RepairingInputStream extends InputStream {

        private final InputStream upstream;
        private byte[] head;
        private int headPos;
        private int headLength;

        private RepairingInputStream(InputStream upstream) {
            this.upstream = upstream;
        }

        private void prepareHead() throws IOException {
            if (head != null) {
                return;
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(8192);
            byte[] chunk = new byte[8192];
            boolean eof = false;
            int[] decision = new int[0];
            while (decision != null && decision.length == 0 && buffer.size() < MAX_BUFFER) {
                int n = upstream.read(chunk, 0, Math.min(chunk.length, MAX_BUFFER - buffer.size()));
                if (n < 0) {
                    eof = true;
                } else {
                    buffer.write(chunk, 0, n);
                }
                byte[] bytes = buffer.toByteArray();
                decision = findStrayEndTag(bytes, bytes.length, eof);
                if (eof) {
                    break;
                }
            }
            byte[] bytes = buffer.toByteArray();
            if (decision != null && decision.length == 2) {
                log.debug("Dropping the stray Series end tag of an empty Statistics Canada dataset");
                ByteArrayOutputStream repaired = new ByteArrayOutputStream(bytes.length);
                repaired.write(bytes, 0, decision[0]);
                repaired.write(bytes, decision[1], bytes.length - decision[1]);
                bytes = repaired.toByteArray();
            }
            head = bytes;
            headLength = bytes.length;
            headPos = 0;
        }

        @Override
        public int read() throws IOException {
            prepareHead();
            if (headPos < headLength) {
                return head[headPos++] & 0xFF;
            }
            return upstream.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            prepareHead();
            if (len == 0) {
                return 0;
            }
            if (headPos < headLength) {
                int n = Math.min(len, headLength - headPos);
                System.arraycopy(head, headPos, b, off, n);
                headPos += n;
                return n;
            }
            return upstream.read(b, off, len);
        }

        @Override
        public void close() throws IOException {
            upstream.close();
        }
    }
}
