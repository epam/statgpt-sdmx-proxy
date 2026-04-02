package com.epam.sdmxproxy.services.sdmxsource;

import io.sdmx.api.sdmx.model.beans.SdmxBeans;
import io.sdmx.format.json.engine.structure.reader.sdmx.v2.SdmxJsonAgencySchemeReaderEngineV2;
import io.sdmx.format.json.engine.structure.reader.sdmx.v2.SdmxJsonCategorisationReaderEngineV2;
import io.sdmx.format.json.engine.structure.reader.sdmx.v2.SdmxJsonCategorySchemeMapReaderEngineV2;
import io.sdmx.format.json.engine.structure.reader.sdmx.v2.SdmxJsonCategorySchemeReaderEngineV2;
import io.sdmx.format.json.engine.structure.reader.sdmx.v2.SdmxJsonCodelistReaderEngineV2;
import io.sdmx.format.json.engine.structure.reader.sdmx.v2.SdmxJsonConceptSchemeMapReaderEngineV2;
import io.sdmx.format.json.engine.structure.reader.sdmx.v2.SdmxJsonConceptSchemeReaderEngineV2;
import io.sdmx.format.json.engine.structure.reader.sdmx.v2.SdmxJsonContentConstraintReaderEngineV2;
import io.sdmx.format.json.engine.structure.reader.sdmx.v2.SdmxJsonCustomTypeSchemeReaderEngineV2;
import io.sdmx.format.json.engine.structure.reader.sdmx.v2.SdmxJsonDataConsumerSchemeReaderEngineV2;
import io.sdmx.format.json.engine.structure.reader.sdmx.v2.SdmxJsonDataProviderSchemeReaderEngineV2;
import io.sdmx.format.json.engine.structure.reader.sdmx.v2.SdmxJsonDataflowReaderEngineV2;
import io.sdmx.format.json.engine.structure.reader.sdmx.v2.SdmxJsonGeoGridCodelistReaderEngineV2;
import io.sdmx.format.json.engine.structure.reader.sdmx.v2.SdmxJsonGeographicCodelistReaderEngineV2;
import io.sdmx.format.json.engine.structure.reader.sdmx.v2.SdmxJsonHierarchyAssociationReaderEngineV2;
import io.sdmx.format.json.engine.structure.reader.sdmx.v2.SdmxJsonMetadataConstraintReaderEngineV2;
import io.sdmx.format.json.engine.structure.reader.sdmx.v2.SdmxJsonMetadataProviderSchemeReaderEngineV2;
import io.sdmx.format.json.engine.structure.reader.sdmx.v2.SdmxJsonMetadataProvisionReaderEngineV2;
import io.sdmx.format.json.engine.structure.reader.sdmx.v2.SdmxJsonMetadataStructureReaderEngineV2;
import io.sdmx.format.json.engine.structure.reader.sdmx.v2.SdmxJsonMetadataflowReaderEngineV2;
import io.sdmx.format.json.engine.structure.reader.sdmx.v2.SdmxJsonNamePersonalisationSchemeReaderEngineV2;
import io.sdmx.format.json.engine.structure.reader.sdmx.v2.SdmxJsonOrganisationSchemeMapReaderEngineV2;
import io.sdmx.format.json.engine.structure.reader.sdmx.v2.SdmxJsonOrganisationUnitSchemeReaderEngineV2;
import io.sdmx.format.json.engine.structure.reader.sdmx.v2.SdmxJsonProvisionAgreementReaderEngineV2;
import io.sdmx.format.json.engine.structure.reader.sdmx.v2.SdmxJsonReportingTaxonomyMapReaderEngineV2;
import io.sdmx.format.json.engine.structure.reader.sdmx.v2.SdmxJsonReportingTaxonomyReaderEngineV2;
import io.sdmx.format.json.engine.structure.reader.sdmx.v2.SdmxJsonRepresentationMapReaderEngineV2;
import io.sdmx.format.json.engine.structure.reader.sdmx.v2.SdmxJsonRulesetSchemeReaderEngineV2;
import io.sdmx.format.json.engine.structure.reader.sdmx.v2.SdmxJsonStructureMapReaderEngineV2;
import io.sdmx.format.json.engine.structure.reader.sdmx.v2.SdmxJsonTransformationSchemeReaderEngineV2;
import io.sdmx.format.json.engine.structure.reader.sdmx.v2.SdmxJsonUserDefinedOperatorSchemeReaderEngineV2;
import io.sdmx.format.json.engine.structure.reader.sdmx.v2.SdmxJsonValueListReaderEngineV2;
import io.sdmx.format.json.engine.structure.reader.sdmx.v2.SdmxJsonVtlMappingSchemeReaderEngineV2;
import io.sdmx.format.json.manager.AbstractJsonStructureReaderManager;
import io.sdmx.utils.json.JsonReader;
import org.springframework.stereotype.Service;

