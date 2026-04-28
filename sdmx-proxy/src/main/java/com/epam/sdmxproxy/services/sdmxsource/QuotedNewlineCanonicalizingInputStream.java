package com.epam.sdmxproxy.services.sdmxsource;

import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Streaming filter that replaces line breaks ({@code \n}, {@code \r}, {@code \r\n})
 * occurring <em>inside</em> double-quoted CSV fields with a single space,
 * leaving line breaks outside quoted fields untouched. Escaped quotes
 * ({@code ""}) are passed through without toggling the in-quote state.
 *
 * <p>Workaround for a defect in {@code io.sdmx.utils.core.csv.CSVColumnReaderEngineImpl}
 * (sdmx-core 2.3.9): its {@code moveNextRow} pulls one physical line at a time
 * via {@code BufferedReader.readLine()} and then validates cell count against
 * the header. RFC 4180 allows quoted fields to span multiple physical lines,
 * so registries that emit long descriptive attributes (e.g. IMF's
 * {@code FULL_DESCRIPTION}) trip {@code validateRowSize} immediately. See
 * <a href="https://github.com/epam/statgpt-sdmx-proxy/issues/57">issue #57</a>.
 *
 * <p>The filter is byte-oriented but UTF-8-safe: every byte it inspects
 * ({@code "}, {@code \n}, {@code \r}) is a 7-bit ASCII control character,
 * which never appears as a continuation byte in a multi-byte UTF-8 sequence.
 */
public class QuotedNewlineCanonicalizingInputStream extends InputStream {

    private static final int QUOTE = '"';
    private static final int LF = '\n';
    private static final int CR = '\r';
    private static final int SPACE = ' ';

    private final PushbackInputStream upstream;
    private final Deque<Integer> emit = new ArrayDeque<>();
    private boolean inQuote;

    public QuotedNewlineCanonicalizingInputStream(InputStream upstream) {
        this.upstream = new PushbackInputStream(upstream, 1);
    }

    @Override
    public int read() throws IOException {
        if (!emit.isEmpty()) {
            return emit.removeFirst();
        }
        int b = upstream.read();
        if (b == -1) {
            return -1;
        }
        if (b == QUOTE) {
            int next = upstream.read();
            if (next == QUOTE) {
                // Escaped literal quote ("" inside a quoted field). Emit both,
                // do not toggle in-quote state.
                emit.addLast(QUOTE);
                return QUOTE;
            }
            // Real opening or closing quote.
            inQuote = !inQuote;
            if (next != -1) {
                upstream.unread(next);
            }
            return QUOTE;
        }
        if (inQuote && (b == LF || b == CR)) {
            if (b == CR) {
                int next = upstream.read();
                if (next != LF && next != -1) {
                    upstream.unread(next);
                }
            }
            return SPACE;
        }
        return b;
    }

    @Override
    public int read(byte[] dst, int off, int len) throws IOException {
        if (len == 0) {
            return 0;
        }
        int first = read();
        if (first == -1) {
            return -1;
        }
        dst[off] = (byte) first;
        int n = 1;
        while (n < len) {
            int b = read();
            if (b == -1) {
                break;
            }
            dst[off + n] = (byte) b;
            n++;
        }
        return n;
    }

    @Override
    public void close() throws IOException {
        upstream.close();
    }
}
