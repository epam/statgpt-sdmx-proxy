package com.epam.sdmxproxy.common.mapping;

import com.epam.jsdmx.infomodel.sdmx30.MetadataProvisionAgreement;
import com.epam.jsdmx.infomodel.sdmx30.MetadataProvisionAgreementImpl;
import com.epam.jsdmx.infomodel.sdmx30.StructureClassImpl;
import com.epam.jsdmx.infomodel.sdmx30.Version;
import io.sdmx.api.sdmx.model.beans.metadatastructure.MetadataProvisionAgreementBean;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class MetadataProvisionAgreementMapper implements Mapper<MetadataProvisionAgreementBean> {

    private final AnnotationMapper annotationMapper;
    private final ReferenceMapper referenceMapper;
    private final TextMapper textMapper;

    @Override
    public MetadataProvisionAgreement map(MetadataProvisionAgreementBean bean) {
        var mpa = new MetadataProvisionAgreementImpl();
        mpa.setOrganizationId(bean.getAgencyId());
        mpa.setId(bean.getId());
        mpa.setVersion(Version.createFromString(bean.getVersion().toString()));
        mpa.setName(textMapper.map(bean.getNames()));
        mpa.setDescription(textMapper.map(bean.getDescriptions()));
        mpa.setAnnotations(annotationMapper.map(bean.getAnnotations()));
        if (bean.getMetadataflowRef() != null) {
            mpa.setControlledStructureUsage(referenceMapper.mapMaintainable(
                    bean.getMetadataflowRef(), StructureClassImpl.METADATAFLOW));
        }
        if (bean.getMetadataProviderRef() != null) {
            mpa.setMetadataProvider(referenceMapper.mapMaintainable(
                    bean.getMetadataProviderRef(), StructureClassImpl.METADATA_PROVIDER));
        }
        return mpa;
    }
}
