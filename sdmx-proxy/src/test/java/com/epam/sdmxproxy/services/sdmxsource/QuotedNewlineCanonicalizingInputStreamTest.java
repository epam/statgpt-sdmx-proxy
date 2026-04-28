package com.epam.sdmxproxy.services.sdmxsource;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link QuotedNewlineCanonicalizingInputStream}. Exercises the
 * filter directly with synthetic inputs so failures point at the filter, not
 * at the conversion pipeline.
 */
class QuotedNewlineCanonicalizingInputStreamTest {

    @Test
    void passesUnquotedContentThrough() {
        assertEquals("a,b,c\nd,e,f\n", canonicalize("a,b,c\nd,e,f\n"));
    }

    @Test
    void replacesNewlinesInsideQuotedField() {
        assertEquals("a,\"line1 line2\",c\n",
                canonicalize("a,\"line1\nline2\",c\n"));
    }

    @Test
    void replacesCarriageReturnLineFeedAsSingleSpace() {
        assertEquals("a,\"line1 line2\",c\n",
                canonicalize("a,\"line1\r\nline2\",c\n"));
    }

    @Test
    void replacesBareCarriageReturnInsideQuoteAsSpace() {
        assertEquals("a,\"line1 line2\",c\n",
                canonicalize("a,\"line1\rline2\",c\n"));
    }

    @Test
    void preservesNewlinesOutsideQuotesEvenAfterAQuotedField() {
        assertEquals("a,\"x y\",c\nd,\"e\",f\n",
                canonicalize("a,\"x\ny\",c\nd,\"e\",f\n"));
    }

    @Test
    void leavesEscapedQuotesAloneWithoutTogglingState() {
        // "He said ""hi"" to me" is a single quoted field with a literal "hi".
        // The newline that follows must remain because we are *outside* a
        // quoted field after the closing quote.
        String in = "a,\"He said \"\"hi\"\" to me\",c\nnext\n";
        assertEquals(in, canonicalize(in));
    }

    @Test
    void handlesMultipleQuotedNewlinesInOneRow() {
        String in  = "a,\"first\nbroken\",b,\"second\nbroken\",end\n";
        String out = "a,\"first broken\",b,\"second broken\",end\n";
        assertEquals(out, canonicalize(in));
    }

    @Test
    void handlesUtf8MultiByteContentInsideQuotes() {
        // Continuation bytes (0x80..0xBF) must never be misinterpreted as
        // structural CSV characters. The non-breaking-space U+00A0 starts
        // with 0xC2 0xA0; both bytes lie outside the {", \n, \r} set.
        String in  = "a,\"café\nfin\",c\n";
        String out = "a,\"café fin\",c\n";
        assertEquals(out, canonicalize(in));
    }

    @Test
    void emptyInputYieldsEmpty() {
        assertEquals("", canonicalize(""));
    }

    @Test
    void readArrayMatchesByteByByte() throws Exception {
        String in = "a,\"x\ny\",c\nd,\"\"\"e\"\"\",f\n";
        InputStream byteStream = new QuotedNewlineCanonicalizingInputStream(
                new ByteArrayInputStream(in.getBytes(StandardCharsets.UTF_8)));
        StringBuilder sbBytes = new StringBuilder();
        int b;
        while ((b = byteStream.read()) != -1) {
            sbBytes.append((char) b);
        }

        InputStream arrayStream = new QuotedNewlineCanonicalizingInputStream(
                new ByteArrayInputStream(in.getBytes(StandardCharsets.UTF_8)));
        byte[] buf = new byte[7];
        StringBuilder sbArray = new StringBuilder();
        int n;
        while ((n = arrayStream.read(buf, 0, buf.length)) != -1) {
            sbArray.append(new String(buf, 0, n, StandardCharsets.UTF_8));
        }

        assertEquals(sbBytes.toString(), sbArray.toString());
    }

    private static String canonicalize(String input) {
        try (InputStream s = new QuotedNewlineCanonicalizingInputStream(
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)))) {
            return new String(s.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
