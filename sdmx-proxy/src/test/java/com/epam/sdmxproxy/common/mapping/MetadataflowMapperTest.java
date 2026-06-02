package com.epam.sdmxproxy.common.mapping;

import com.epam.jsdmx.infomodel.sdmx30.ArtefactReference;
import com.epam.jsdmx.infomodel.sdmx30.Metadataflow;
import com.epam.jsdmx.infomodel.sdmx30.StructureClassImpl;
import io.sdmx.api.sdmx.model.beans.metadatastructure.MetadataFlowBean;
import io.sdmx.im.mutable.metadatastructure.MetadataflowMutableBeanImpl;
import io.sdmx.utils.sdmx.xs.StructureReferenceBeanImpl;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MetadataflowMapperTest {

    private static final String MSD_URN = "urn:sdmx:org.sdmx.infomodel.metadatastructure.MetadataStructure=IMF.RES:MSD_WEO(1.0.0)";

    private final MetadataflowMapper sut = newMapper();

    private static MetadataflowMapper newMapper() {
        var textMapper = new TextMapper();
        var annotationMapper = new AnnotationMapper(textMapper);
        var referenceMapper = new ReferenceMapper();
        return new MetadataflowMapper(annotationMapper, referenceMapper, textMapper);
    }

    @Test
    void mapsIdentityAndMetadataStructureReference() {
        MetadataFlowBean bean = metadataflow(MSD_URN);

        Metadataflow mapped = sut.map(bean);

        assertThat(mapped.getId()).isEqualTo("MDF_WEO");
        assertThat(mapped.getOrganizationId()).isEqualTo("IMF.RES");
        assertThat(mapped.getVersion().toString()).isEqualTo("1.0.0");

        ArtefactReference structureRef = mapped.getStructure();
        assertThat(structureRef).isNotNull();
        assertThat(structureRef.getStructureClass()).isEqualTo(StructureClassImpl.METADATA_STRUCTURE);
        assertThat(structureRef.getOrganisationId()).isEqualTo("IMF.RES");
        assertThat(structureRef.getId()).isEqualTo("MSD_WEO");
        assertThat(structureRef.getVersionString()).isEqualTo("1.0.0");
    }

    private static MetadataFlowBean metadataflow(String msdUrn) {
        var mutable = new MetadataflowMutableBeanImpl();
        mutable.setAgencyId("IMF.RES");
        mutable.setId("MDF_WEO");
        mutable.setVersion("1.0.0");
        mutable.setExternalReference(false);
        mutable.setFinalStructure(false);
        mutable.addName("en", "WEO Metadataflow");
        mutable.setMetadataStructureRef(new StructureReferenceBeanImpl(msdUrn));
        mutable.addTarget(new StructureReferenceBeanImpl(msdUrn));
        return mutable.getImmutableInstance();
    }
}
