package com.epam.sdmxproxy.services.availability.harvest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import com.epam.sdmxproxy.exception.AvailabilityProbeQueuedException;
import lombok.SneakyThrows;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A queued envelope left undetected is the worst failure mode in the emulator: it harvests to an
 * empty coverage map, which the consumer reads as "nothing is available" and which silently drops
 * the dataset from every candidate set. So detection is asserted both ways.
 */
class QueuedResponseDetectorTest {

    private final QueuedResponseDetector detector = new QueuedResponseDetector();

    @Test
    @DisplayName("the real Eurostat queued envelope is rejected")
    void queuedEnvelopeThrows() {
        assertThrows(AvailabilityProbeQueuedException.class,
                () -> detector.requireNotQueued(fixture("eurostat_queued_extraction.xml"), "NAMA_10_GDP"));
    }

    @Test
    @DisplayName("a real CSV probe passes through untouched")
    @SneakyThrows(IOException.class)
    void csvPassesThrough() {
        byte[] body = ("DATAFLOW,LAST UPDATE,freq,unit,na_item,geo\n"
                + "ESTAT:NAMA_10_GDP(1.0),03/09/26 23:00:00,A,CLV05_MEUR,B1G,EL\n").getBytes(StandardCharsets.UTF_8);

        InputStream checked = detector.requireNotQueued(new ByteArrayInputStream(body), "NAMA_10_GDP");

        assertArrayEquals(body, checked.readAllBytes(), "the sniffed prefix must be pushed back");
    }

    @Test
    @DisplayName("a real SDMX-ML probe passes through untouched")
    @SneakyThrows(IOException.class)
    void sdmxMlPassesThrough() {
        byte[] body = fixture("estat_nama_10_gdp_generic_serieskeysonly.xml").readAllBytes();

        InputStream checked = detector.requireNotQueued(new ByteArrayInputStream(body), "NAMA_10_GDP");

        assertArrayEquals(body, checked.readAllBytes());
    }

    @Test
    @DisplayName("a body shorter than the sniff window is handled")
    @SneakyThrows(IOException.class)
    void shortBodyPassesThrough() {
        byte[] body = "STRUCTURE,STRUCTURE_ID,freq\n".getBytes(StandardCharsets.UTF_8);

        InputStream checked = detector.requireNotQueued(new ByteArrayInputStream(body), "NAMA_10_GDP");

        assertArrayEquals(body, checked.readAllBytes());
    }

    @Test
    @DisplayName("an empty body is not a queued response")
    @SneakyThrows(IOException.class)
    void emptyBodyPassesThrough() {
        InputStream checked = detector.requireNotQueued(new ByteArrayInputStream(new byte[0]), "NAMA_10_GDP");

        assertArrayEquals(new byte[0], checked.readAllBytes());
    }

    private static InputStream fixture(String name) {
        InputStream stream = QueuedResponseDetectorTest.class.getResourceAsStream("/com/epam/sdmxproxy/services/availability/" + name);
        if (stream == null) {
            throw new IllegalStateException("Missing test fixture: " + name);
        }
        return stream;
    }
}
