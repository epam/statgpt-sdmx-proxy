package com.epam.sdmxproxy.services.availability.harvest;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import com.epam.sdmxproxy.exception.AvailabilityProbeQueuedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Detects a queued-extraction envelope returned in place of data -- see design 040.
 * <p>
 * Eurostat answers a data request whose extraction was queued with HTTP 200, the
 * {@code Content-Type} of the <em>requested</em> format, and a SOAP body:
 * <pre>
 * &lt;env:Envelope&gt;&lt;env:Body&gt;&lt;ns0:syncResponse&gt;
 *   &lt;processingTime&gt;111&lt;/processingTime&gt;
 *   &lt;queued&gt;&lt;id&gt;a801f1c5-...&lt;/id&gt;&lt;status&gt;SUBMITTED&lt;/status&gt;&lt;/queued&gt;
 * &lt;/ns0:syncResponse&gt;&lt;/env:Body&gt;&lt;/env:Envelope&gt;
 * </pre>
 * Roughly 380 bytes, so only the body distinguishes it from a real answer. Left undetected it
 * harvests to an empty coverage map, which the consumer reads as "nothing is available" and which
 * silently drops the dataset -- hence a hard failure here instead.
 * <p>
 * The check reads a bounded prefix and pushes it back, so the caller's stream is untouched. It
 * only fires when the prefix both looks like XML and mentions the queue markers, so a genuine
 * SDMX-ML data response passes through: {@code StructureSpecificData} and {@code GenericData}
 * carry neither marker.
 */
@Slf4j
@Component
public class QueuedResponseDetector {

    /**
     * Enough to cover the whole envelope (~380 bytes observed) with room for a longer prolog,
     * and small enough to be free on a large response.
     */
    static final int SNIFF_BYTES = 2048;

    private static final String SYNC_RESPONSE_MARKER = "syncResponse";
    private static final String QUEUED_MARKER = "<queued";

    /**
     * Wraps {@code probeResponse} so the prefix can be inspected without consuming it, and throws
     * if that prefix is a queued envelope.
     *
     * @param probeResponse raw probe stream; returned wrapped and positioned at the first byte
     * @param description   probe description for the exception message (URL shape, dataflow)
     * @return a stream equivalent to the input, safe to hand to a harvester
     * @throws AvailabilityProbeQueuedException when the body is a queued-extraction envelope
     */
    public InputStream requireNotQueued(InputStream probeResponse, String description) {
        BufferedInputStream buffered = probeResponse instanceof BufferedInputStream b
                ? b
                : new BufferedInputStream(probeResponse, SNIFF_BYTES * 2);
        String prefix = readPrefix(buffered);
        if (isQueuedEnvelope(prefix)) {
            log.warn("Probe for {} was answered with a queued-extraction envelope instead of data", description);
            throw new AvailabilityProbeQueuedException(
                    "Registry queued the availability emulation probe for " + description
                            + " instead of answering it");
        }
        return buffered;
    }

    /**
     * Whether {@code prefix} is a queued-extraction envelope rather than a data response.
     */
    boolean isQueuedEnvelope(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return false;
        }
        return prefix.contains(SYNC_RESPONSE_MARKER) && prefix.contains(QUEUED_MARKER);
    }

    private String readPrefix(BufferedInputStream buffered) {
        buffered.mark(SNIFF_BYTES + 1);
        try {
            byte[] prefix = new byte[SNIFF_BYTES];
            int read = 0;
            while (read < SNIFF_BYTES) {
                int n = buffered.read(prefix, read, SNIFF_BYTES - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            return new String(prefix, 0, Math.max(read, 0), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // Let the harvester surface the read failure with its own context.
            log.debug("Could not sniff probe response prefix, continuing", e);
            return "";
        } finally {
            try {
                buffered.reset();
            } catch (IOException e) {
                throw new IllegalStateException("Failed to rewind the probe response after sniffing", e);
            }
        }
    }
}
