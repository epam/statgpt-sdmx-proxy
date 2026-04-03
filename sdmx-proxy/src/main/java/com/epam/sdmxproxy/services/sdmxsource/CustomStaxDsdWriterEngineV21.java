package com.epam.sdmxproxy.services.sdmxsource;

import io.sdmx.api.sdmx.model.beans.base.ComponentBean;
import io.sdmx.api.sdmx.model.beans.datastructure.AttributeBean;
import io.sdmx.api.sdmx.model.beans.datastructure.AttributeListBean;
import io.sdmx.api.sdmx.model.beans.datastructure.DataStructureBean;
import io.sdmx.api.sdmx.model.beans.datastructure.DimensionBean;
import io.sdmx.api.sdmx.model.beans.datastructure.DimensionListBean;
import io.sdmx.api.sdmx.model.beans.datastructure.GroupBean;
import io.sdmx.api.sdmx.model.beans.datastructure.MeasureListBean;
import io.sdmx.api.sdmx.model.beans.datastructure.PrimaryMeasureBean;
import io.sdmx.api.sdmx.model.beans.reference.ICrossReferenceBean;
import io.sdmx.api.singleton.IFusionSingleton;
import io.sdmx.format.ml.constant.StaxStructureWriterUtil;
import io.sdmx.format.ml.engine.structure.writer.v21.AbstractV21Writer;
import io.sdmx.format.ml.engine.structure.writer.v21.util.StaxAnnotableWriterUtil;
import io.sdmx.format.ml.engine.structure.writer.v21.util.StaxIdentifiableWriterUtil;
import io.sdmx.format.ml.engine.structure.writer.v21.util.StaxRefUtil;
import io.sdmx.format.ml.engine.structure.writer.v21.util.StaxRepresentationWriterUtil;
import io.sdmx.utils.core.application.FusionBeanStore;
import io.sdmx.utils.core.object.ObjectUtil;
import io.sdmx.utils.core.xml.StaxWriter;

import java.util.List;
import java.util.Map;

public class CustomStaxDsdWriterEngineV21 extends AbstractV21Writer<DataStructureBean> implements IFusionSingleton {
    private static CustomStaxDsdWriterEngineV21 INSTANCE;

    //Private constructor - lazy load instance
    private CustomStaxDsdWriterEngineV21() {
    }

