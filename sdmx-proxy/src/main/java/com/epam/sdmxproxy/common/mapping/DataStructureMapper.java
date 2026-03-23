package com.epam.sdmxproxy.common.mapping;

import com.epam.jsdmx.infomodel.sdmx30.AttributeDescriptorImpl;
import com.epam.jsdmx.infomodel.sdmx30.AttributeRelationship;
import com.epam.jsdmx.infomodel.sdmx30.ComponentImpl;
import com.epam.jsdmx.infomodel.sdmx30.DataAttribute;
import com.epam.jsdmx.infomodel.sdmx30.DataAttributeImpl;
import com.epam.jsdmx.infomodel.sdmx30.DataStructureDefinition;
import com.epam.jsdmx.infomodel.sdmx30.DataStructureDefinitionImpl;
import com.epam.jsdmx.infomodel.sdmx30.DimensionComponent;
import com.epam.jsdmx.infomodel.sdmx30.DimensionComponentImpl;
import com.epam.jsdmx.infomodel.sdmx30.DimensionDescriptorImpl;
import com.epam.jsdmx.infomodel.sdmx30.DimensionImpl;
import com.epam.jsdmx.infomodel.sdmx30.DimensionRelationshipImpl;
import com.epam.jsdmx.infomodel.sdmx30.GroupDimensionDescriptorImpl;
import com.epam.jsdmx.infomodel.sdmx30.Measure;
import com.epam.jsdmx.infomodel.sdmx30.MeasureDescriptorImpl;
import com.epam.jsdmx.infomodel.sdmx30.MeasureImpl;
import com.epam.jsdmx.infomodel.sdmx30.ObservationRelationshipImpl;
import com.epam.jsdmx.infomodel.sdmx30.StreamUtils;
import com.epam.jsdmx.infomodel.sdmx30.StructureClassImpl;
import com.epam.jsdmx.infomodel.sdmx30.TimeDimensionImpl;
import com.epam.jsdmx.infomodel.sdmx30.Version;
import io.sdmx.api.sdmx.constants.ATTRIBUTE_ATTACHMENT_LEVEL;
import io.sdmx.api.sdmx.model.beans.base.ComponentBean;
import io.sdmx.api.sdmx.model.beans.datastructure.AttributeBean;
import io.sdmx.api.sdmx.model.beans.datastructure.AttributeListBean;
import io.sdmx.api.sdmx.model.beans.datastructure.DataStructureBean;
import io.sdmx.api.sdmx.model.beans.datastructure.DimensionBean;
import io.sdmx.api.sdmx.model.beans.datastructure.DimensionListBean;
import io.sdmx.api.sdmx.model.beans.datastructure.GroupBean;
import io.sdmx.api.sdmx.model.beans.datastructure.MeasureDimensionBean;
import io.sdmx.api.sdmx.model.beans.datastructure.MeasureListBean;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@RequiredArgsConstructor
public class DataStructureMapper implements Mapper<DataStructureBean> {

    private final AnnotationMapper annotationMapper;
    private final ReferenceMapper referenceMapper;
    private final RepresentationMapper representationMapper;
    private final TextMapper textMapper;

    @Override
    public DataStructureDefinition map(DataStructureBean dataStructure) {
        final var dsd = new DataStructureDefinitionImpl();
        dsd.setOrganizationId(dataStructure.getAgencyId());
        dsd.setId(dataStructure.getId());
        dsd.setVersion(Version.createFromString(dataStructure.getVersion().toString()));
        dsd.setName(textMapper.map(dataStructure.getNames()));
        dsd.setDescription(textMapper.map(dataStructure.getDescriptions()));
        dsd.setAnnotations(annotationMapper.map(dataStructure.getAnnotations()));

        var dimensionDescriptor = new DimensionDescriptorImpl();
        dimensionDescriptor.setId("DimensionDescriptor");
        dsd.setDimensionDescriptor(dimensionDescriptor);
        mapDimensions(dimensionDescriptor, dataStructure);

        var measureDescriptor = new MeasureDescriptorImpl();
        measureDescriptor.setId("MeasureDescriptor");
        dsd.setMeasureDescriptor(measureDescriptor);
        mapMeasures(measureDescriptor, dataStructure);

        var attributeDescriptor = new AttributeDescriptorImpl();
        attributeDescriptor.setId("AttributeDescriptor");
        dsd.setAttributeDescriptor(attributeDescriptor);
        mapAttributes(attributeDescriptor, dataStructure);

        dsd.setGroupDimensionDescriptor(mapGroupDimensionDescriptors(dataStructure.getGroups()));

        return dsd;
    }

    private void mapDimensions(DimensionDescriptorImpl dimensionDescriptor, DataStructureBean dataStructure) {
        final DimensionListBean dimensionList = dataStructure.getDimensionList();
        if (dimensionList == null) {
            return;
        }
        final List<DimensionBean> dimensions = dimensionList.getDimensions();
        dimensionDescriptor.setComponents(mapDimensions(dimensions));
    }

