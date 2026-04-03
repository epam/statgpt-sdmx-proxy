package com.epam.sdmxproxy.services.adapter;

import com.epam.sdmxproxy.common.data.TranslatedAvailabilityQuery;
import com.epam.sdmxproxy.common.data.TranslatedDataQuery;
import com.epam.sdmxproxy.common.data.TranslatedStructureQuery;

import java.io.InputStream;

public interface GenericRegistryAdapter {
    InputStream getStructures(TranslatedStructureQuery query);

    InputStream getData(TranslatedDataQuery query);

    InputStream getAvailability(TranslatedAvailabilityQuery query);
}