    public static CustomStaxDsdWriterEngineV21 getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new CustomStaxDsdWriterEngineV21();
            FusionBeanStore.registerInstance(INSTANCE);
        }
        return INSTANCE;
    }

    @Override
    public void destroyInstance() {
        INSTANCE = null;
    }

    @Override
    protected String getRootNodeName() {
        return "DataStructure";
    }

    @Override
    protected void writeMaintainableInternal(DataStructureBean maint, StaxWriter writer) {
        writer.writeStartElement(NS, "DataStructureComponents");
        writeDimensionList(maint.getDimensionList(), writer);
        writeGroups(maint.getGroups(), writer);
        writeAttributeList(maint.getAttributeList(), writer);
        writeMeasureList(maint.getMeasureList(), writer);
        writer.writeEndElement(); //End DataStructureComponents
    }

    private void writeDimensionList(DimensionListBean dimList, StaxWriter writer) {
        if (dimList == null) {
            return;
        }
        StaxIdentifiableWriterUtil.writeBean(dimList, "DimensionList", writer);
        int pos = 0;
        for (DimensionBean dim : dimList.getDimensions()) {
            pos++;
            if (dim.isMeasureDimension()) {
                writer.writeStartElement(NS, "MeasureDimension");
            } else if (dim.isTimeDimension()) {
                writer.writeStartElement(NS, "TimeDimension");
            } else {
                writer.writeStartElement(NS, "Dimension");
            }
            writeComponent(dim, writer, "position", Integer.toString(pos));
            if (dim.getConceptScheme() != null) {
                writer.writeStartElement(StaxStructureWriterUtil.STRCUTURE_NS, "LocalRepresentation");
                StaxRefUtil.writeReference(dim.getConceptScheme(), writer, "Enumeration");
                writer.writeEndElement();
            }
            for (ICrossReferenceBean<?> conceptRole : dim.getConceptRole()) {
                StaxRefUtil.writeReference(conceptRole, writer, "ConceptRole");
            }
            writer.writeEndElement(); //End [X]Dimension
        }
        writer.writeEndElement(); //End DimensionList
    }


    private void writeComponent(ComponentBean comp, StaxWriter writer, String attrId, String attrVal) {
        Map<String, String> attributes = StaxIdentifiableWriterUtil.getAttributes(comp);
        attributes.put(attrId, attrVal);
        writer.writeAttributes(attributes);
        StaxAnnotableWriterUtil.writeBean(comp, writer);
        StaxRefUtil.writeReference(comp.getConceptRef(), writer, "ConceptIdentity");
        if (comp.getRepresentation() != null) {
            StaxRepresentationWriterUtil.writeRepresentation(comp.getRepresentation(), writer, "LocalRepresentation", false);
        }

    }

    private void writeGroups(List<GroupBean> groups, StaxWriter writer) {
        if (ObjectUtil.validCollection(groups)) {
            for (GroupBean groupBean : groups) {
                StaxIdentifiableWriterUtil.writeBean(groupBean, "Group", writer);
                for (String dimRef : groupBean.getDimensionRefs()) {
                    writer.writeStartElement(NS, "GroupDimension");
                    StaxRefUtil.writeLocalReference(dimRef, writer, "DimensionReference");
                    writer.writeEndElement();
                }
                writer.writeEndElement(); //End Group
            }
        }
    }

    private void writeAttributeList(AttributeListBean attrList, StaxWriter writer) {
        if (attrList == null || attrList.getAttributes() == null || attrList.getAttributes().size() == 0) {
            return;
        }
        StaxIdentifiableWriterUtil.writeBean(attrList, "AttributeList", writer);
        for (AttributeBean attr : attrList.getAttributes()) {
            writer.writeStartElement(NS, "Attribute");
            String assignmentStatus = attr.isMandatory() ? "Mandatory" : "Conditional";
            writeComponent(attr, writer, "assignmentStatus", assignmentStatus);
            for (ICrossReferenceBean<?> conceptRole : attr.getConceptRoles()) {
                StaxRefUtil.writeReference(conceptRole, writer, "ConceptRole");
            }
            writer.writeStartElement(NS, "AttributeRelationship");
            switch (attr.getAttachmentLevel()) {
                case DATA_SET:
                    writer.writeStartElement(NS, "None");
                    writer.writeEndElement(); //End None
                    break;
                case DIMENSION_GROUP:
                    for (String dimRef : attr.getDimensionReferences()) {
                        StaxRefUtil.writeLocalReference(dimRef, writer, "Dimension");
                    }
                    break;
                case GROUP:
                    StaxRefUtil.writeLocalReference(attr.getAttachmentGroup(), writer, "Group");
                    break;
                case OBSERVATION:
                    if (ObjectUtil.validCollection(attr.getDimensionReferences())) {
                        for (String dimRef : attr.getDimensionReferences()) {
                            StaxRefUtil.writeLocalReference(dimRef, writer, "Dimension");
                        }
                    } else {
                        StaxRefUtil.writeLocalReference(PrimaryMeasureBean.FIXED_ID, writer, "PrimaryMeasure");
                    }
                    break;
            }
            writer.writeEndElement(); //End AttributeRelationship
            writer.writeEndElement(); //End Attribute
        }
        writer.writeEndElement(); //End AttributeList
    }

    private void writeMeasureList(MeasureListBean measList, StaxWriter writer) {
        if (measList == null) {
            return;
        }
        StaxIdentifiableWriterUtil.writeBean(measList, "MeasureList", writer);

        PrimaryMeasureBean pm = measList.getPrimaryMeasure();
        if (pm != null) {
            StaxIdentifiableWriterUtil.writeBean(pm, "PrimaryMeasure", writer);
            StaxRefUtil.writeReference(pm.getConceptRef(), writer, "ConceptIdentity");
            if (pm.getRepresentation() != null) {
                StaxRepresentationWriterUtil.writeRepresentation(pm.getRepresentation(), writer, "LocalRepresentation", false);
            }
            writer.writeEndElement(); //End PrimaryMeasure //TODO CONSIDER COMMITING THIS TO SDMXSOURCE
        }
        writer.writeEndElement(); //End MeasureList
    }
}
