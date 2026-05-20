package com.epam.sdmxproxy.services.cache;

import com.epam.sdmxproxy.common.data.Structure;
import com.epam.sdmxproxy.common.data.TranslatedDataQuery;
import com.epam.sdmxproxy.common.data.TranslatedStructureQuery;
import com.epam.sdmxproxy.configuration.data.DataEndpointConfiguration;
import com.epam.sdmxproxy.exception.UnexpectedStateException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Utility class for generating cache keys for structures and responses.
 * <p>
 * Structure keys: structure:{registryName}:{agencyId}:{resourceId}:{version}:{references}:{detail}
 * Response keys: response:structure:{registryName}:{agencyId}:{resourceId}:{version}:{references}:{detail}:{md5Hash(Accept+queryParams)}
 */
@Slf4j
public class CacheKeyGenerator {

    private static final String STRUCTURE_KEY_PREFIX = "structure:";
    private static final String RESPONSE_KEY_PREFIX = "response:structure:";
    private static final String FAN_OUT_RESPONSE_KEY_PREFIX = "response:structure:fanout:";
    private static final String LIMIT_EMULATION_PREFIX = "limit_emu:";
    private static final String KEY_SEPARATOR = ":";

    /**
     * Generate cache key for parsed structures.
     * Format: structure:{registryName}:{agencyId}:{resourceId}:{version}:{references}:{detail}
     * <p>
     * Note: references and detail are NOT normalized - exact values are used.
     *
     * @param query translated structure query
     * @return cache key for parsed structures
     */
    public static String generateStructureKey(TranslatedStructureQuery query) {
        String registryName = query.getRegistryConfiguration().getName();
        Structure structure = query.getStructure();
        String type = structure.type();
        String agencyId = structure.agency();
        String resourceId = structure.id();
        String version = structure.version() != null ? structure.version() : "";
        String references = query.getReferences() != null ? query.getReferences() : "";
        String detail = query.getDetail() != null ? query.getDetail() : "";

        return STRUCTURE_KEY_PREFIX +
                type + KEY_SEPARATOR +
                registryName + KEY_SEPARATOR +
                agencyId + KEY_SEPARATOR +
                resourceId + KEY_SEPARATOR +
                version + KEY_SEPARATOR +
                references + KEY_SEPARATOR +
                detail;
    }

    /**
     * Generate cache key for ready response.
     * Format: response:structure:{registryName}:{agencyId}:{resourceId}:{version}:{references}:{detail}:{md5Hash(Accept+queryParams)}
     *
     * @param query              translated structure query
     * @param requestedMediaType Requested media type
     * @param queryParams        additional query parameters (filters, etc.)
     * @return cache key for ready response
     */
    public static String generateResponseKey(
            TranslatedStructureQuery query,
            MediaType requestedMediaType,
            Map<String, String> queryParams) {

        String acceptHeader = requestedMediaType != null ? requestedMediaType.toString() : null;

        String baseKey = generateStructureKey(query);

        // Build fingerprint string from Accept header and query params
        StringBuilder fingerprint = new StringBuilder();
        if (acceptHeader != null) {
            fingerprint.append("Accept:").append(acceptHeader);
        }

        // Sort query params for deterministic hashing
        if (queryParams != null && !queryParams.isEmpty()) {
            TreeMap<String, String> sortedParams = new TreeMap<>(queryParams);
            sortedParams.forEach((key, value) -> {
                fingerprint.append("&").append(key).append("=").append(value != null ? value : "");
            });
        }

        // Generate MD5 hash
        String hash = md5Hash(fingerprint.toString());

        return RESPONSE_KEY_PREFIX + baseKey.substring(STRUCTURE_KEY_PREFIX.length()) + KEY_SEPARATOR + hash;
    }