    private List<DimensionComponent> mapDimensions(List<DimensionBean> dimensions) {
        var dimensionComponentsList = StreamUtils.streamOfNullable(dimensions)
                .sorted(Comparator.comparing(DimensionBean::getPosition))
                .map(this::mapDimension)
                .collect(Collectors.toList());

        var position = 0;
        if (!dimensionComponentsList.isEmpty()) {
            for (var dimensionComponent : dimensionComponentsList) {
                ((DimensionComponentImpl) dimensionComponent).setOrder(position);
                position++;
            }
        }

        return dimensionComponentsList;
    }

    private DimensionComponent mapDimension(DimensionBean dimensionBean) {
        DimensionComponentImpl dim = dimensionBean.isTimeDimension()
                ? new TimeDimensionImpl()
                : new DimensionImpl();
        mapComponent(dim, dimensionBean);
        return dim;
    }

    private void mapComponent(ComponentImpl component, ComponentBean componentBean) {
        component.setId(componentBean.getId());
        component.setConceptIdentity(referenceMapper.mapItem(componentBean.getConceptRef(), StructureClassImpl.CONCEPT));
        component.setLocalRepresentation(representationMapper.map(componentBean.getRepresentation()));
        component.setAnnotations(annotationMapper.map(componentBean.getAnnotations()));
    }

    private void mapMeasures(MeasureDescriptorImpl measureDescriptor, DataStructureBean dataStructure) {
        final MeasureListBean measureList = dataStructure.getMeasureList();
        if (measureList == null) {
            return;
        }
        measureDescriptor.setComponents(mapMeasures(measureList.getMeasures()));
    }

    private List<Measure> mapMeasures(List<MeasureDimensionBean> measureBeans) {
        return measureBeans.stream()
                .map(measureBean -> {
                    var measure = new MeasureImpl();
                    mapComponent(measure, measureBean);
                    return measure;
                })
                .collect(Collectors.toList());
    }

    private void mapAttributes(AttributeDescriptorImpl attributeDescriptor, DataStructureBean dataStructure) {
        final AttributeListBean attributeList = dataStructure.getAttributeList();
        if (attributeList == null) {
            return;
        }
        attributeDescriptor.setComponents(mapAttributes(dataStructure));
    }

    private List<DataAttribute> mapAttributes(DataStructureBean dsd) {
        return StreamUtils.streamOfNullable(dsd.getAttributes())
                .map(attribute -> mapAttribute(attribute, dsd))
                .toList();
    }

    private DataAttribute mapAttribute(AttributeBean attributeBean, DataStructureBean dataStructure) {
        var a = new DataAttributeImpl();
        mapComponent(a, attributeBean);
        mapRelationship(a, attributeBean, dataStructure);
        return a;
    }

    private void mapRelationship(DataAttributeImpl a, AttributeBean attributeBean, DataStructureBean dataStructure) {
        final ATTRIBUTE_ATTACHMENT_LEVEL attachmentLevel = attributeBean.getAttachmentLevel();
        final AttributeRelationship relationship = mapRelationship(attributeBean, dataStructure, attachmentLevel);
        a.setAttributeRelationship(relationship);
    }

    private AttributeRelationship mapRelationship(AttributeBean attributeBean,
                                                  DataStructureBean dataStructure,
                                                  ATTRIBUTE_ATTACHMENT_LEVEL attachmentLevel) {
        return switch (attachmentLevel) {
            case GROUP -> group(attributeBean, dataStructure);
            case DIMENSION_GROUP -> dimensionGroup(attributeBean);
            case OBSERVATION -> new ObservationRelationshipImpl();
            case DATA_SET -> null;
        };
    }

    private AttributeRelationship group(AttributeBean attributeBean, DataStructureBean dataStructure) {
        final String attachmentGroup = attributeBean.getAttachmentGroup();
        final GroupBean group = dataStructure.getGroup(attachmentGroup);
        final List<String> dimensionRefs = group.getDimensionRefs();
        return new DimensionRelationshipImpl(new ArrayList<>(dimensionRefs), new ArrayList<>());
    }

    private AttributeRelationship dimensionGroup(AttributeBean attributeBean) {
        final List<String> dimensionRefs = attributeBean.getDimensionReferences();
        return new DimensionRelationshipImpl(new ArrayList<>(dimensionRefs), new ArrayList<>());
    }

    private List<GroupDimensionDescriptorImpl> mapGroupDimensionDescriptors(List<GroupBean> groups) {
        return StreamUtils.streamOfNullable(groups)
                .map(this::mapGroupDimensionDescriptor)
                .toList();
    }

    private GroupDimensionDescriptorImpl mapGroupDimensionDescriptor(GroupBean groupBean) {
        final String id = groupBean.getId();
        final List<String> dimensionRefs = groupBean.getDimensionRefs();
        var groupDimensionDescriptor = new GroupDimensionDescriptorImpl();
        groupDimensionDescriptor.setId(id);
        groupDimensionDescriptor.setDimensions(dimensionRefs);
        return groupDimensionDescriptor;
    }


}
