package com.epam.sdmxproxy.services.availability;

import java.io.InputStream;

import com.epam.sdmxproxy.common.data.TranslatedDataQuery;
import com.epam.sdmxproxy.common.data.TranslatedStructureQuery;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;

/**
 * The upstream calls an emulator is allowed to make, handed to it by {@code AdapterRouter}.
 * <p>
 * Exists to keep the architectural rule that only {@code AdapterRouter} talks to
 * {@code GenericRegistryAdapter} -- the same reason {@code AvailabilityProber} exists for
 * limit emulation. In production these are bound to the router's own adapter; tests pass lambdas
 * returning fixture streams.
 */
public interface AvailabilityEmulationContext {

    /**
     * Fetches a structure artefact, e.g. the registry's content constraint.
     */
    InputStream fetchStructure(TranslatedStructureQuery query);

    /**
     * Issues a data request, e.g. a series-key probe.
     */
    InputStream fetchData(TranslatedDataQuery query);

    /**
     * Builds and runs a structure query, returning parsed structures. Goes through the router's
     * structure cache, so repeated calls for the same dataflow are free.
     */
    SdmxBeans fetchStructureBeans(TranslatedStructureQuery query);

    /**
     * Builds a structure query for an arbitrary structure type against the registry the
     * availability request resolved to.
     *
     * @param structureType e.g. {@code contentconstraint}, {@code dataconstraint}
     */
    TranslatedStructureQuery structureQuery(String structureType, String agencyId, String resourceId, String version);
}
