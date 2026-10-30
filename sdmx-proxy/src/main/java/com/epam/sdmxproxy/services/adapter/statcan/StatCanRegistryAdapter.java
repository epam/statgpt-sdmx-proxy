package com.epam.sdmxproxy.services.adapter.statcan;

import com.epam.sdmxproxy.common.data.TranslatedAvailabilityQuery;
import com.epam.sdmxproxy.common.data.TranslatedDataQuery;
import com.epam.sdmxproxy.common.data.TranslatedStructureQuery;
import com.epam.sdmxproxy.configuration.data.RegistrySelectionResult;
import com.epam.sdmxproxy.services.adapter.GenericRegistryAdapter;
import com.epam.sdmxproxy.services.adapter.GenericRegistryAdapterImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.InputStream;

/**
 * Adapter for Statistics Canada ({@code "adapter": "STATCAN"}) -- see design 043, Revision 1.
 * <p>
 * StatCan has no SDMX structure resources beyond per-table bundles, so structures come from
 * {@link StatCanStructureSource}. Its data endpoint is SDMX 2.1 with deviations that the data
 * endpoint config already describes ({@code flowRefIdOnly}, {@code omitProviderRef},
 * {@code expandMatchAllKey}), so data and availability go through the standard SDMX adapter.
 */
@Service
@RequiredArgsConstructor
public class StatCanRegistryAdapter implements GenericRegistryAdapter {

    private final StatCanStructureSource structureSource;
    private final GenericRegistryAdapterImpl sdmxAdapter;

    @Override
    public InputStream getStructures(TranslatedStructureQuery query) {
        RegistrySelectionResult selectedRegistry = RegistrySelectionResult.builder()
                .registryConfiguration(query.getRegistryConfiguration())
                .versionConfiguration(query.getVersionConfiguration())
                .build();
        return structureSource.getStructures(query, selectedRegistry);
    }

    @Override
    public InputStream getData(TranslatedDataQuery query) {
        return sdmxAdapter.getData(query);
    }

    @Override
    public InputStream getAvailability(TranslatedAvailabilityQuery query) {
        return sdmxAdapter.getAvailability(query);
    }
}
