package com.epam.sdmxproxy.services.limit;

import com.epam.sdmxproxy.configuration.data.SdmxFormat;
import com.epam.sdmxproxy.exception.AvailabilityProbeException;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.List;

/**
 * Routes an availability response to the parser that understands its format.
 * <p>
 * Registries differ in what they return from the availability endpoint: an SDMX 3.0 registry answers
 * with SDMX-JSON 2.0.0, an SDMX 2.1 registry with SDMX-ML 2.1. Limit emulation probes whichever the
 * registry's {@code availabilityEndpointConfig.defaultFormat} names, so the parser cannot be fixed
 * at wiring time.
 */
@Primary
@Component
@RequiredArgsConstructor
public class DelegatingAvailabilityResponseParser implements AvailabilityResponseParser {

    private final JsonAvailabilityResponseParser jsonParser;
    private final XmlAvailabilityResponseParser xmlParser;

    @Override
    public boolean supports(SdmxFormat format) {
        return parsers().stream().anyMatch(parser -> parser.supports(format));
    }

    @Override
    public AvailabilityProjection parse(InputStream availabilityResponseStream, SdmxFormat format) {
        return parsers().stream()
                .filter(parser -> parser.supports(format))
                .findFirst()
                .orElseThrow(() -> new AvailabilityProbeException("No availability parser for return format: " + format))
                .parse(availabilityResponseStream, format);
    }

    private List<AvailabilityResponseParser> parsers() {
        return List.of(jsonParser, xmlParser);
    }
}
