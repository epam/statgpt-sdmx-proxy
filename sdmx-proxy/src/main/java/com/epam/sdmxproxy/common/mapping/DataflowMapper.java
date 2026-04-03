package com.epam.sdmxproxy.common.mapping;

import com.epam.jsdmx.infomodel.sdmx30.Dataflow;
import com.epam.jsdmx.infomodel.sdmx30.DataflowImpl;
import com.epam.jsdmx.infomodel.sdmx30.StructureClassImpl;
import com.epam.jsdmx.infomodel.sdmx30.Version;
import io.sdmx.api.sdmx.model.beans.datastructure.DataflowBean;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class DataflowMapper implements Mapper<DataflowBean> {

    private final AnnotationMapper annotationMapper;
    private final ReferenceMapper referenceMapper;
    private final TextMapper textMapper;

    @Override
    public Dataflow map(DataflowBean bean) {
        var df = new DataflowImpl();
        df.setOrganizationId(bean.getAgencyId());
        df.setId(bean.getId());
        df.setVersion(Version.createFromString(bean.getVersion().toString()));
        df.setName(textMapper.map(bean.getNames()));
        df.setDescription(textMapper.map(bean.getDescriptions()));
        df.setAnnotations(annotationMapper.map(bean.getAnnotations()));
        df.setStructure(referenceMapper.mapMaintainable(bean.getDataStructureRef(), StructureClassImpl.DATA_STRUCTURE));
        return df;
    }
}
