package com.epam.sdmxproxy.services.availability.harvest;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import com.epam.sdmxproxy.configuration.data.SdmxFormat;
import com.epam.sdmxproxy.exception.AvailabilityEmulationException;
import com.epam.sdmxproxy.exception.ResponseTooLargeException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import io.sdmx.api.sdmx.model.beans.SdmxBeans;

/**
 * Harvests per-dimension coverage from an SDMX-ML series-key response -- see design 040.
 * <p>
 * One StAX pass covers both packagings a registry may answer {@code detail=serieskeysonly} with:
 * <pre>
 * structure-specific:  &lt;Series FREQ="A" unit="CP_MEUR" na_item="B1GQ" geo="AT"/&gt;
 * generic:             &lt;Series&gt;&lt;SeriesKey&gt;&lt;Value id="FREQ" value="A"/&gt;...
 * </pre>
 * The generic form needs the {@code SeriesKey} depth tracked: {@code Series/Attributes} carries
 * {@code Value} elements with the same {@code id}/{@code value} shape, and folding those in would
 * report attribute values as dimension coverage.
 * <p>
 * Only values for DSD-declared non-time dimensions are recorded, so neither packaging can
 * contribute a stray component. CSV is the cheaper probe format -- measured 3.36x smaller on the
 * same key set -- so this exists for registries whose data endpoint serves no CSV.
 */
@Slf4j
@Component
public class XmlSeriesKeyHarvester implements SeriesKeyHarvester {

    private static final String SERIES_ELEMENT = "Series";
    private static final String SERIES_KEY_ELEMENT = "SeriesKey";
    private static final String VALUE_ELEMENT = "Value";
    private static final String ID_ATTRIBUTE = "id";
    private static final String VALUE_ATTRIBUTE = "value";

    private final XMLInputFactory inputFactory;

    public XmlSeriesKeyHarvester() {
        this.inputFactory = XMLInputFactory.newInstance();
        this.inputFactory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        this.inputFactory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        this.inputFactory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    }

    @Override
    public Set<SdmxFormat> supportedFormats() {
        return Set.of(
                SdmxFormat.XML_GENERIC_DATA_2_1,
                SdmxFormat.XML_STRUCTURE_SPECIFIC_DATA_2_1,
                SdmxFormat.XML_GENERIC_TIME_SERIES_DATA_2_1,
                SdmxFormat.XML_STRUCTURE_SPECIFIC_TIME_SERIES_DATA_2_1,
                SdmxFormat.XML_DATA_3_0_0
        );
    }

    @Override
    public HarvestedCoverage harvest(InputStream probeResponse, SdmxBeans sdmxBeans, HarvestLimits limits) {
        List<String> dimensionIds = DimensionIds.nonTimeDimensionIds(sdmxBeans);
        Set<String> known = new LinkedHashSet<>(dimensionIds);
        Map<String, Set<String>> values = new LinkedHashMap<>();
        for (String dimensionId : dimensionIds) {
            values.put(dimensionId, new LinkedHashSet<>());
        }

        InputStream limited = new ByteLimitingInputStream(probeResponse, limits.maxBytes(), "xml probe");
        long series = 0;
        XMLStreamReader reader = null;
        try {
            reader = inputFactory.createXMLStreamReader(limited);
            boolean inSeriesKey = false;
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String name = reader.getLocalName();
                    if (SERIES_ELEMENT.equals(name)) {
                        series++;
                        if (series > limits.maxSeries()) {
                            throw new ResponseTooLargeException(
                                    "Availability emulation probe returned more than " + limits.maxSeries()
                                            + " series; narrow the request or raise "
                                            + "availabilityEndpointConfig.emulation.maxProbeSeries");
                        }
                        // Structure-specific packaging: the whole key is on the Series element.
                        readSeriesAttributes(reader, known, values);
                    } else if (SERIES_KEY_ELEMENT.equals(name)) {
                        inSeriesKey = true;
                    } else if (inSeriesKey && VALUE_ELEMENT.equals(name)) {
                        String id = reader.getAttributeValue(null, ID_ATTRIBUTE);
                        String value = reader.getAttributeValue(null, VALUE_ATTRIBUTE);
                        if (id != null && value != null && known.contains(id)) {
                            values.get(id).add(value);
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT
                        && SERIES_KEY_ELEMENT.equals(reader.getLocalName())) {
                    inSeriesKey = false;
                }
            }
        } catch (XMLStreamException e) {
            throw new AvailabilityEmulationException("Failed to parse the SDMX-ML probe response", e);
        } finally {
            closeQuietly(reader);
        }

        log.info("XML probe harvested {} series into {}", series, describe(values));
        return new HarvestedCoverage(values, series);
    }

    private void readSeriesAttributes(XMLStreamReader reader, Set<String> known, Map<String, Set<String>> values) {
        for (int i = 0; i < reader.getAttributeCount(); i++) {
            String id = reader.getAttributeLocalName(i);
            if (known.contains(id)) {
                values.get(id).add(reader.getAttributeValue(i));
            }
        }
    }

    private void closeQuietly(XMLStreamReader reader) {
        if (reader == null) {
            return;
        }
        try {
            reader.close();
        } catch (XMLStreamException e) {
            log.debug("Failed to close the StAX reader for the probe response", e);
        }
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
