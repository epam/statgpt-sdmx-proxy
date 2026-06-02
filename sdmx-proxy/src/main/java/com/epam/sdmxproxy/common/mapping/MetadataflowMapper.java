package com.epam.sdmxproxy.common.mapping;

import com.epam.jsdmx.infomodel.sdmx30.Metadataflow;
import com.epam.jsdmx.infomodel.sdmx30.MetadataflowImpl;
import com.epam.jsdmx.infomodel.sdmx30.StructureClassImpl;
import com.epam.jsdmx.infomodel.sdmx30.Version;
import io.sdmx.api.sdmx.model.beans.metadatastructure.MetadataFlowBean;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class MetadataflowMapper implements Mapper<MetadataFlowBean> {

    private final AnnotationMapper annotationMapper;
    private final ReferenceMapper referenceMapper;
    private final TextMapper textMapper;

    @Override
    public Metadataflow map(MetadataFlowBean bean) {
        var mf = new MetadataflowImpl();
        mf.setOrganizationId(bean.getAgencyId());
        mf.setId(bean.getId());
        mf.setVersion(Version.createFromString(bean.getVersion().toString()));
        mf.setName(textMapper.map(bean.getNames()));
        mf.setDescription(textMapper.map(bean.getDescriptions()));
        mf.setAnnotations(annotationMapper.map(bean.getAnnotations()));
        if (bean.getMetadataStructureRef() != null) {
            mf.setStructure(referenceMapper.mapMaintainable(
                    bean.getMetadataStructureRef(), StructureClassImpl.METADATA_STRUCTURE));
        }
        return mf;
    }
}
