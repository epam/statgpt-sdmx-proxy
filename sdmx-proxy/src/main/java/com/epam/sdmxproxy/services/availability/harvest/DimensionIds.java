package com.epam.sdmxproxy.services.availability.harvest;

import java.util.ArrayList;
import java.util.List;

import com.epam.sdmxproxy.exception.AvailabilityEmulationException;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;
import io.sdmx.api.sdmx.model.beans.base.IdentifiableBean;
import io.sdmx.api.sdmx.model.beans.datastructure.DataStructureBean;
import io.sdmx.api.sdmx.model.beans.datastructure.DimensionBean;

/**
 * Reads the dataflow's non-time dimension IDs, in DSD-declared order, from parsed structures.
 * <p>
 * DSD order matters twice over: it is the order the emulated cube region reports its key values
 * in, and it is the positional order of an SDMX 2.1 key. See design 040.
 */
public final class DimensionIds {

    private DimensionIds() {
    }

    /**
     * Non-time dimension IDs in DSD order.
     *
     * @throws AvailabilityEmulationException when {@code beans} carries no data structure -- the
     *                                        emulator cannot name the cube's dimensions without
     *                                        one, and guessing them from a probe response would
     *                                        silently omit any dimension the probe did not carry
     */
    public static List<String> nonTimeDimensionIds(SdmxBeans beans) {
        DataStructureBean dsd = dataStructure(beans);
        List<String> ids = new ArrayList<>();
        for (DimensionBean dimension : dsd.getDimensionList().getDimensions()) {
            if (!dimension.isTimeDimension()) {
                ids.add(dimension.getId());
            }
        }
        return ids;
    }

    public static DataStructureBean dataStructure(SdmxBeans beans) {
        if (beans == null || beans.getDataStructures() == null || beans.getDataStructures().isEmpty()) {
            throw new AvailabilityEmulationException(
                    "No data structure available for availability emulation; cannot determine the cube's dimensions");
        }
        return beans.getDataStructures().iterator().next();
    }

    public static List<DimensionBean> nonTimeDimensions(SdmxBeans beans) {
        List<DimensionBean> dimensions = new ArrayList<>();
        for (DimensionBean dimension : dataStructure(beans).getDimensionList().getDimensions()) {
            if (!dimension.isTimeDimension()) {
                dimensions.add(dimension);
            }
        }
        return dimensions;
    }

    public static String timeDimensionId(SdmxBeans beans) {
        for (DimensionBean dimension : dataStructure(beans).getDimensionList().getDimensions()) {
            if (dimension.isTimeDimension()) {
                return ((IdentifiableBean) dimension).getId();
            }
        }
        return null;
    }
}
