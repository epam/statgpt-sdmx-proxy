package com.epam.sdmxproxy.services.availability;

import java.util.List;

import com.epam.sdmxproxy.common.data.TranslatedAvailabilityQuery;
import com.epam.sdmxproxy.common.data.TranslatedDataQuery;
import com.epam.sdmxproxy.configuration.data.DataEndpointConfiguration;
import com.epam.sdmxproxy.configuration.data.SdmxFormat;
import com.epam.sdmxproxy.configuration.data.SdmxVersion;
import com.epam.sdmxproxy.configuration.data.availability.AvailabilityEmulationConfiguration;
import com.epam.sdmxproxy.exception.AvailabilityEmulationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;

/**
 * Turns an availability request into the series-key data request that emulates it -- see
 * design 040.
 * <p>
 * The one property that matters: <strong>the client's key and filters are carried through
 * unchanged</strong>. Everything else the builder sets is about asking for keys and nothing else.
 * <p>
 * Deliberately absent: {@code limit}, {@code firstNObservations}, {@code lastNObservations}.
 * {@code lastNObservations=1} was the original idea for this feature and is actively harmful --
 * Eurostat sizes a request from the requested cube <em>before</em> applying it, so on a broad key
 * it both collapses the time coverage and trips the extraction-size gate (HTTP 413
 * {@code EXTRACTION_TOO_BIG}). Plain {@code detail=serieskeysonly} is not gated at all: measured
 * synchronous 200s up to 139 MB and 71 s.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProbeQueryBuilder {

    private static final String ATTRIBUTES_NONE = "none";
    private static final String MEASURES_NONE = "none";

    /**
     * Builds the probe. {@code key} and {@code filters} override the query's own, which is how
     * {@code ProbeDecomposer} pins a chunk; pass the query's own values for an undecomposed probe.
     */
    public TranslatedDataQuery build(
            TranslatedAvailabilityQuery query,
            String key,
            MultiValueMap<String, String> filters
    ) {
        AvailabilityEmulationConfiguration emulation = emulation(query);
        SdmxVersion sdmxVersion = query.getVersionConfiguration().getSdmxVersion();
        SdmxFormat probeFormat = resolveProbeFormat(query, emulation);

        TranslatedDataQuery.TranslatedDataQueryBuilder builder = TranslatedDataQuery.builder()
                .registryConfiguration(query.getRegistryConfiguration())
                .versionConfiguration(query.getVersionConfiguration())
                .context(query.getContext())
                .agencyID(query.getAgencyID())
                .resourceID(query.getResourceID())
                .version(query.getVersion())
                .key(key)
                .filters(filters)
                // The probe is an internal request; the client's Accept header governs the
                // emulated response, not the probe. Both are set to the probe format so the
                // adapter's Accept resolution and its CSV parameter handling agree.
                .contentType(org.springframework.http.MediaType.parseMediaType(probeFormat.getContentType()))
                .returnFormat(probeFormat);

        if (sdmxVersion == SdmxVersion.SDMX_2_1) {
            // SDMX-REST 1.5.0: `detail` selects how much of each series to return.
            builder.detail(emulation.getProbeDetail());
        } else {
            // SDMX-REST 2.x dropped `detail`; attributes+measures replace it. Sending `detail`
            // to a 3.0 registry is at best ignored and at worst a 400.
            builder.attributes(ATTRIBUTES_NONE).measures(MEASURES_NONE);
        }

        TranslatedDataQuery probe = builder.build();
        log.info(
                "Availability emulation probe: registry={}, flow={}:{}({}), key='{}', filters={}, format={}, detail={}, attributes={}, measures={}",
                query.getRegistryConfiguration().getName(), query.getAgencyID(), query.getResourceID(),
                query.getVersion(), probe.getKey(), probe.getFilters(), probeFormat,
                probe.getDetail(), probe.getAttributes(), probe.getMeasures()
        );
        return probe;
    }

    public TranslatedDataQuery build(TranslatedAvailabilityQuery query) {
        return build(query, query.getKey(), query.getFilters());
    }

    /**
     * Configured {@code probeFormat}, else the cheapest CSV the data endpoint serves, else that
     * endpoint's {@code defaultFormat}.
     * <p>
     * CSV is preferred because the registry has already deduplicated: with the observations gone
     * the {@code TIME_PERIOD} column goes too, so every row is one distinct series key. Measured
     * 3.36x smaller than SDMX-ML on the same key set.
     */
    public SdmxFormat resolveProbeFormat(
            TranslatedAvailabilityQuery query,
            AvailabilityEmulationConfiguration emulation
    ) {
        if (emulation.getProbeFormat() != null) {
            return emulation.getProbeFormat();
        }
        DataEndpointConfiguration dataConfig = query.getVersionConfiguration().getDataEndpointConfig();
        if (dataConfig == null) {
            throw new AvailabilityEmulationException(
                    "Registry " + query.getRegistryConfiguration().getName()
                            + " has no dataEndpointConfig, so availability cannot be emulated from a data query");
        }
        List<SdmxFormat> supported = dataConfig.getSupportedFormats();
        if (supported != null) {
            for (SdmxFormat candidate : List.of(SdmxFormat.CSV_DATA_2_0_0, SdmxFormat.CSV_DATA_1_0_0)) {
                if (supported.contains(candidate)) {
                    return candidate;
                }
            }
        }
        if (dataConfig.getDefaultFormat() == null) {
            throw new AvailabilityEmulationException(
                    "Registry " + query.getRegistryConfiguration().getName()
                            + " declares no data format usable as an availability emulation probe");
        }
        return dataConfig.getDefaultFormat();
    }

    public AvailabilityEmulationConfiguration emulation(TranslatedAvailabilityQuery query) {
        AvailabilityEmulationConfiguration emulation =
                query.getVersionConfiguration().getAvailabilityEndpointConfig().getEmulation();
        if (emulation == null) {
            throw new AvailabilityEmulationException(
                    "Availability emulation is not configured for registry "
                            + query.getRegistryConfiguration().getName());
        }
        return emulation;
    }
}
