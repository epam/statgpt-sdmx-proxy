package com.epam.sdmxproxy.services.sdmxsource;

import io.sdmx.api.exception.SdmxNotImplementedException;
import io.sdmx.api.sdmx.constants.DATASET_ACTION;
import io.sdmx.api.sdmx.constants.SDMX_SCHEMA;
import io.sdmx.api.sdmx.constants.SDMX_STRUCTURE_TYPE;
import io.sdmx.api.sdmx.constants.SdmxConstants;
import io.sdmx.api.sdmx.manager.output.SchemaLocationManager;
import io.sdmx.api.sdmx.manager.retrieval.HeaderRetrievalManager;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;
import io.sdmx.api.sdmx.model.beans.base.IURNMaintainable;
import io.sdmx.api.sdmx.model.beans.base.MaintainableBean;
import io.sdmx.api.sdmx.model.beans.mapping.StructureSetBean;
import io.sdmx.api.sdmx.model.beans.registry.RegistrationBean;
import io.sdmx.api.sdmx.model.beans.validityperiod.ValidatableBean;
import io.sdmx.api.sdmx.model.header.HeaderBean;
import io.sdmx.core.sdmx.api.engine.structure.StructureWriterEngine;
import io.sdmx.core.sdmx.api.model.structure.IExternalMaintainableLinks;
import io.sdmx.core.sdmx.engine.structure.AbstractStructureWriterEngine;
import io.sdmx.core.sdmx.util.StructureWriterUtil;
import io.sdmx.format.ml.api.engine.StaxMaintainableWriterEngine;
import io.sdmx.format.ml.engine.structure.writer.v21.categorisation.StaxCategorisationWriterEngineV21;
import io.sdmx.format.ml.engine.structure.writer.v21.catscheme.StaxCategorySchemeWriterEngineV21;
import io.sdmx.format.ml.engine.structure.writer.v21.codelist.StaxCodelistWriterEngineV21;
import io.sdmx.format.ml.engine.structure.writer.v21.conceptscheme.StaxConceptSchemeWriterEngineV21;
import io.sdmx.format.ml.engine.structure.writer.v21.constraint.StaxContentConstraintWriterEngineV21;
import io.sdmx.format.ml.engine.structure.writer.v21.dataflow.StaxDataflowWriterEngineV21;
import io.sdmx.format.ml.engine.structure.writer.v21.hcl.StaxHclWriterEngineV21;
import io.sdmx.format.ml.engine.structure.writer.v21.metadataflow.StaxMetadataflowWriterEngineV21;
import io.sdmx.format.ml.engine.structure.writer.v21.msd.StaxMetadataStructureWriterEngineV21;
import io.sdmx.format.ml.engine.structure.writer.v21.orgscheme.StaxAgencySchemeWriterEngineV21;
import io.sdmx.format.ml.engine.structure.writer.v21.orgscheme.StaxDataConsumerSchemeWriterEngineV21;
import io.sdmx.format.ml.engine.structure.writer.v21.orgscheme.StaxDataProviderSchemeWriterEngineV21;
import io.sdmx.format.ml.engine.structure.writer.v21.orgscheme.StaxOrgUnitSchemeWriterEngineV21;
import io.sdmx.format.ml.engine.structure.writer.v21.process.StaxProcessWriterEngineV21;
import io.sdmx.format.ml.engine.structure.writer.v21.provision.StaxProvisionWriterEngineV21;
import io.sdmx.format.ml.engine.structure.writer.v21.registration.StaxRegistrationWriterEngineV21;
import io.sdmx.format.ml.engine.structure.writer.v21.reportingtaxonomy.StaxReportingTaxonomyWriterEngineV21;
import io.sdmx.format.ml.engine.structure.writer.v21.structureset.StaxStructureSetWriterEngineV21;
import io.sdmx.format.ml.engine.structure.writer.v21.transformation.StaxCustomTypeSchemeWriterEngineV21;
import io.sdmx.format.ml.engine.structure.writer.v21.transformation.StaxNamePersonalisationSchemeWriterEngineV21;
import io.sdmx.format.ml.engine.structure.writer.v21.transformation.StaxRulesetSchemeWriterEngineV21;
import io.sdmx.format.ml.engine.structure.writer.v21.transformation.StaxTransformationSchemeWriterEngineV21;
import io.sdmx.format.ml.engine.structure.writer.v21.transformation.StaxUserDefinedOperatorSchemeWriterEngineV21;
import io.sdmx.format.ml.engine.structure.writer.v21.transformation.StaxVtlMappingSchemeWriterEngineV21;
import io.sdmx.format.ml.engine.structure.writer.v21.util.StaxHeaderWriterUtil;
import io.sdmx.im.format.SDMXMetadataStandard;
import io.sdmx.im.header.HeaderBeanImpl;
import io.sdmx.im.util.ItemValidityPeriodHelper;
import io.sdmx.utils.core.object.ObjectUtil;
import io.sdmx.utils.core.xml.Namespace;
import io.sdmx.utils.core.xml.StaxWriter;

