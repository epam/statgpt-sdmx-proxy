package com.epam.sdmxproxy.e2e.tests.framework.config;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Test config for the generic DSD round-trip fidelity pin in
 * {@link com.epam.sdmxproxy.e2e.tests.framework.BaseRegistryTestSuite}. Picks a single
 * DSD that exercises the moving parts of the structure-side conversion pipeline; the
 * base suite fetches it and runs the assertions whose corresponding config field is
 * non-null:
 * <ul>
 *   <li>{@link #expectedMetadataUrnContains} -- DSD's MSD reference is preserved
 *       (Stage 1 mapper fix in {@code StructureMapperImpl}).</li>
 *   <li>{@link #dimensionConceptRoleSuffixes} -- specific dimensions retain their
 *       {@code conceptRoles} (Stage 1 mapper fix).</li>
 *   <li>{@link #expectedAnnotationsWithText} -- ANNOTATION_VALUE_TO_TEXT fixture
 *       has rescued the original {@code value} into {@code text}.</li>
 *   <li>{@link #expectNonEmptyMetadataAttributeUsages} -- PRESERVE_METADATA_ATTRIBUTE_USAGES
 *       fixture has restored the MSD usage list at the attribute level.</li>
 *   <li>{@link #expectedFoldedMetadataAttributeIdsXml21} -- on XML 2.1 output the
 *       PRESERVE_METADATA_ATTRIBUTE_USAGES fixture folds each MSD usage into the
 *       {@code AttributeList} as a regular {@code DataAttribute} (design 032).</li>
 * </ul>
 * Each sub-assertion is opt-in: a registry that does not need a given check leaves the
 * field unset (or {@code false} for the boolean). Absent config block -> the whole
 * fidelity test is skipped for that registry.
 */
@Data
@NoArgsConstructor
public class DsdFidelityTestSuitConfiguration {

    /** Required: DSD to fetch (e.g. {@code "IMF.RES:DSD_WEO(9.0.0)"}). */
    private String dsdUrn;

    /** Accept header for the proxy. Defaults to {@code application/vnd.sdmx.structure+json;version=2.0.0}. */
    private String mediaType;

    /**
     * Optional. If non-null, asserts that {@code dataStructures[0].metadata} is non-empty
     * and contains this substring (e.g. {@code "MetadataStructure=IMF.RES:MSD_WEO_METADATA_EXTERNAL"}).
     */
    private String expectedMetadataUrnContains;

    /**
     * Optional. For each entry {@code dimensionId -> expectedSuffix}, asserts the
     * dimension carries {@code conceptRoles} where the first entry ends with the
     * given suffix (e.g. {@code "FREQUENCY" -> "SDMX_CONCEPT_ROLES(1.0).FREQ"}).
     */
    private Map<String, String> dimensionConceptRoleSuffixes;

    /**
     * Optional. For each ID in this list, asserts the DSD-level annotation with that
     * ID exists and carries a non-empty {@code text} field (gates ANNOTATION_VALUE_TO_TEXT
     * fixture's rescue of the original {@code value} into {@code text}).
     */
    private List<String> expectedAnnotationsWithText;

    /**
     * If true, asserts {@code dataStructureComponents.attributeList.metadataAttributeUsages}
     * is a non-empty array (gates the PRESERVE_METADATA_ATTRIBUTE_USAGES fixture).
     */
    private boolean expectNonEmptyMetadataAttributeUsages;

    /**
     * Optional. When non-empty, refetches the DSD with {@code Accept:
     * application/vnd.sdmx.structure+xml;version=2.1} and asserts each listed metadata-attribute id
     * appears as a {@code <str:Attribute>} in the AttributeList -- i.e. the PRESERVE_METADATA_ATTRIBUTE_USAGES
     * fixture folded the MSD usages into regular DataAttributes for XML 2.1 output (design 032).
     * The MSD must not leak as a {@code <str:MetadataStructure>} element.
     */
    private List<String> expectedFoldedMetadataAttributeIdsXml21;

    /**
     * Optional. When non-empty, refetches the DSD with {@code Accept:
     * application/vnd.sdmx.structure+xml;version=3.0.0} and asserts each listed metadata-attribute id
     * appears as a native {@code <str:MetadataAttributeUsage>} (its {@code MetadataAttributeReference}
     * text equals the id) -- i.e. the XML 3.0 usage injector restored the MSD usages (design 036).
     */
    private List<String> expectedMetadataAttributeUsageIdsXml30;
}
