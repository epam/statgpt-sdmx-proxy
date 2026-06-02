package com.epam.sdmxproxy.common.mapping;

import com.epam.jsdmx.infomodel.sdmx30.MetadataAttribute;
import com.epam.jsdmx.infomodel.sdmx30.MetadataStructureDefinition;
import com.epam.jsdmx.infomodel.sdmx30.StructureClassImpl;
import io.sdmx.api.sdmx.model.beans.metadatastructure.MetadataStructureDefinitionBean;
import io.sdmx.api.sdmx.model.mutable.metadatastructure.MetadataAttributeMutableBean;
import io.sdmx.im.mutable.metadatastructure.MetadataAttributeMutableBeanImpl;
import io.sdmx.im.mutable.metadatastructure.MetadataStructureDefinitionMutableBeanImpl;
import io.sdmx.utils.sdmx.xs.StructureReferenceBeanImpl;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MetadataStructureDefinitionMapperTest {

    private static final String CONCEPT_DOI = "urn:sdmx:org.sdmx.infomodel.conceptscheme.Concept=IMF.RES:CS_WEO(1.0).DOI";
    private static final String CONCEPT_AUTHOR = "urn:sdmx:org.sdmx.infomodel.conceptscheme.Concept=IMF.RES:CS_WEO(1.0).AUTHOR";
    private static final String CONCEPT_NOTE = "urn:sdmx:org.sdmx.infomodel.conceptscheme.Concept=IMF.RES:CS_WEO(1.0).NOTE";

    private final MetadataStructureDefinitionMapper sut = newMapper();

    private static MetadataStructureDefinitionMapper newMapper() {
        var textMapper = new TextMapper();
        var annotationMapper = new AnnotationMapper(textMapper);
        var referenceMapper = new ReferenceMapper();
        var representationMapper = new RepresentationMapper(referenceMapper);
        return new MetadataStructureDefinitionMapper(annotationMapper, referenceMapper, representationMapper, textMapper);
    }

    @Test
    void mapsIdentityAndName() {
        MetadataStructureDefinition mapped = sut.map(msdBuilder().build());

        assertThat(mapped.getId()).isEqualTo("MSD_WEO");
        assertThat(mapped.getOrganizationId()).isEqualTo("IMF.RES");
        assertThat(mapped.getVersion().toString()).isEqualTo("1.0.0");
        assertThat(mapped.getName().get("en")).contains("WEO MSD");
    }

    @Test
    void mapsAttributesWithConceptIdentityAndOccurs() {
        MetadataStructureDefinitionBean bean = msdBuilder()
                .withAttribute("DOI", CONCEPT_DOI, 1, false)
                .withAttribute("AUTHOR", CONCEPT_AUTHOR, 99, true)
                .build();

        MetadataStructureDefinition mapped = sut.map(bean);

        List<MetadataAttribute> attrs = mapped.getAttributeDescriptor().getComponents();
        assertThat(attrs).hasSize(2);

        MetadataAttribute doi = attrs.get(0);
        assertThat(doi.getId()).isEqualTo("DOI");
        assertThat(doi.getConceptIdentity()).isNotNull();
        assertThat(doi.getConceptIdentity().getStructureClass()).isEqualTo(StructureClassImpl.CONCEPT);
        assertThat(doi.getConceptIdentity().getItemId()).isEqualTo("DOI");
        assertThat(doi.getMaxOccurs()).isEqualTo(1);
        assertThat(doi.isPresentational()).isFalse();

        MetadataAttribute author = attrs.get(1);
        assertThat(author.getId()).isEqualTo("AUTHOR");
        assertThat(author.getMaxOccurs()).isEqualTo(99);
        assertThat(author.isPresentational()).isTrue();
    }

    @Test
    void preservesSourceMinOccursAndDefaultsToOneWhenAbsent() {
        MetadataStructureDefinitionBean bean = msdBuilder()
                .withOccursAttribute("OPTIONAL", CONCEPT_DOI, 0, 1)
                .withOccursAttribute("DEFAULTED", CONCEPT_AUTHOR, null, 1)
                .build();

        List<MetadataAttribute> attrs = sut.map(bean).getAttributeDescriptor().getComponents();

        assertThat(attrs.get(0).getId()).isEqualTo("OPTIONAL");
        assertThat(attrs.get(0).getMinOccurs()).isEqualTo(0);
        assertThat(attrs.get(1).getId()).isEqualTo("DEFAULTED");
        assertThat(attrs.get(1).getMinOccurs()).isEqualTo(1);
    }

    @Test
    void mapsNestedMetadataAttributesAsHierarchy() {
        MetadataStructureDefinitionBean bean = msdBuilder()
                .withNestedAttribute("AUTHOR", CONCEPT_AUTHOR, "NOTE", CONCEPT_NOTE)
                .build();

        MetadataStructureDefinition mapped = sut.map(bean);

        List<MetadataAttribute> attrs = mapped.getAttributeDescriptor().getComponents();
        assertThat(attrs).hasSize(1);
        MetadataAttribute parent = attrs.get(0);
        assertThat(parent.getId()).isEqualTo("AUTHOR");
        assertThat(parent.getHierarchy()).hasSize(1);
        assertThat(parent.getHierarchy().get(0).getId()).isEqualTo("NOTE");
    }

    @Test
    void mapsEmptyAttributeDescriptorWhenNoAttributes() {
        MetadataStructureDefinition mapped = sut.map(msdBuilder().build());

        assertThat(mapped.getAttributeDescriptor()).isNotNull();
        assertThat(mapped.getAttributeDescriptor().getComponents()).isEmpty();
    }

    private static MsdBuilder msdBuilder() {
        return new MsdBuilder();
    }

    private static final class MsdBuilder {
        private final MetadataStructureDefinitionMutableBeanImpl mutable = new MetadataStructureDefinitionMutableBeanImpl();

        MsdBuilder() {
            mutable.setAgencyId("IMF.RES");
            mutable.setId("MSD_WEO");
            mutable.setVersion("1.0.0");
            mutable.setExternalReference(false);
            mutable.setFinalStructure(false);
            mutable.addName("en", "WEO MSD");
        }

        MsdBuilder withAttribute(String id, String conceptUrn, int maxOccurs, boolean presentational) {
            mutable.addMetadataAttributes(attribute(id, conceptUrn, maxOccurs, presentational));
            return this;
        }

        MsdBuilder withOccursAttribute(String id, String conceptUrn, Integer minOccurs, int maxOccurs) {
            MetadataAttributeMutableBean attr = attribute(id, conceptUrn, maxOccurs, false);
            if (minOccurs != null) {
                attr.setMinOccurs(minOccurs);
            }
            mutable.addMetadataAttributes(attr);
            return this;
        }

        MsdBuilder withNestedAttribute(String parentId, String parentConcept, String childId, String childConcept) {
            MetadataAttributeMutableBean parent = attribute(parentId, parentConcept, 1, false);
            parent.addMetadataAttributes(attribute(childId, childConcept, 1, false));
            mutable.addMetadataAttributes(parent);
            return this;
        }

        private static MetadataAttributeMutableBean attribute(String id, String conceptUrn, int maxOccurs, boolean presentational) {
            var attr = new MetadataAttributeMutableBeanImpl();
            attr.setId(id);
            attr.setConceptRef(new StructureReferenceBeanImpl(conceptUrn));
            attr.setMaxOccurs(maxOccurs);
            attr.setPresentational(presentational);
            return attr;
        }

        MetadataStructureDefinitionBean build() {
            return mutable.getImmutableInstance();
        }
    }
}
