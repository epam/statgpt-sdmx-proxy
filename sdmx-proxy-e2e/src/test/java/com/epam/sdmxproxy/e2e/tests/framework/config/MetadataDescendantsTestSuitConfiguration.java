package com.epam.sdmxproxy.e2e.tests.framework.config;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Test config for the generic structure-DESCENDANTS metadata-artefact pin in
 * {@link com.epam.sdmxproxy.e2e.tests.framework.BaseRegistryTestSuite}. Picks a single
 * dataflow whose {@code references=descendants} response carries metadata artefacts the
 * proxy mapper used to drop (design 033); the base suite fetches it and runs the
 * assertions whose corresponding flag is set:
 * <ul>
 *   <li>{@link #expectMetadataStructures} -- {@code data.metadataStructures} is non-empty
 *       and the first MSD carries its {@code metadataAttributes} (not a bare header).</li>
 *   <li>{@link #expectMetadataflows} -- {@code data.metadataflows} is non-empty.</li>
 *   <li>{@link #expectMetadataProvisionAgreements} -- {@code data.metadataProvisionAgreements}
 *       is non-empty.</li>
 * </ul>
 * Each assertion is opt-in: a registry whose DESCENDANTS response has no metadataflows or
 * provision agreements leaves those flags {@code false}. Absent config block -> the whole
 * pin is skipped for that registry.
 */
@Data
@NoArgsConstructor
public class MetadataDescendantsTestSuitConfiguration {

    /** Required: dataflow to fetch with {@code references=descendants} (e.g. {@code "IMF.RES:WEO(9.0.0)"}). */
    private String dataflowUrn;

    /** Accept header for the proxy. Defaults to {@code application/vnd.sdmx.structure+json;version=2.0.0}. */
    private String mediaType;

    /** If true, assert {@code data.metadataStructures} is non-empty (with populated attributes). */
    private boolean expectMetadataStructures;

    /** Optional substring the first MSD's {@code id} must contain (e.g. {@code "MSD_WEO"}). */
    private String expectedMsdIdContains;

    /** If true, assert {@code data.metadataflows} is non-empty. */
    private boolean expectMetadataflows;

    /** If true, assert {@code data.metadataProvisionAgreements} is non-empty. */
    private boolean expectMetadataProvisionAgreements;
}
