package com.epam.sdmxproxy.common.mapping;

import com.epam.jsdmx.infomodel.sdmx30.Concept;
import com.epam.jsdmx.infomodel.sdmx30.ConceptImpl;
import com.epam.jsdmx.infomodel.sdmx30.ConceptScheme;
import com.epam.jsdmx.infomodel.sdmx30.ConceptSchemeImpl;
import com.epam.jsdmx.infomodel.sdmx30.IsoConceptReferenceImpl;
import com.epam.jsdmx.infomodel.sdmx30.Version;
import com.epam.sdmxproxy.common.utils.StreamUtils;
import io.sdmx.api.sdmx.model.beans.conceptscheme.ConceptBean;
import io.sdmx.api.sdmx.model.beans.conceptscheme.ConceptSchemeBean;
import io.sdmx.api.sdmx.model.beans.reference.ICrossReferenceBean;
import lombok.RequiredArgsConstructor;

import java.util.List;

@RequiredArgsConstructor
public class ConceptSchemeMapper implements Mapper<ConceptSchemeBean> {

    private final TextMapper textMapper;
    private final AnnotationMapper annotationMapper;
    private final RepresentationMapper representationMapper;

    @Override
    public ConceptScheme map(ConceptSchemeBean bean) {
        var cs = new ConceptSchemeImpl();
        cs.setOrganizationId(bean.getAgencyId());
        cs.setId(bean.getId());
        cs.setVersion(Version.createFromString(bean.getVersion().toString()));
        cs.setName(textMapper.map(bean.getNames()));
        cs.setDescription(textMapper.map(bean.getDescriptions()));
        cs.setAnnotations(annotationMapper.map(bean.getAnnotations()));
        cs.setItems(mapConcepts(bean.getItems()));
        return cs;
    }

    private List<Concept> mapConcepts(List<ConceptBean> items) {
        return StreamUtils.streamOfNullable(items)
                .map(this::mapConcept)
                .toList();
    }

    private Concept mapConcept(ConceptBean concept) {
        var c = new ConceptImpl();
        c.setId(concept.getId());
        c.setName(textMapper.map(concept.getNames()));
        c.setDescription(textMapper.map(concept.getDescriptions()));
        c.setAnnotations(annotationMapper.map(concept.getAnnotations()));
        c.setCoreRepresentation(representationMapper.map(concept.getRepresentation()));
        c.setIsoConceptReference(mapIsoRef(concept.getIsoConceptReference()));
        return c;
    }

    private IsoConceptReferenceImpl mapIsoRef(ICrossReferenceBean<?> isoConceptReference) {
        if (isoConceptReference == null) {
            return null;
        }

        var isoRef = new IsoConceptReferenceImpl();
        isoRef.setAgency(isoConceptReference.getReference().getAgencyId());
        isoRef.setConceptId(isoConceptReference.getReference().getFullIdentifiableId());
        isoRef.setSchemeId(isoConceptReference.getReference().getMaintainableId());
        return isoRef;
    }

}
