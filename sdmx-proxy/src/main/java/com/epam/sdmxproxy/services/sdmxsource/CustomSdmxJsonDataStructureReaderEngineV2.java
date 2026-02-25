package com.epam.sdmxproxy.services.sdmxsource;

import io.sdmx.api.exception.SdmxSemmanticException;
import io.sdmx.api.sdmx.constants.ATTRIBUTE_ATTACHMENT_LEVEL;
import io.sdmx.api.sdmx.constants.SDMX_STRUCTURE_TYPE;
import io.sdmx.api.sdmx.model.beans.base.MaintainableBean;
import io.sdmx.api.sdmx.model.beans.datastructure.DataStructureBean;
import io.sdmx.api.sdmx.model.beans.reference.StructureReferenceBean;
import io.sdmx.api.sdmx.model.mutable.datastructure.AttributeListMutableBean;
import io.sdmx.api.sdmx.model.mutable.datastructure.AttributeMutableBean;
import io.sdmx.api.sdmx.model.mutable.datastructure.DataStructureMutableBean;
import io.sdmx.api.sdmx.model.mutable.datastructure.DimensionListMutableBean;
import io.sdmx.api.sdmx.model.mutable.datastructure.DimensionMutableBean;
import io.sdmx.api.sdmx.model.mutable.datastructure.GroupMutableBean;
import io.sdmx.api.sdmx.model.mutable.datastructure.MeasureListMutableBean;
import io.sdmx.api.sdmx.model.mutable.datastructure.MeasureMutableBean;
import io.sdmx.api.singleton.IFusionSingleton;
import io.sdmx.format.json.engine.structure.reader.sdmx.AbstractSdmxJsonReaderEngine;
import io.sdmx.format.json.engine.structure.reader.util.SdmxJsonComponentUtil;
import io.sdmx.format.json.engine.structure.reader.util.SdmxJsonIdentifiableUtil;
import io.sdmx.im.mutable.datastructure.AttributeListMutableBeanImpl;
import io.sdmx.im.mutable.datastructure.AttributeMutableBeanImpl;
import io.sdmx.im.mutable.datastructure.DataStructureMutableBeanImpl;
import io.sdmx.im.mutable.datastructure.DimensionListMutableBeanImpl;
import io.sdmx.im.mutable.datastructure.DimensionMutableBeanImpl;
import io.sdmx.im.mutable.datastructure.GroupMutableBeanImpl;
import io.sdmx.im.mutable.datastructure.MeasureListMutableBeanImpl;
import io.sdmx.im.mutable.datastructure.MeasureMutableBeanImpl;
import io.sdmx.utils.core.application.FusionBeanStore;
import io.sdmx.utils.json.JsonReader;
import io.sdmx.utils.sdmx.xs.StructureReferenceBeanImpl;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class CustomSdmxJsonDataStructureReaderEngineV2 extends AbstractSdmxJsonReaderEngine<DataStructureMutableBean> implements IFusionSingleton {
    private static CustomSdmxJsonDataStructureReaderEngineV2 INSTANCE;

    public static CustomSdmxJsonDataStructureReaderEngineV2 getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new CustomSdmxJsonDataStructureReaderEngineV2();
            FusionBeanStore.registerInstance(INSTANCE);
        }
        return INSTANCE;
    }

    @Override
    public void destroyInstance() {
        INSTANCE = null;
    }

    @Override
    public String getContainerElement() {
        return "dataStructures";
    }

    @Override
    protected Class<? extends MaintainableBean> getMaintainableType() {
        return DataStructureBean.class;
    }

    @Override
    protected DataStructureMutableBean getMutable() {
        return new DataStructureMutableBeanImpl();
    }

    @Override
    protected boolean processMaintainableProperty(String fieldName, DataStructureMutableBean mutable,
                                                  JsonReader reader) {
        switch (fieldName) {
            case "metadata":
                mutable.setMSDReference(StructureReferenceBeanImpl.buildAndVerify(reader.getValueAsString(), SDMX_STRUCTURE_TYPE.MSD));
                return true;
            case "dataStructureComponents":
                buildDataStructure(reader, mutable);
                return true;
        }
        return false;
    }

    private void buildDataStructure(JsonReader jReader, DataStructureMutableBean dsdBean) {
        while (jReader.moveNext()) {
            if (jReader.isEndObject()) {
                break;
            }
            String fieldName = jReader.getCurrentFieldName();
            switch (fieldName) {
                case "attributeList":
                    AttributeListMutableBean attras = getAttributes(jReader);
                    dsdBean.setAttributeList(attras);
                    break;
                case "dimensionList":
                    DimensionListMutableBean dims = readDimensions(jReader);
                    dsdBean.setDimensionList(dims);
                    break;
                case "groups":
                    List<GroupMutableBean> groups = readGroups(jReader);
                    dsdBean.setGroups(groups);
                    break;
                case "measureList":
                    MeasureListMutableBean measureList = readMeasureList(jReader);
                    dsdBean.setMeasureList(measureList);
                    break;
            }
        }
    }

    private MeasureListMutableBean readMeasureList(JsonReader jReader) {
        MeasureListMutableBean ret = new MeasureListMutableBeanImpl();
        while (jReader.moveNext()) {
            if (jReader.isEndObject()) {
                break;
            }
            if (SdmxJsonIdentifiableUtil.processBean(ret, jReader)) {
                continue;
            }
            if (jReader.getCurrentFieldName().equals("measures")) {
                ret.setMeasures(readMeasures(jReader));
            }
        }
        return ret;
    }

    private List<MeasureMutableBean> readMeasures(JsonReader jReader) {
        List<MeasureMutableBean> retList = new ArrayList<>();
        while (jReader.moveNext()) {
            if (jReader.isEndArray()) {
                break;
            }
            if (jReader.isStartObject()) {
                MeasureMutableBean measure = new MeasureMutableBeanImpl();
                while (jReader.moveNext()) {
                    if (jReader.isEndObject()) {
                        break;
                    }
                    if (SdmxJsonComponentUtil.processBean(measure, jReader, true)) {
                        continue;
                    }
                }
                retList.add(measure);
            }
        }
        return retList;
    }

    private List<GroupMutableBean> readGroups(JsonReader jReader) {
        List<GroupMutableBean> retList = new ArrayList<>();
        while (jReader.moveNext()) {
            if (jReader.isEndArray()) {
                break;
            }
            GroupMutableBean group = new GroupMutableBeanImpl();
            while (jReader.moveNext()) {
                if (jReader.isEndObject()) {
                    break;
                }
                String fieldName = jReader.getCurrentFieldName();
                if (SdmxJsonIdentifiableUtil.processBean(group, jReader)) {
                    continue;
                }
                switch (fieldName) {
                    case "attachmentConstraint":
                        String value = jReader.getValueAsString();
                        group.setAttachmentConstraintRef(new StructureReferenceBeanImpl(value));
                        break;
                    case "groupDimensions":
                        List<String> groupDimIds = jReader.readStringArray();
                        group.setDimensionRef(groupDimIds);
                        break;
                }
            }
            retList.add(group);
        }
        return retList;
    }

    private DimensionListMutableBean readDimensions(JsonReader jReader) {
        DimensionListMutableBean dimList = new DimensionListMutableBeanImpl();
        while (jReader.moveNext()) {
            if (SdmxJsonIdentifiableUtil.processBean(dimList, jReader)) {
                continue;
            }
            if (jReader.isEndObject()) {
                break;
            }
            String fieldName = jReader.getCurrentFieldName();
            switch (fieldName) {
                case "dimensions":
                    List<DimensionMutableBean> dims = readDims(jReader, SDMX_STRUCTURE_TYPE.DIMENSION);
                    addDimensions(dims, dimList);
                    break;
                case "timeDimension":
                    DimensionMutableBean aDim = readSingleDimension(jReader, SDMX_STRUCTURE_TYPE.TIME_DIMENSION);
                    dimList.addDimension(aDim);
                    break;
                case "timeDimensions":
                    // This is not really applicable for SDMX JSON V2, but for lenience purposes leave it in
                    List<DimensionMutableBean> timeDims = readDims(jReader, SDMX_STRUCTURE_TYPE.TIME_DIMENSION);
                    addDimensions(timeDims, dimList);
                    break;
            }
        }
        return dimList;
    }

    private void addDimensions(List<DimensionMutableBean> dims, DimensionListMutableBean dimList) {
        dims.forEach(dimList::addDimension);
    }

    private List<DimensionMutableBean> readDims(JsonReader jReader, SDMX_STRUCTURE_TYPE dimensionType) {
        List<DimensionMutableBean> rtestList = new ArrayList<>();
        while (jReader.moveNext()) {
            if (jReader.isEndArray()) {
                break;
            }
            rtestList.add(readSingleDimension(jReader, dimensionType));
        }
        return rtestList;
    }

    private DimensionMutableBean readSingleDimension(JsonReader jReader, SDMX_STRUCTURE_TYPE dimensionType) {
        DimensionMutableBean dim = new DimensionMutableBeanImpl();
        if (dimensionType == SDMX_STRUCTURE_TYPE.TIME_DIMENSION) {
            dim.setTimeDimension(true);
        } else if (dimensionType == SDMX_STRUCTURE_TYPE.MEASURE_DIMENSION) {
            dim.setMeasureDimension(true);
        }
        while (jReader.moveNext()) {
            if (jReader.isEndObject()) {
                break;
            }
            String fieldName = jReader.getCurrentFieldName();
            if (SdmxJsonComponentUtil.processBean(dim, jReader, true)) {
                continue;
            }
            switch (fieldName) {
                case "conceptRoles":
                    List<StructureReferenceBean> concepts = readConceptRoles(jReader);
                    dim.setConceptRole(concepts);
                    break;
            }
        }
        return dim;
    }

    private AttributeListMutableBean getAttributes(JsonReader jReader) {
        AttributeListMutableBean attrList = new AttributeListMutableBeanImpl();
        while (jReader.moveNext()) {

            /*
                Hack to skip metadataAttributeUsages. It is absent in sdmx source infomodel. So we will skip it until some better times.
                Also, the main reason to have this hack is to get rid of problem with having zero dimensions, attributes and measures in parsed dsd.
                Look into StreamingStructureConversionServiceTest.shouldConvertStructures_Imf_3_0_Dsd_NoDimension to understand what I'm talking about.
             */
            //TODO CONSIDER COMMITING THIS TO SDMXSOURCE
            if (jReader.isStartArray() && jReader.getCurrentFieldName().equals("metadataAttributeUsages")) {
                jReader.moveToEndArray();
                continue;
            }

            if (jReader.isEndObject()) {
                break;
            }
            String currentFieldName = jReader.getCurrentFieldName();
            if (SdmxJsonIdentifiableUtil.processBean(attrList, jReader)) {
                continue;
            }
            if (currentFieldName.equals("attributes")) {
                while (jReader.moveNext()) {
                    if (jReader.isEndArray()) {
                        break;
                    }
                    AttributeMutableBean atBean = new AttributeMutableBeanImpl();
                    while (jReader.moveNext()) {
                        if (jReader.isEndObject()) {
                            break;
                        }
                        String fieldName = jReader.getCurrentFieldName();
                        if (SdmxJsonComponentUtil.processBean(atBean, jReader, true)) {
                            continue;
                        }
                        switch (fieldName) {
                            case "usage":
                                String value = jReader.getValueAsString().toLowerCase();
                                if (!value.equals("optional") && !value.equals("mandatory")) {
                                    throw new SdmxSemmanticException("'usage' has illegal value of: ");
                                }
                                atBean.setMandatory(!value.equals("optional"));
                                break;
                            case "measureRelationship":
                                atBean.setMeasureReferences(jReader.readStringArray());
                                break;
                            case "attributeRelationship":
                                readAttributeRelationship(jReader, atBean);
                                break;
                            case "conceptRoles":
                                List<StructureReferenceBean> concepts = readConceptRoles(jReader);
                                atBean.setConceptRoles(concepts);
                                break;
                        }
                    }
                    attrList.addAttribute(atBean);
                }
            }
        }
        return attrList;
    }


    private List<StructureReferenceBean> readConceptRoles(JsonReader jReader) {
        return jReader.readStringArray().stream().map(StructureReferenceBeanImpl::new).collect(Collectors.toList());
    }

    private void readAttributeRelationship(JsonReader jReader, AttributeMutableBean atBean) {
        while (jReader.moveNext()) {
            if (jReader.isEndObject()) {
                break;
            }
            String fieldName = jReader.getCurrentFieldName();
            String value = jReader.getValueAsString();
            switch (fieldName) {
                case "dimensions":
                    atBean.setAttachmentLevel(ATTRIBUTE_ATTACHMENT_LEVEL.DIMENSION_GROUP);
                    List<String> dims = jReader.readStringArray();
                    atBean.setDimensionReferences(dims);
                    break;
                case "group":
                    atBean.setAttachmentLevel(ATTRIBUTE_ATTACHMENT_LEVEL.GROUP);
                    atBean.setAttachmentGroup(value);
                    break;
                case "dataflow":
                    atBean.setAttachmentLevel(ATTRIBUTE_ATTACHMENT_LEVEL.DATA_SET);
                    jReader.moveToEndObject(); //empty node
                    break;
                case "none":
                    atBean.setAttachmentLevel(ATTRIBUTE_ATTACHMENT_LEVEL.DATA_SET);
                    jReader.moveToEndObject();
                    break;
                case "observation":
                    atBean.setAttachmentLevel(ATTRIBUTE_ATTACHMENT_LEVEL.OBSERVATION);
                    jReader.moveToEndObject();  //empty node
                    break;
            }
        }
    }
}