    /**
     * Generate cache key for the merged response of a wildcard fan-out structure query.
     * Format: {@code response:structure:fanout:{type}:{resourceId}:{version}:{references}:{detail}:{md5(Accept + configsHash)}}.
     * <p>
     * {@code configsHash} reflects the set of configured registries (typically
     * {@code configurationProvider.getConfiguration().getConfigs().hashCode()}). When the
     * operator updates the registry list the hash changes and existing entries become
     * unreachable by new requests; they expire on TTL.
     *
     * @param structureType      structure type (e.g. "datastructure")
     * @param resourceId         resource ID (path slot; may be {@code "*"})
     * @param version            version (path slot; may be {@code "*"})
     * @param references         references parameter (may be {@code null})
     * @param detail             detail parameter (may be {@code null})
     * @param requestedMediaType client Accept media type after fallback resolution
     * @param configsHash        deterministic hash over the configured registry list
     * @return cache key for the merged fan-out response
     */
    public static String generateFanOutResponseKey(
            String structureType,
            String resourceId,
            String version,
            String references,
            String detail,
            MediaType requestedMediaType,
            int configsHash) {

        String accept = requestedMediaType != null ? requestedMediaType.toString() : "";
        String fingerprint = "Accept:" + accept + "&configsHash:" + configsHash;
        String hash = md5Hash(fingerprint);

        return FAN_OUT_RESPONSE_KEY_PREFIX
                + structureType + KEY_SEPARATOR
                + (resourceId != null ? resourceId : "") + KEY_SEPARATOR
                + (version != null ? version : "") + KEY_SEPARATOR
                + (references != null ? references : "") + KEY_SEPARATOR
                + (detail != null ? detail : "") + KEY_SEPARATOR
                + hash;
    }

    /**
     * Generate cache key for the limit-emulation shrunk-query result.
     * Format: {@code limit_emu:{registryName}:{agencyId}:{resourceId}:{version}:{md5Hash(key+filters+limit+tolerance+budget)}}.
     * <p>
     * Cube identity (agency/resource/version) is captured in the prefix; everything that
     * influences the bisect outcome (path key, c[] filters, client limit, tolerance, probe
     * budget) is folded into the hash. Filter keys and values are sorted before hashing
     * so the order of insertion does not affect the cache key.
     */
    public static String generateLimitEmulationKey(TranslatedDataQuery query) {
        String registryName = query.getRegistryConfiguration() != null
                && query.getRegistryConfiguration().getName() != null
                ? query.getRegistryConfiguration().getName()
                : "";
        String agencyId = query.getAgencyID() != null ? query.getAgencyID() : "";
        String resourceId = query.getResourceID() != null ? query.getResourceID() : "";
        String version = query.getVersion() != null ? query.getVersion() : "";

        StringBuilder fingerprint = new StringBuilder();
        fingerprint.append("key=").append(query.getKey() != null ? query.getKey() : "");
        fingerprint.append("&filters=").append(serializeFilters(query.getFilters()));
        fingerprint.append("&limit=").append(query.getLimit() != null ? query.getLimit() : "");

        DataEndpointConfiguration cfg = query.getVersionConfiguration() != null
                ? query.getVersionConfiguration().getDataEndpointConfig()
                : null;
        if (cfg != null) {
            fingerprint.append("&tolerance=").append(cfg.getLimitEmulationTolerance());
            fingerprint.append("&budget=").append(cfg.getLimitEmulationProbeBudget());
        }

        return LIMIT_EMULATION_PREFIX
                + registryName + KEY_SEPARATOR
                + agencyId + KEY_SEPARATOR
                + resourceId + KEY_SEPARATOR
                + version + KEY_SEPARATOR
                + md5Hash(fingerprint.toString());
    }

    /**
     * Sort filters by dim id and by value within each dim, then encode as
     * {@code dim1=v1,v2;dim2=v3,v4}. Stable across MultiValueMap insertion order.
     */
    private static String serializeFilters(MultiValueMap<String, String> filters) {
        if (filters == null || filters.isEmpty()) {
            return "";
        }
        TreeMap<String, List<String>> sortedDims = new TreeMap<>();
        filters.forEach((dim, values) -> {
            if (values != null && !values.isEmpty()) {
                List<String> sortedValues = new ArrayList<>(values);
                sortedValues.sort(String::compareTo);
                sortedDims.put(dim, sortedValues);
            }
        });
        StringBuilder out = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, List<String>> entry : sortedDims.entrySet()) {
            if (!first) {
                out.append(';');
            }
            first = false;
            out.append(entry.getKey()).append('=').append(String.join(",", entry.getValue()));
        }
        return out.toString();
    }

    /**
     * Generate MD5 hash of input string.
     *
     * @param input string to hash
     * @return MD5 hash as hexadecimal string
     */
    private static String md5Hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hashBytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            log.error("MD5 algorithm not available", e);
            throw new UnexpectedStateException("MD5 hashing not available", e);
        }
    }

    /**
     * Convert byte array to hexadecimal string.
     *
     * @param bytes byte array
     * @return hexadecimal string
     */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder hexString = new StringBuilder(2 * bytes.length);
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }
}
