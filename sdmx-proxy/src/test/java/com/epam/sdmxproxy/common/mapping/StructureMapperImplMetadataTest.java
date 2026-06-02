package com.epam.sdmxproxy.common.mapping;

import com.epam.jsdmx.infomodel.sdmx30.Artefacts;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;
import io.sdmx.api.sdmx.model.beans.metadatastructure.MetadataFlowBean;
import io.sdmx.api.sdmx.model.beans.metadatastructure.MetadataProvisionAgreementBean;
import io.sdmx.api.sdmx.model.beans.metadatastructure.MetadataStructureDefinitionBean;
import io.sdmx.api.sdmx.model.beans.reference.ICrossReferenceBean;
import io.sdmx.im.mutable.metadatastructure.MetadataAttributeMutableBeanImpl;
import io.sdmx.im.mutable.metadatastructure.MetadataStructureDefinitionMutableBeanImpl;
import io.sdmx.im.mutable.metadatastructure.MetadataflowMutableBeanImpl;
import io.sdmx.utils.sdmx.xs.StructureReferenceBeanImpl;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies {@link StructureMapperImpl} forwards the three metadata artefact types
 * ({@code MetadataStructureDefinition}, {@code Metadataflow}, {@code MetadataProvisionAgreement})
 * into the output {@link Artefacts} (design 033). Before the fix these were parsed into
 * {@link SdmxBeans} but silently dropped by the mapper.
 *
 * <p>{@link SdmxBeans} is stubbed: only the metadata getters are populated, every other
 * artefact getter returns {@code null}, which the mappers tolerate via
 * {@code StreamUtils.streamOfNullable}.
 */
class StructureMapperImplMetadataTest {

    private static final String MSD_URN = "urn:sdmx:org.sdmx.infomodel.metadatastructure.MetadataStructure=IMF.RES:MSD_WEO(1.0.0)";
    private static final String CONCEPT_DOI = "urn:sdmx:org.sdmx.infomodel.conceptscheme.Concept=IMF.RES:CS_WEO(1.0).DOI";

    private final StructureMapperImpl sut = new StructureMapperImpl();

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void forwardsAllThreeMetadataArtefactTypes() {
        MetadataFlowBean flow = metadataflow();
        ICrossReferenceBean<?> ref = flow.getMetadataStructureRef();

        MetadataProvisionAgreementBean mpa = mock(MetadataProvisionAgreementBean.class);
        when(mpa.getAgencyId()).thenReturn("IMF.RES");
        when(mpa.getId()).thenReturn("MPA_WEO");
        when(mpa.getVersion()).thenReturn(flow.getVersion());
        when(mpa.getMetadataflowRef()).thenReturn((ICrossReferenceBean) ref);

        SdmxBeans beans = mock(SdmxBeans.class);
        when(beans.getMetadataStructures()).thenReturn(Set.of(msd()));
        when(beans.getMetadataflows()).thenReturn(Set.of(flow));
        when(beans.getMetadataProvisions()).thenReturn(Set.of(mpa));

        Artefacts artefacts = sut.map(beans);

        assertThat(artefacts.getMetadataStructureDefinitions())
                .as("MSD must be forwarded to Artefacts")
                .hasSize(1);
        assertThat(artefacts.getMetadataflows())
                .as("Metadataflow must be forwarded to Artefacts")
                .hasSize(1);
        assertThat(artefacts.getMetadataProvisionAgreements())
                .as("MetadataProvisionAgreement must be forwarded to Artefacts")
                .hasSize(1);
    }

    private static MetadataStructureDefinitionBean msd() {
        var mutable = new MetadataStructureDefinitionMutableBeanImpl();
        mutable.setAgencyId("IMF.RES");
        mutable.setId("MSD_WEO");
        mutable.setVersion("1.0.0");
        mutable.setExternalReference(false);
        mutable.setFinalStructure(false);
        mutable.addName("en", "WEO MSD");
        var attr = new MetadataAttributeMutableBeanImpl();
        attr.setId("DOI");
        attr.setConceptRef(new StructureReferenceBeanImpl(CONCEPT_DOI));
        attr.setMaxOccurs(1);
        attr.setPresentational(false);
        mutable.addMetadataAttributes(attr);
        return mutable.getImmutableInstance();
    }

    private static MetadataFlowBean metadataflow() {
        var mutable = new MetadataflowMutableBeanImpl();
        mutable.setAgencyId("IMF.RES");
        mutable.setId("MDF_WEO");
        mutable.setVersion("1.0.0");
        mutable.setExternalReference(false);
        mutable.setFinalStructure(false);
        mutable.addName("en", "WEO Metadataflow");
        mutable.setMetadataStructureRef(new StructureReferenceBeanImpl(MSD_URN));
        mutable.addTarget(new StructureReferenceBeanImpl(MSD_URN));
        return mutable.getImmutableInstance();
    }
}
