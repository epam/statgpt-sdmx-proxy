package com.epam.sdmxproxy.common.mapping;

import com.epam.jsdmx.infomodel.sdmx30.ArtefactReference;
import com.epam.jsdmx.infomodel.sdmx30.DataAttributeImpl;
import com.epam.jsdmx.infomodel.sdmx30.DataStructureDefinition;
import com.epam.jsdmx.infomodel.sdmx30.DimensionImpl;
import com.epam.jsdmx.infomodel.sdmx30.StructureClassImpl;
import io.sdmx.api.sdmx.constants.ATTRIBUTE_ATTACHMENT_LEVEL;
import io.sdmx.api.sdmx.model.beans.datastructure.DataStructureBean;
import io.sdmx.api.sdmx.model.mutable.datastructure.AttributeMutableBean;
import io.sdmx.api.sdmx.model.mutable.datastructure.DataStructureMutableBean;
import io.sdmx.api.sdmx.model.mutable.datastructure.DimensionMutableBean;
import io.sdmx.im.mutable.datastructure.AttributeMutableBeanImpl;
import io.sdmx.im.mutable.datastructure.DataStructureMutableBeanImpl;
import io.sdmx.im.mutable.datastructure.DimensionMutableBeanImpl;
import io.sdmx.utils.sdmx.xs.StructureReferenceBeanImpl;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DataStructureMapperTest {

    private static final String CONCEPT_FREQ_URN = "urn:sdmx:org.sdmx.infomodel.conceptscheme.Concept=IMF:CS(1.0).FREQ";
    private static final String CONCEPT_INDICATOR_URN = "urn:sdmx:org.sdmx.infomodel.conceptscheme.Concept=IMF:CS(1.0).INDICATOR";
    private static final String CONCEPT_OBS_STATUS_URN = "urn:sdmx:org.sdmx.infomodel.conceptscheme.Concept=IMF:CS(1.0).OBS_STATUS";
    private static final String CONCEPT_ROLE_FREQ_URN = "urn:sdmx:org.sdmx.infomodel.conceptscheme.Concept=SDMX:SDMX_CONCEPT_ROLES(1.0).FREQ";
    private static final String CONCEPT_ROLE_TIME_FORMAT_URN = "urn:sdmx:org.sdmx.infomodel.conceptscheme.Concept=SDMX:SDMX_CONCEPT_ROLES(1.0).TIME_FORMAT";
    private static final String CONCEPT_ROLE_OBS_STATUS_URN = "urn:sdmx:org.sdmx.infomodel.conceptscheme.Concept=SDMX:SDMX_CONCEPT_ROLES(1.0).OBS_STATUS";
    private static final String MSD_URN = "urn:sdmx:org.sdmx.infomodel.metadatastructure.MetadataStructure=IMF.RES:MSD_WEO(1.0.0)";

    private final DataStructureMapper sut = newMapper();

    private static DataStructureMapper newMapper() {
        var textMapper = new TextMapper();
        var annotationMapper = new AnnotationMapper(textMapper);
        var referenceMapper = new ReferenceMapper();
        var representationMapper = new RepresentationMapper(referenceMapper);
        return new DataStructureMapper(annotationMapper, referenceMapper, representationMapper, textMapper);
    }

    @Test
    void mapsMsdReferenceWhenPresent() {
        DataStructureBean bean = dsdBuilder()
                .withDimension("FREQ", CONCEPT_FREQ_URN, List.of())
                .withMsdRef(MSD_URN)
                .build();

        DataStructureDefinition mapped = sut.map(bean);

        ArtefactReference msdRef = mapped.getMetadataStructure();
        assertThat(msdRef).isNotNull();
        assertThat(msdRef.getStructureClass()).isEqualTo(StructureClassImpl.METADATA_STRUCTURE);
        assertThat(msdRef.getOrganisationId()).isEqualTo("IMF.RES");
        assertThat(msdRef.getId()).isEqualTo("MSD_WEO");
        assertThat(msdRef.getVersionString()).isEqualTo("1.0.0");
    }

    @Test
    void leavesMetadataStructureNullWhenMsdRefAbsent() {
        DataStructureBean bean = dsdBuilder()
                .withDimension("FREQ", CONCEPT_FREQ_URN, List.of())
                .build();

        DataStructureDefinition mapped = sut.map(bean);

        assertThat(mapped.getMetadataStructure()).isNull();
    }

    @Test
    void mapsDimensionConceptRolesPreservingOrder() {
        DataStructureBean bean = dsdBuilder()
                .withDimension("FREQ", CONCEPT_FREQ_URN, List.of(CONCEPT_ROLE_FREQ_URN, CONCEPT_ROLE_TIME_FORMAT_URN))
                .build();

        DataStructureDefinition mapped = sut.map(bean);

        var dimensions = mapped.getDimensionDescriptor().getComponents();
        assertThat(dimensions).hasSize(1);
        var dim = (DimensionImpl) dimensions.getFirst();
        var roles = dim.getConceptRoles();
        assertThat(roles).hasSize(2);
        assertThat(roles.get(0).getItemId()).isEqualTo("FREQ");
        assertThat(roles.get(1).getItemId()).isEqualTo("TIME_FORMAT");
        assertThat(roles.get(0).getStructureClass()).isEqualTo(StructureClassImpl.CONCEPT);
        assertThat(roles.get(0).getId()).isEqualTo("SDMX_CONCEPT_ROLES");
        assertThat(roles.get(0).getOrganisationId()).isEqualTo("SDMX");
    }

    @Test
    void leavesDimensionConceptRolesEmptyWhenBeanHasNone() {
        DataStructureBean bean = dsdBuilder()
                .withDimension("INDICATOR", CONCEPT_INDICATOR_URN, List.of())
                .build();

        DataStructureDefinition mapped = sut.map(bean);

        var dim = (DimensionImpl) mapped.getDimensionDescriptor().getComponents().getFirst();
        assertThat(dim.getConceptRoles()).isEmpty();
    }

    @Test
    void mapsAttributeConceptRolesPreservingOrder() {
        DataStructureBean bean = dsdBuilder()
                .withDimension("FREQ", CONCEPT_FREQ_URN, List.of())
                .withAttribute("OBS_STATUS", CONCEPT_OBS_STATUS_URN, List.of(CONCEPT_ROLE_OBS_STATUS_URN))
                .build();

        DataStructureDefinition mapped = sut.map(bean);

        var attrs = mapped.getAttributeDescriptor().getComponents();
        assertThat(attrs).hasSize(1);
        var attr = (DataAttributeImpl) attrs.getFirst();
        var roles = attr.getConceptRoles();
        assertThat(roles).hasSize(1);
        assertThat(roles.getFirst().getItemId()).isEqualTo("OBS_STATUS");
        assertThat(roles.getFirst().getStructureClass()).isEqualTo(StructureClassImpl.CONCEPT);
    }

    @Test
    void leavesAttributeConceptRolesEmptyWhenBeanHasNone() {
        DataStructureBean bean = dsdBuilder()
                .withDimension("FREQ", CONCEPT_FREQ_URN, List.of())
                .withAttribute("OBS_STATUS", CONCEPT_OBS_STATUS_URN, List.of())
                .build();

        DataStructureDefinition mapped = sut.map(bean);

        var attr = (DataAttributeImpl) mapped.getAttributeDescriptor().getComponents().getFirst();
        assertThat(attr.getConceptRoles()).isEmpty();
    }

    private static DsdBuilder dsdBuilder() {
        return new DsdBuilder();
    }

    private static final class DsdBuilder {
        private final DataStructureMutableBean mutable = new DataStructureMutableBeanImpl();

        DsdBuilder() {
            mutable.setAgencyId("IMF.RES");
            mutable.setId("DSD_TEST");
            mutable.setVersion("1.0.0");
            mutable.setExternalReference(false);
            mutable.setFinalStructure(false);
            mutable.addName("en", "Test DSD");
        }

        DsdBuilder withMsdRef(String urn) {
            mutable.setMSDReference(new StructureReferenceBeanImpl(urn));
            return this;
        }

        DsdBuilder withDimension(String id, String conceptUrn, List<String> conceptRoleUrns) {
            DimensionMutableBean dim = new DimensionMutableBeanImpl();
            dim.setId(id);
            dim.setConceptRef(new StructureReferenceBeanImpl(conceptUrn));
            dim.setConceptRole(conceptRoleUrns.stream()
                    .map(urn -> (io.sdmx.api.sdmx.model.beans.reference.StructureReferenceBean) new StructureReferenceBeanImpl(urn))
                    .toList());
            mutable.addDimension(dim);
            return this;
        }

        DsdBuilder withAttribute(String id, String conceptUrn, List<String> conceptRoleUrns) {
            AttributeMutableBean attr = new AttributeMutableBeanImpl();
            attr.setId(id);
            attr.setConceptRef(new StructureReferenceBeanImpl(conceptUrn));
            attr.setMandatory(false);
            attr.setAttachmentLevel(ATTRIBUTE_ATTACHMENT_LEVEL.OBSERVATION);
            attr.setConceptRoles(conceptRoleUrns.stream()
                    .map(urn -> (io.sdmx.api.sdmx.model.beans.reference.StructureReferenceBean) new StructureReferenceBeanImpl(urn))
                    .toList());
            mutable.addAttribute(attr);
            return this;
        }

        DataStructureBean build() {
            return mutable.getImmutableInstance();
        }
    }
}
