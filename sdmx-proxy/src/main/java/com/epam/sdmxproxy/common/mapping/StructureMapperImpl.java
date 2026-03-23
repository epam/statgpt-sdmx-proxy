package com.epam.sdmxproxy.common.mapping;

import com.epam.jsdmx.infomodel.sdmx30.Artefacts;
import com.epam.jsdmx.infomodel.sdmx30.ArtefactsImpl;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;
import io.sdmx.api.sdmx.model.beans.categoryscheme.CategorySchemeBean;
import io.sdmx.api.sdmx.model.beans.codelist.CodelistBean;
import io.sdmx.api.sdmx.model.beans.conceptscheme.ConceptSchemeBean;
import io.sdmx.api.sdmx.model.beans.datastructure.DataStructureBean;
import io.sdmx.api.sdmx.model.beans.datastructure.DataflowBean;
import io.sdmx.api.sdmx.model.beans.registry.ContentConstraintBean;

import java.util.function.Predicate;

public class StructureMapperImpl implements StructureMapper {

    private final Mapper<CodelistBean> codelistMapper;
    private final Mapper<ConceptSchemeBean> conceptSchemeMapper;
    private final Mapper<DataStructureBean> dataStructureMapper;
    private final Mapper<DataflowBean> dataflowMapper;
    private final Mapper<CategorySchemeBean> categorySchemesMapper;
    private final Mapper<ContentConstraintBean> contentConstraintMapper;

    public StructureMapperImpl() {
        this(null);
    }

    public StructureMapperImpl(Predicate<String> annotationPredicate) {
        final var textMapper = new TextMapper();
        final var annotationMapper = annotationPredicate != null ? new AnnotationMapper(textMapper, annotationPredicate) : new AnnotationMapper(textMapper);
        final var referenceMapper = new ReferenceMapper();
        final var representationMapper = new RepresentationMapper(referenceMapper);

        codelistMapper = new CodelistMapper(textMapper, annotationMapper);
        conceptSchemeMapper = new ConceptSchemeMapper(textMapper, annotationMapper, representationMapper);
        dataStructureMapper = new DataStructureMapper(annotationMapper, referenceMapper, representationMapper, textMapper);
        dataflowMapper = new DataflowMapper(annotationMapper, referenceMapper, textMapper);
        categorySchemesMapper = new CategorySchemesMapper(annotationMapper, textMapper);
        contentConstraintMapper = new ContentConstraintMapper(annotationMapper, referenceMapper, textMapper);
    }

    @Override
    public Artefacts map(SdmxBeans beans) {
        var artefacts = new ArtefactsImpl();
        artefacts.addMaintainables(codelistMapper.map(beans.getCodelists()));
        artefacts.addMaintainables(conceptSchemeMapper.map(beans.getConceptSchemes()));
        artefacts.addMaintainables(dataStructureMapper.map(beans.getDataStructures()));
        artefacts.addMaintainables(dataflowMapper.map(beans.getDataflows()));
        artefacts.addMaintainables(categorySchemesMapper.map(beans.getCategorySchemes()));
        artefacts.addMaintainables(contentConstraintMapper.map(beans.getContentConstraintBeans()));
        return artefacts;
    }

    @Override
    public Mapper<DataStructureBean> getDataStructureMapper() {
        return dataStructureMapper;
    }
}
