package com.epam.sdmxproxy.common.mapping;

import com.epam.jsdmx.infomodel.sdmx30.MetadataAttribute;
import com.epam.jsdmx.infomodel.sdmx30.MetadataAttributeDescriptorImpl;
import com.epam.jsdmx.infomodel.sdmx30.MetadataAttributeImpl;
import com.epam.jsdmx.infomodel.sdmx30.MetadataStructureDefinition;
import com.epam.jsdmx.infomodel.sdmx30.MetadataStructureDefinitionImpl;
import com.epam.jsdmx.infomodel.sdmx30.Representation;
import com.epam.jsdmx.infomodel.sdmx30.StreamUtils;
import com.epam.jsdmx.infomodel.sdmx30.StructureClassImpl;
import com.epam.jsdmx.infomodel.sdmx30.Version;
import io.sdmx.api.sdmx.model.beans.base.RepresentationBean;
import io.sdmx.api.sdmx.model.beans.metadatastructure.MetadataAttributeBean;
import io.sdmx.api.sdmx.model.beans.metadatastructure.MetadataStructureDefinitionBean;
import lombok.RequiredArgsConstructor;

import java.util.List;

@RequiredArgsConstructor
public class MetadataStructureDefinitionMapper implements Mapper<MetadataStructureDefinitionBean> {

    private final AnnotationMapper annotationMapper;
    private final ReferenceMapper referenceMapper;
    private final RepresentationMapper representationMapper;
    private final TextMapper textMapper;

    @Override
    public MetadataStructureDefinition map(MetadataStructureDefinitionBean bean) {
        var msd = new MetadataStructureDefinitionImpl();
        msd.setOrganizationId(bean.getAgencyId());
        msd.setId(bean.getId());
        msd.setVersion(Version.createFromString(bean.getVersion().toString()));
        msd.setName(textMapper.map(bean.getNames()));
        msd.setDescription(textMapper.map(bean.getDescriptions()));
        msd.setAnnotations(annotationMapper.map(bean.getAnnotations()));

        var descriptor = new MetadataAttributeDescriptorImpl();
        descriptor.setId("MetadataAttributeDescriptor");
        descriptor.setComponents(mapAttributes(bean.getMetadataAttributes()));
        msd.setAttributeDescriptor(descriptor);
        return msd;
    }

    private List<MetadataAttribute> mapAttributes(List<MetadataAttributeBean> beans) {
        return StreamUtils.streamOfNullable(beans)
                .map(this::mapAttribute)
                .toList();
    }

    private MetadataAttribute mapAttribute(MetadataAttributeBean bean) {
        var attr = new MetadataAttributeImpl();
        attr.setId(bean.getId());
        if (bean.getConceptRef() != null) {
            attr.setConceptIdentity(referenceMapper.mapItem(bean.getConceptRef(), StructureClassImpl.CONCEPT));
        }
        RepresentationBean representation = bean.getRepresentation();
        attr.setLocalRepresentation(mapRepresentation(representation));
        attr.setAnnotations(annotationMapper.map(bean.getAnnotations()));
        attr.setPresentational(bean.getPresentational());
        attr.setMaxOccurs(bean.getMaxOccurs());
        // minOccurs has no convenience getter on the bean; it is stored on the RepresentationBean
        // (where getMaxOccurs() reads from too). Leave the jsdmx default (1) when the source omits it.
        if (representation != null && representation.getMinOccurs() != null) {
            attr.setMinOccurs(representation.getMinOccurs());
        }
        List<MetadataAttribute> nested = mapAttributes(bean.getMetadataAttributes());
        if (!nested.isEmpty()) {
            attr.setHierarchy(nested);
        }
        return attr;
    }

    /**
     * A presentational metadata attribute carries no representation. sdmx-core can still
     * surface a non-null but empty {@link RepresentationBean} for it (neither enumerated
     * nor a text format), which {@link RepresentationMapper} rejects. Treat that as "no
     * local representation" rather than letting it bubble up as a conversion failure.
     */
    private Representation mapRepresentation(RepresentationBean representation) {
        if (representation == null
                || (representation.getRepresentation() == null && representation.getTextFormat() == null)) {
            return null;
        }
        return representationMapper.map(representation);
    }
}