@Service
public class CustomSdmxJsonStructureReaderManagerV2 extends AbstractJsonStructureReaderManager {

    public CustomSdmxJsonStructureReaderManagerV2() {
        registerReader(SdmxJsonAgencySchemeReaderEngineV2.getInstance());
        registerReader(SdmxJsonCategorisationReaderEngineV2.getInstance());
        registerReader(SdmxJsonCategorySchemeMapReaderEngineV2.getInstance());
        registerReader(SdmxJsonCategorySchemeReaderEngineV2.getInstance());
        registerReader(SdmxJsonCodelistReaderEngineV2.getInstance());
        registerReader(SdmxJsonConceptSchemeMapReaderEngineV2.getInstance());
        registerReader(SdmxJsonConceptSchemeReaderEngineV2.getInstance());
        registerReader(SdmxJsonContentConstraintReaderEngineV2.getInstance());
        registerReader(SdmxJsonCustomTypeSchemeReaderEngineV2.getInstance());
        registerReader(SdmxJsonDataConsumerSchemeReaderEngineV2.getInstance());
        registerReader(SdmxJsonDataProviderSchemeReaderEngineV2.getInstance());
        registerReader(CustomSdmxJsonDataStructureReaderEngineV2.getInstance());
        registerReader(SdmxJsonDataflowReaderEngineV2.getInstance());
        registerReader(SdmxJsonGeoGridCodelistReaderEngineV2.getInstance());
        registerReader(SdmxJsonGeographicCodelistReaderEngineV2.getInstance());
        registerReader(CustomSdmxJsonHierarchicalCodelistReaderEngineV2.getInstance());
        registerReader(SdmxJsonHierarchyAssociationReaderEngineV2.getInstance());
        registerReader(SdmxJsonMetadataConstraintReaderEngineV2.getInstance());
        registerReader(SdmxJsonMetadataProviderSchemeReaderEngineV2.getInstance());
        registerReader(SdmxJsonMetadataProvisionReaderEngineV2.getInstance());
        registerReader(SdmxJsonMetadataStructureReaderEngineV2.getInstance());
        registerReader(SdmxJsonMetadataflowReaderEngineV2.getInstance());
        registerReader(SdmxJsonNamePersonalisationSchemeReaderEngineV2.getInstance());
        registerReader(SdmxJsonOrganisationSchemeMapReaderEngineV2.getInstance());
        registerReader(SdmxJsonOrganisationUnitSchemeReaderEngineV2.getInstance());
        registerReader(SdmxJsonProvisionAgreementReaderEngineV2.getInstance());
        registerReader(SdmxJsonReportingTaxonomyReaderEngineV2.getInstance());
        registerReader(SdmxJsonReportingTaxonomyMapReaderEngineV2.getInstance());
        registerReader(SdmxJsonRepresentationMapReaderEngineV2.getInstance());
        registerReader(SdmxJsonRulesetSchemeReaderEngineV2.getInstance());
        registerReader(SdmxJsonStructureMapReaderEngineV2.getInstance());
        registerReader(SdmxJsonTransformationSchemeReaderEngineV2.getInstance());
        registerReader(SdmxJsonUserDefinedOperatorSchemeReaderEngineV2.getInstance());
        registerReader(SdmxJsonValueListReaderEngineV2.getInstance());
        registerReader(SdmxJsonVtlMappingSchemeReaderEngineV2.getInstance());
    }

    @Override
    protected boolean skipToData(JsonReader jReader, SdmxBeans sdmxBeans) {
        outer:
        while (jReader.moveNextStartObject()) {
            while (jReader.moveNext()) {
                String currentFieldName = jReader.getCurrentFieldName();
                switch (currentFieldName) {
                    case "meta":
                        readMetaInfo(jReader, sdmxBeans);
                        break;
                    case "errors":
                        // process error info
                        continue outer;
                    case "data":
                        jReader.moveNextStartArray();
                        return true;
                }
            }
        }
        return false;
    }
}
