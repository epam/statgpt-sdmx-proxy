package com.epam.sdmxproxy.services.adapter;

import com.epam.sdmxproxy.common.data.TranslatedAvailabilityQuery;
import com.epam.sdmxproxy.common.data.TranslatedDataQuery;
import com.epam.sdmxproxy.common.data.TranslatedStructureQuery;
import com.epam.sdmxproxy.configuration.data.RegistryAdapterType;
import com.epam.sdmxproxy.configuration.data.RegistryConfiguration;
import com.epam.sdmxproxy.services.adapter.statcan.StatCanRegistryAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.io.InputStream;

/**
 * The {@link GenericRegistryAdapter} every caller gets: picks the adapter by the registry's
 * {@code adapter} setting -- see design 043, Revision 1. Standard SDMX registries go to
 * {@link GenericRegistryAdapterImpl}, which holds no registry-specific code.
 */
@Primary
@Service
@RequiredArgsConstructor
public class RoutingRegistryAdapter implements GenericRegistryAdapter {

    private final GenericRegistryAdapterImpl sdmxAdapter;
    private final StatCanRegistryAdapter statCanAdapter;

    @Override
    public InputStream getStructures(TranslatedStructureQuery query) {
        return adapterFor(query.getRegistryConfiguration()).getStructures(query);
    }

    @Override
    public InputStream getData(TranslatedDataQuery query) {
        return adapterFor(query.getRegistryConfiguration()).getData(query);
    }

    @Override
    public InputStream getAvailability(TranslatedAvailabilityQuery query) {
        return adapterFor(query.getRegistryConfiguration()).getAvailability(query);
    }

    private GenericRegistryAdapter adapterFor(RegistryConfiguration registry) {
        RegistryAdapterType type = registry != null ? registry.getAdapter() : null;
        return type == RegistryAdapterType.STATCAN ? statCanAdapter : sdmxAdapter;
    }
}
