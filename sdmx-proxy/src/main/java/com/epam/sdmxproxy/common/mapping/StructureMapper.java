package com.epam.sdmxproxy.common.mapping;

import com.epam.jsdmx.infomodel.sdmx30.Artefacts;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;
import io.sdmx.api.sdmx.model.beans.datastructure.DataStructureBean;

public interface StructureMapper {
    Artefacts map(SdmxBeans beans);

    Mapper<DataStructureBean> getDataStructureMapper();
}
