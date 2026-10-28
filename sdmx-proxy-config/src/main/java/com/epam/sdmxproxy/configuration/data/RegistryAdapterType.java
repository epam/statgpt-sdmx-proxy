package com.epam.sdmxproxy.configuration.data;

/**
 * Which registry adapter talks to a registry.
 */
public enum RegistryAdapterType {
    /** A standard SDMX registry: the structure, data, and availability resources of its version. */
    SDMX,
    /**
     * Statistics Canada, which serves only per-table structure bundles: structures are emulated
     * from {@code structure/Data_Structure_{table}} and the WDS {@code getAllCubesListLite} method,
     * data and availability go through the SDMX adapter -- see design 043, Revision 1.
     */
    STATCAN
}