import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public abstract class CustomStaxAbstractStructureWriterEngineV21 extends AbstractStructureWriterEngine implements StructureWriterEngine {
    protected Namespace messageNs;
    protected Namespace structureNs;
    protected Namespace commonNs;
    protected Namespace registryNs;
    protected boolean prettyPrint = false;
    private SDMX_SCHEMA ouputVersion;
    private SchemaLocationManager schemaLocationManager;
    private HeaderRetrievalManager headerRetrievalManager;
    private Map<SDMX_STRUCTURE_TYPE, StaxMaintainableWriterEngine> writerEngines = new HashMap<>();

    public CustomStaxAbstractStructureWriterEngineV21(SDMX_SCHEMA ouputVersion,
                                                      Namespace messageNs,
                                                      Namespace structureNs,
                                                      Namespace commonNs,
                                                      Namespace registryNs,
                                                      SchemaLocationManager schemaLocationManager,
                                                      HeaderRetrievalManager headerRetrievalManager) {
        super(true, SDMX_STRUCTURE_TYPE.AGENCY_SCHEME,
                SDMX_STRUCTURE_TYPE.DATA_PROVIDER_SCHEME,
                SDMX_STRUCTURE_TYPE.DATA_CONSUMER_SCHEME,
                SDMX_STRUCTURE_TYPE.CATEGORISATION,
                SDMX_STRUCTURE_TYPE.CATEGORY_SCHEME,
                SDMX_STRUCTURE_TYPE.CODE_LIST,
                SDMX_STRUCTURE_TYPE.CONCEPT_SCHEME,
                SDMX_STRUCTURE_TYPE.CONSTRAINT,
                SDMX_STRUCTURE_TYPE.CONTENT_CONSTRAINT,
                SDMX_STRUCTURE_TYPE.ADVANCED_RELEASE_CALENDAR,
                SDMX_STRUCTURE_TYPE.DATAFLOW,
                SDMX_STRUCTURE_TYPE.METADATA_FLOW,
                SDMX_STRUCTURE_TYPE.DATA_PROVIDER_SCHEME,
                SDMX_STRUCTURE_TYPE.DSD,
                SDMX_STRUCTURE_TYPE.HIERARCHY,
                SDMX_STRUCTURE_TYPE.MSD,
                SDMX_STRUCTURE_TYPE.ORGANISATION_SCHEME,
                SDMX_STRUCTURE_TYPE.ORGANISATION_UNIT_SCHEME,
                SDMX_STRUCTURE_TYPE.PROCESS,
                SDMX_STRUCTURE_TYPE.PROVISION_AGREEMENT,
                SDMX_STRUCTURE_TYPE.REGISTRATION,
                SDMX_STRUCTURE_TYPE.STRUCTURE_SET,
                SDMX_STRUCTURE_TYPE.REPORTING_TAXONOMY,
                SDMX_STRUCTURE_TYPE.VTL_CUSTOM_TYPE_SCHEME,
                SDMX_STRUCTURE_TYPE.VTL_NAME_PERSONALISATION_SCHEME,
                SDMX_STRUCTURE_TYPE.VTL_RULESET_SCHEME,
                SDMX_STRUCTURE_TYPE.VTL_TRANSFORMATION_SCHEME,
                SDMX_STRUCTURE_TYPE.VTL_USER_DEFINED_OPERATOR_SCHEME,
                SDMX_STRUCTURE_TYPE.VTL_MAPPING_SCHEME);

        this.schemaLocationManager = schemaLocationManager;
        this.headerRetrievalManager = headerRetrievalManager;

        this.ouputVersion = ouputVersion;
        this.messageNs = messageNs;
        this.structureNs = structureNs;
        this.commonNs = commonNs;
        this.registryNs = registryNs;

        writerEngines.put(SDMX_STRUCTURE_TYPE.AGENCY_SCHEME, StaxAgencySchemeWriterEngineV21.getInstance());
        writerEngines.put(SDMX_STRUCTURE_TYPE.DATA_PROVIDER_SCHEME, StaxDataProviderSchemeWriterEngineV21.getInstance());
        writerEngines.put(SDMX_STRUCTURE_TYPE.DATA_CONSUMER_SCHEME, StaxDataConsumerSchemeWriterEngineV21.getInstance());
        writerEngines.put(SDMX_STRUCTURE_TYPE.ORGANISATION_UNIT_SCHEME, StaxOrgUnitSchemeWriterEngineV21.getInstance());
        writerEngines.put(SDMX_STRUCTURE_TYPE.CATEGORISATION, StaxCategorisationWriterEngineV21.getInstance());
        writerEngines.put(SDMX_STRUCTURE_TYPE.CATEGORY_SCHEME, StaxCategorySchemeWriterEngineV21.getInstance());
        writerEngines.put(SDMX_STRUCTURE_TYPE.CODE_LIST, StaxCodelistWriterEngineV21.getInstance());
        writerEngines.put(SDMX_STRUCTURE_TYPE.CONCEPT_SCHEME, StaxConceptSchemeWriterEngineV21.getInstance());
        writerEngines.put(SDMX_STRUCTURE_TYPE.CONTENT_CONSTRAINT, StaxContentConstraintWriterEngineV21.getInstance());
        writerEngines.put(SDMX_STRUCTURE_TYPE.ADVANCED_RELEASE_CALENDAR, StaxContentConstraintWriterEngineV21.getInstance());
        writerEngines.put(SDMX_STRUCTURE_TYPE.DATAFLOW, StaxDataflowWriterEngineV21.getInstance());
        writerEngines.put(SDMX_STRUCTURE_TYPE.MSD, StaxMetadataStructureWriterEngineV21.getInstance());
        writerEngines.put(SDMX_STRUCTURE_TYPE.METADATA_FLOW, StaxMetadataflowWriterEngineV21.getInstance());
        writerEngines.put(SDMX_STRUCTURE_TYPE.DSD, CustomStaxDsdWriterEngineV21.getInstance());
        writerEngines.put(SDMX_STRUCTURE_TYPE.HIERARCHY, StaxHclWriterEngineV21.getInstance());
        writerEngines.put(SDMX_STRUCTURE_TYPE.PROCESS, StaxProcessWriterEngineV21.getInstance());
        writerEngines.put(SDMX_STRUCTURE_TYPE.PROVISION_AGREEMENT, StaxProvisionWriterEngineV21.getInstance());
        writerEngines.put(SDMX_STRUCTURE_TYPE.REGISTRATION, StaxRegistrationWriterEngineV21.getInstance());
        writerEngines.put(SDMX_STRUCTURE_TYPE.STRUCTURE_SET, StaxStructureSetWriterEngineV21.getInstance());
        writerEngines.put(SDMX_STRUCTURE_TYPE.REPORTING_TAXONOMY, StaxReportingTaxonomyWriterEngineV21.getInstance());

        writerEngines.put(SDMX_STRUCTURE_TYPE.VTL_CUSTOM_TYPE_SCHEME, StaxCustomTypeSchemeWriterEngineV21.getInstance());
        writerEngines.put(SDMX_STRUCTURE_TYPE.VTL_NAME_PERSONALISATION_SCHEME, StaxNamePersonalisationSchemeWriterEngineV21.getInstance());
        writerEngines.put(SDMX_STRUCTURE_TYPE.VTL_RULESET_SCHEME, StaxRulesetSchemeWriterEngineV21.getInstance());
        writerEngines.put(SDMX_STRUCTURE_TYPE.VTL_TRANSFORMATION_SCHEME, StaxTransformationSchemeWriterEngineV21.getInstance());
        writerEngines.put(SDMX_STRUCTURE_TYPE.VTL_USER_DEFINED_OPERATOR_SCHEME, StaxUserDefinedOperatorSchemeWriterEngineV21.getInstance());
        writerEngines.put(SDMX_STRUCTURE_TYPE.VTL_MAPPING_SCHEME, StaxVtlMappingSchemeWriterEngineV21.getInstance());
    }

    @Override
    public void writeStructure(MaintainableBean bean, IExternalMaintainableLinks additionalLinks, OutputStream out) {
        StructureWriterUtil.writeStructure(this, bean, additionalLinks, out);
    }

    @Override
    public void writeStructures(SdmxBeans beans, Map<IURNMaintainable<?>, IExternalMaintainableLinks> additionalLinks, OutputStream out) {
        StaxWriter writer = null;
        try {
            if (beans.hasStructuresOfType(SDMX_STRUCTURE_TYPE.REGISTRATION)) {
                writer = new StaxWriter(out, getSchemaLocation(), prettyPrint, messageNs, registryNs, commonNs);
                writeRegistrations(writer, beans, out);
            } else {
                writer = new StaxWriter(out, getSchemaLocation(), prettyPrint, messageNs, structureNs, commonNs);
                writeStructuresInternal(writer, beans, out);
            }
        } finally {
            if (writer != null) {
                writer.close();
            }
        }
    }

    /**
     * Writes structures, expects the document to have been started and relevant namespaces to have been added
     *
     * @param writer
     * @param beans
     */
    public void writeStructures(StaxWriter writer, SdmxBeans beans) {
        writeOrganisations(writer, beans);
        writeStructures(writer, "Dataflows", beans, SDMX_STRUCTURE_TYPE.DATAFLOW);
        writeStructures(writer, "Metadataflows", beans, SDMX_STRUCTURE_TYPE.METADATA_FLOW);
        writeStructures(writer, "CategorySchemes", beans, SDMX_STRUCTURE_TYPE.CATEGORY_SCHEME);
        writeStructures(writer, "Categorisations", beans, SDMX_STRUCTURE_TYPE.CATEGORISATION);
        writeStructures(writer, "Codelists", beans, SDMX_STRUCTURE_TYPE.CODE_LIST);
        writeStructures(writer, "HierarchicalCodelists", beans, SDMX_STRUCTURE_TYPE.HIERARCHY);
        writeStructures(writer, "Concepts", beans, SDMX_STRUCTURE_TYPE.CONCEPT_SCHEME);
        writeStructures(writer, "MetadataStructures", beans, SDMX_STRUCTURE_TYPE.MSD);
        writeStructures(writer, "DataStructures", beans, SDMX_STRUCTURE_TYPE.DSD);
        writeStructures(writer, "ReportingTaxonomies", beans, SDMX_STRUCTURE_TYPE.REPORTING_TAXONOMY);
        writeStructures(writer, "Processes", beans, SDMX_STRUCTURE_TYPE.PROCESS);
        writeConstraints(writer, beans);
        writeStructures(writer, "ProvisionAgreements", beans, SDMX_STRUCTURE_TYPE.PROVISION_AGREEMENT);
        writeStructures(writer, "CustomTypes", beans, SDMX_STRUCTURE_TYPE.VTL_CUSTOM_TYPE_SCHEME);
        writeStructures(writer, "VtlMappings", beans, SDMX_STRUCTURE_TYPE.VTL_MAPPING_SCHEME);
        writeStructures(writer, "NamePersonalisations", beans, SDMX_STRUCTURE_TYPE.VTL_NAME_PERSONALISATION_SCHEME);
        writeStructures(writer, "Rulesets", beans, SDMX_STRUCTURE_TYPE.VTL_RULESET_SCHEME);
        writeStructures(writer, "Transformations", beans, SDMX_STRUCTURE_TYPE.VTL_TRANSFORMATION_SCHEME);
        writeStructures(writer, "UserDefinedOperators", beans, SDMX_STRUCTURE_TYPE.VTL_USER_DEFINED_OPERATOR_SCHEME);
    }

    private String getSchemaLocation() {
        String messageSchema = messageNs.getNamespaceURL();
        String schemaName = SdmxConstants.getSchemaName(messageSchema);
        if (schemaLocationManager != null) {
            return messageSchema + " " + schemaLocationManager.getSchemaLocation(ouputVersion) + schemaName;
        }
        return null;
    }

    protected void writeStructuresInternal(StaxWriter writer, SdmxBeans beans, OutputStream out) {
        writer.startDocument(messageNs, getDocumentRoot());
        writeHeader(writer, beans.getHeader(), true);
        afterHeader(writer, beans.getAction());

        if (getDocumentRoot().equals("RegistryInterface")) {
            writer.writeStartElement(structureNs, "Structures");
        } else {
            writer.writeStartElement(messageNs, "Structures");
        }
        writeStructures(writer, beans);
    }


    @SuppressWarnings({"unchecked"})
    protected void writeRegistrations(StaxWriter writer, SdmxBeans beans, OutputStream out) {
        Set<RegistrationBean> registrations = beans.getRegistrations();
        writer.startDocument(messageNs, "RegistryInterface");
        writeHeader(writer, beans.getHeader(), true);
        writer.writeStartElement(messageNs, "QueryRegistrationResponse");


        StaxMaintainableWriterEngine writerEngine = getWriterEngine(SDMX_STRUCTURE_TYPE.REGISTRATION);
        writer.writeStartElement(registryNs, "StatusMessage");
        writer.writeAttribute("status", "Success");
        writer.writeEndElement();


        for (RegistrationBean registration : registrations) {
            writer.writeStartElement(registryNs, "QueryResult");
            writer.writeAttribute("timeSeriesMatch", "false");  //TODO What is this?
            writer.writeStartElement(registryNs, "DataResult");
            writerEngine.writeMaintainable(registration, null, writer);
            writer.writeEndElement(); //End Data Result
            writer.writeEndElement(); //End Query Result
        }
        writer.writeEndElement(); //End QueryRegistrationResponse
    }

    protected abstract String getDocumentRoot();

    protected abstract void afterHeader(StaxWriter writer, DATASET_ACTION action);

    protected void writeHeader(StaxWriter writer, HeaderBean header, boolean includeReceiver) {
        if (header == null && headerRetrievalManager != null) {
            header = headerRetrievalManager.getHeader();
        }
        if (header == null) {
            header = new HeaderBeanImpl("ZZZ");
        }
        boolean isRegistryInterfaceMessage = getDocumentRoot().equals("RegistryInterface");
        StaxHeaderWriterUtil.writeHeader(writer, messageNs, header, includeReceiver, isRegistryInterfaceMessage);
    }


    private void writeOrganisations(StaxWriter writer, SdmxBeans beans) {
        if (ObjectUtil.validCollection(beans.getAgenciesSchemes())
                || ObjectUtil.validCollection(beans.getDataProviderSchemes())
                || ObjectUtil.validCollection(beans.getDataConsumerSchemes())
                || ObjectUtil.validCollection(beans.getOrganisationUnitSchemes())) {
            writer.writeStartElement(structureNs, "OrganisationSchemes");
            writeStructures(writer, beans, SDMX_STRUCTURE_TYPE.AGENCY_SCHEME);
            writeStructures(writer, beans, SDMX_STRUCTURE_TYPE.DATA_PROVIDER_SCHEME);
            writeStructures(writer, beans, SDMX_STRUCTURE_TYPE.DATA_CONSUMER_SCHEME);
            writeStructures(writer, beans, SDMX_STRUCTURE_TYPE.ORGANISATION_UNIT_SCHEME);
            writer.writeEndElement();
        }
    }

    private void writeConstraints(StaxWriter writer, SdmxBeans beans) {
        if (ObjectUtil.validCollection(beans.getContentConstraintBeans())) {
            writer.writeStartElement(structureNs, "Constraints");
            writeStructures(writer, beans, SDMX_STRUCTURE_TYPE.CONTENT_CONSTRAINT);
            writer.writeEndElement();
        }
    }

    @SuppressWarnings("unchecked")
    private void writeStructures(StaxWriter writer, String startNode,
                                 SdmxBeans beans,
                                 SDMX_STRUCTURE_TYPE structureType) {
        if (ObjectUtil.validCollection(beans.getMaintainables(structureType))) {
            StaxMaintainableWriterEngine writerEngine = getWriterEngine(structureType);
            writer.writeStartElement(structureNs, startNode);
            for (MaintainableBean maint : beans.getMaintainables(structureType)) {
				/*if (structureType == SDMX_STRUCTURE_TYPE.CODE_LIST) {
					maint = ItemValidityPeriodHelper.potentiallyMutateIt((CodelistBean)maint);
				} else if (structureType == SDMX_STRUCTURE_TYPE.CONCEPT_SCHEME) {
					maint = ItemValidityPeriodHelper.potentiallyMutateIt((ConceptSchemeBean)maint);
				}*/
                if (maint instanceof ValidatableBean) {
                    maint = ItemValidityPeriodHelper.potentiallyMutateIt((ValidatableBean<?>) maint);
                } else if (maint instanceof StructureSetBean) {
                    maint = ItemValidityPeriodHelper.potentiallyMutateIt((StructureSetBean) maint);
                }
                writerEngine.writeMaintainable(maint, null, writer);
            }
            writer.writeEndElement();
        }
    }

    @Override
    public boolean isSupportedType(MaintainableBean structure) {
        if (structure.getDefaultStandard() != SDMXMetadataStandard.getInstance()) {
            return false;
        }
        return super.isSupportedType(structure) && structure.isCompatible(this.ouputVersion);
    }


    /**
     * For use with structures which are grouped for example agency schemes, data provider schemes, content constrains
     *
     * @param beans
     * @param staxWriter
     * @param structureType
     */
    @SuppressWarnings("unchecked")
    private void writeStructures(StaxWriter writer, SdmxBeans beans,
                                 SDMX_STRUCTURE_TYPE structureType) {
        if (ObjectUtil.validCollection(beans.getMaintainables(structureType))) {
            StaxMaintainableWriterEngine writerEngine = getWriterEngine(structureType);
            for (MaintainableBean maint : beans.getMaintainables(structureType)) {
                writerEngine.writeMaintainable(maint, null, writer);
            }
        }
    }

    protected StaxMaintainableWriterEngine getWriterEngine(SDMX_STRUCTURE_TYPE structureType) {
        StaxMaintainableWriterEngine writerEngine = writerEngines.get(structureType);
        if (writerEngine == null) {
            throw new SdmxNotImplementedException("No support for writing structure '" + structureType.getType() + "' in version '" + this.ouputVersion + "'");
        }
        return writerEngine;
    }
}