package com.epam.sdmxproxy.services.misc;

import io.sdmx.api.sdmx.model.beans.SdmxBeans;
import io.sdmx.api.sdmx.model.beans.base.IdentifiableBean;
import io.sdmx.api.sdmx.model.beans.datastructure.DataStructureBean;
import io.sdmx.api.sdmx.model.beans.datastructure.DataflowBean;
import io.sdmx.api.sdmx.model.beans.datastructure.DimensionBean;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Implementation of DimensionService.
 * Service to retrieve dimension IDs from a dataflow or data structure definition.
 */
@Service
@RequiredArgsConstructor
public class DimensionServiceImpl implements DimensionService {
    private static final String DEFAULT_TIME_DIMENSION_ID = "TIME_PERIOD";

    @Override
    public Set<String> getDimensionIds(SdmxBeans sdmxBeans, String agencyId, String resourceId, String version) {
        // Find the dataflow
        DataflowBean dataflow = sdmxBeans.getDataflows().stream()
                .filter(df -> df.getId().equals(resourceId) && df.getAgencyId().equals(agencyId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Dataflow not found: " + resourceId));

        // Get the DSD referenced by the dataflow
        String dsdAgency = dataflow.getDataStructureRef().getReference().getAgencyId();
        String dsdId = dataflow.getDataStructureRef().getReference().getMaintainableId();
        String dsdVersion = dataflow.getDataStructureRef().getReference().getVersion().toString();

        // Get dimensions from DSD
        return getDimensionIdsFromDsd(sdmxBeans, dsdAgency, dsdId, dsdVersion);
    }

    @Override
    public Set<String> getDimensionIdsFromDsd(SdmxBeans sdmxBeans, String agency, String id, String version) {

        DataStructureBean dsd = sdmxBeans.getDataStructures().stream()
                .filter(dsdBean -> dsdBean.getAgencyId().equals(agency) && dsdBean.getId().equals(id) && dsdBean.getVersion().toString().equals(version))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("DataStructure not found: " + id));

        return dsd.getDimensionList().getDimensions().stream()
                .filter(dimension -> !dimension.isTimeDimension())
                .map(IdentifiableBean::getId)
                .collect(Collectors.toSet());
    }


    @Override
    public String getTimeDimensionId(SdmxBeans sdmxBeans, String agencyId, String resourceId, String version) {
        DataStructureBean dsd = getDsdFromDataflow(sdmxBeans, agencyId, resourceId);

        return dsd.getDimensionList().getDimensions().stream()
                .filter(DimensionBean::isTimeDimension)
                .map(IdentifiableBean::getId)
                .findFirst()
                .orElse(DEFAULT_TIME_DIMENSION_ID);
    }

    private DataStructureBean getDsdFromDataflow(SdmxBeans sdmxBeans, String agencyId, String resourceId) {
        DataflowBean dataflow = sdmxBeans.getDataflows().stream()
                .filter(df -> df.getId().equals(resourceId) && df.getAgencyId().equals(agencyId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Dataflow not found: " + resourceId));

        String dsdAgency = dataflow.getDataStructureRef().getReference().getAgencyId();
        String dsdId = dataflow.getDataStructureRef().getReference().getMaintainableId();
        String dsdVersion = dataflow.getDataStructureRef().getReference().getVersion().toString();

        return sdmxBeans.getDataStructures().stream()
                .filter(dsdBean -> dsdBean.getAgencyId().equals(dsdAgency) && dsdBean.getId().equals(dsdId) && dsdBean.getVersion().toString().equals(dsdVersion))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("DataStructure not found: " + dsdId));
    }
}
