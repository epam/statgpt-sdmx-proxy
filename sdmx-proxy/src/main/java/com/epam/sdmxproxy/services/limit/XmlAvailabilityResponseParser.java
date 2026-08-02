package com.epam.sdmxproxy.services.limit;

import com.epam.sdmxproxy.configuration.data.SdmxFormat;
import com.epam.sdmxproxy.exception.AvailabilityProbeException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Streaming StAX parser for SDMX-ML 2.1 availability responses (the {@code availableconstraint}
 * endpoint), which is what an SDMX 2.1 registry returns when the proxy probes it during limit
 * emulation.
 * <p>
 * Reads the {@code CubeRegion} of every {@code ContentConstraint}:
 * <pre>
 * &lt;structure:ContentConstraint&gt;
 *   &lt;common:Annotations&gt;
 *     &lt;common:Annotation id="series_count"&gt;
 *       &lt;common:AnnotationTitle&gt;1234&lt;/common:AnnotationTitle&gt;
 *   &lt;structure:CubeRegion include="true"&gt;
 *     &lt;common:KeyValue id="REF_AREA"&gt;
 *       &lt;common:Value&gt;AUS&lt;/common:Value&gt;
 * </pre>
 * <p>
 * Three deliberate behaviours:
 * <ul>
 *   <li>A {@code KeyValue} carrying no {@code Value} children is skipped entirely. The time
 *       dimension is reported as a {@code TimeRange} rather than a value list, and recording it as
 *       an empty dimension would drive {@link AvailabilityProjection#combinatorialUpperBound()} to
 *       zero, i.e. "the cube is empty".</li>
 *   <li>{@code CubeRegion include="false"} is an <em>exclusion</em> region: the values it lists are
 *       explicitly unavailable. Such values are subtracted from the included set rather than added
 *       to it. Inclusions and exclusions are accumulated separately and reconciled at the end, so
 *       the order in which regions appear does not matter. {@code include} defaults to {@code true}
 *       when the attribute is absent.</li>
 *   <li>Only a {@code series_count} annotation feeds {@code seriesCount}, matching
 *       {@link JsonAvailabilityResponseParser}. An {@code obs_count} annotation (which OECD emits)
 *       counts observations, not series, so using it would over-estimate and over-shrink; the
 *       projection falls back to the combinatorial bound instead.</li>
 * </ul>
 */
@Slf4j
@Component
public class XmlAvailabilityResponseParser implements AvailabilityResponseParser {

    private static final String CUBE_REGION_ELEMENT = "CubeRegion";
    private static final String KEY_VALUE_ELEMENT = "KeyValue";
    private static final String VALUE_ELEMENT = "Value";
    private static final String ANNOTATION_ELEMENT = "Annotation";
    private static final String ANNOTATION_TITLE_ELEMENT = "AnnotationTitle";
    private static final String ID_ATTRIBUTE = "id";
    private static final String INCLUDE_ATTRIBUTE = "include";
    private static final String SERIES_COUNT_ANNOTATION_ID = "series_count";

    private final XMLInputFactory inputFactory;

    public XmlAvailabilityResponseParser() {
        this.inputFactory = XMLInputFactory.newInstance();
        this.inputFactory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        this.inputFactory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        this.inputFactory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    }

    @Override
    public boolean supports(SdmxFormat format) {
        return format == SdmxFormat.XML_STRUCTURE_2_1;
    }

    @Override
    public AvailabilityProjection parse(InputStream availabilityResponseStream, SdmxFormat format) {
        if (!supports(format)) {
            throw new AvailabilityProbeException("Unsupported availability return format for XML parser: " + format);
        }

        Map<String, LinkedHashMap<String, Boolean>> included = new LinkedHashMap<>();
        Map<String, LinkedHashMap<String, Boolean>> excluded = new LinkedHashMap<>();
        long maxSeriesCount = -1L;
        XMLStreamReader reader = null;
        try {
            reader = inputFactory.createXMLStreamReader(availabilityResponseStream);
            String currentDimensionId = null;
            String currentAnnotationId = null;
            boolean currentRegionIncludes = true;
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String local = reader.getLocalName();
                    if (CUBE_REGION_ELEMENT.equals(local)) {
                        currentRegionIncludes = !"false".equalsIgnoreCase(reader.getAttributeValue(null, INCLUDE_ATTRIBUTE));
                    } else if (KEY_VALUE_ELEMENT.equals(local)) {
                        currentDimensionId = reader.getAttributeValue(null, ID_ATTRIBUTE);
                    } else if (VALUE_ELEMENT.equals(local) && currentDimensionId != null) {
                        String value = reader.getElementText();
                        if (value != null && !value.isBlank()) {
                            Map<String, LinkedHashMap<String, Boolean>> target = currentRegionIncludes ? included : excluded;
                            target.computeIfAbsent(currentDimensionId, k -> new LinkedHashMap<>()).putIfAbsent(value.trim(), Boolean.TRUE);
                        }
                    } else if (ANNOTATION_ELEMENT.equals(local)) {
                        currentAnnotationId = reader.getAttributeValue(null, ID_ATTRIBUTE);
                    } else if (ANNOTATION_TITLE_ELEMENT.equals(local) && SERIES_COUNT_ANNOTATION_ID.equals(currentAnnotationId)) {
                        maxSeriesCount = Math.max(maxSeriesCount, parseSeriesCount(reader.getElementText()));
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    String local = reader.getLocalName();
                    if (CUBE_REGION_ELEMENT.equals(local)) {
                        currentRegionIncludes = true;
                    } else if (KEY_VALUE_ELEMENT.equals(local)) {
                        currentDimensionId = null;
                    } else if (ANNOTATION_ELEMENT.equals(local)) {
                        currentAnnotationId = null;
                    }
                }
            }
        } catch (XMLStreamException e) {
            throw new AvailabilityProbeException("Failed to parse availability response", e);
        } finally {
            closeQuietly(reader);
        }

        if (!excluded.isEmpty()) {
            log.info("Availability response carries exclusion CubeRegions; subtracting {} excluded dimension(s)", excluded.keySet());
        }
        Map<String, List<String>> result = new LinkedHashMap<>(included.size());
        for (Map.Entry<String, LinkedHashMap<String, Boolean>> entry : included.entrySet()) {
            LinkedHashMap<String, Boolean> exclusions = excluded.get(entry.getKey());
            List<String> values = entry.getValue().keySet().stream().filter(value -> exclusions == null || !exclusions.containsKey(value)).toList();
            result.put(entry.getKey(), values);
        }
        Long seriesCount = maxSeriesCount >= 0L ? maxSeriesCount : null;
        log.debug("Parsed SDMX-ML 2.1 availability: dims={}, seriesCountAnnotation={}", result.keySet(), seriesCount);
        return new AvailabilityProjection(result, seriesCount);
    }

    private long parseSeriesCount(String title) {
        if (title == null || title.isBlank()) {
            return -1L;
        }
        try {
            return Long.parseLong(title.trim());
        } catch (NumberFormatException e) {
            log.warn("Unparseable series_count annotation title: '{}'", title);
            return -1L;
        }
    }

    private void closeQuietly(XMLStreamReader reader) {
        if (reader == null) {
            return;
        }
        try {
            reader.close();
        } catch (XMLStreamException e) {
            log.debug("Failed to close availability XML reader", e);
        }
    }
}
