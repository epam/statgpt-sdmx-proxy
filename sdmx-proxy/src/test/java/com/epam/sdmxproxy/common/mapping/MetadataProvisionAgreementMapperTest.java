package com.epam.sdmxproxy.common.mapping;

import com.epam.jsdmx.infomodel.sdmx30.ArtefactReference;
import com.epam.jsdmx.infomodel.sdmx30.MetadataProvisionAgreement;
import com.epam.jsdmx.infomodel.sdmx30.StructureClassImpl;
import io.sdmx.api.sdmx.model.beans.metadatastructure.MetadataFlowBean;
import io.sdmx.api.sdmx.model.beans.metadatastructure.MetadataProvisionAgreementBean;
import io.sdmx.api.sdmx.model.beans.reference.ICrossReferenceBean;
import io.sdmx.im.mutable.metadatastructure.MetadataflowMutableBeanImpl;
import io.sdmx.utils.sdmx.xs.StructureReferenceBeanImpl;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The {@code MetadataProvisionAgreement} immutable bean enforces SDMX 2.1 provider-scheme
 * version rules at construction, which makes a hand-built fixture brittle and unrelated to
 * the mapper under test. The mapper is a pure field copy, so we stub the source bean and
 * borrow a real {@link ICrossReferenceBean} (and {@code ISdmxVersion}) from a clean
 * metadataflow bean for the reference fields.
 */
class MetadataProvisionAgreementMapperTest {

    private static final String MSD_URN = "urn:sdmx:org.sdmx.infomodel.metadatastructure.MetadataStructure=IMF.RES:MSD_WEO(1.0.0)";

    private final MetadataProvisionAgreementMapper sut = newMapper();

    private static MetadataProvisionAgreementMapper newMapper() {
        var textMapper = new TextMapper();
        var annotationMapper = new AnnotationMapper(textMapper);
        var referenceMapper = new ReferenceMapper();
        return new MetadataProvisionAgreementMapper(annotationMapper, referenceMapper, textMapper);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void mapsIdentityControlledStructureUsageAndProvider() {
        MetadataFlowBean realFlow = cleanFlow();
        ICrossReferenceBean<?> ref = realFlow.getMetadataStructureRef();

        MetadataProvisionAgreementBean bean = mock(MetadataProvisionAgreementBean.class);
        when(bean.getAgencyId()).thenReturn("IMF.RES");
        when(bean.getId()).thenReturn("MPA_WEO");
        when(bean.getVersion()).thenReturn(realFlow.getVersion());
        when(bean.getMetadataflowRef()).thenReturn((ICrossReferenceBean) ref);
        when(bean.getMetadataProviderRef()).thenReturn((ICrossReferenceBean) ref);

        MetadataProvisionAgreement mapped = sut.map(bean);

        assertThat(mapped.getId()).isEqualTo("MPA_WEO");
        assertThat(mapped.getOrganizationId()).isEqualTo("IMF.RES");
        assertThat(mapped.getVersion().toString()).isEqualTo("1.0.0");

        ArtefactReference usage = mapped.getControlledStructureUsage();
        assertThat(usage).isNotNull();
        assertThat(usage.getStructureClass()).isEqualTo(StructureClassImpl.METADATAFLOW);
        assertThat(usage.getId()).isEqualTo("MSD_WEO");

        ArtefactReference provider = mapped.getMetadataProvider();
        assertThat(provider).isNotNull();
        assertThat(provider.getStructureClass()).isEqualTo(StructureClassImpl.METADATA_PROVIDER);
        assertThat(provider.getId()).isEqualTo("MSD_WEO");
    }

    @Test
    void leavesReferencesNullWhenBeanHasNone() {
        MetadataProvisionAgreementBean bean = mock(MetadataProvisionAgreementBean.class);
        when(bean.getAgencyId()).thenReturn("IMF.RES");
        when(bean.getId()).thenReturn("MPA_WEO");
        when(bean.getVersion()).thenReturn(cleanFlow().getVersion());

        MetadataProvisionAgreement mapped = sut.map(bean);

        assertThat(mapped.getControlledStructureUsage()).isNull();
        assertThat(mapped.getMetadataProvider()).isNull();
    }

    private static MetadataFlowBean cleanFlow() {
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
