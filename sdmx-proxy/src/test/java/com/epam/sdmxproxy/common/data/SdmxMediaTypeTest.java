package com.epam.sdmxproxy.common.data;

import com.epam.sdmxproxy.configuration.data.SdmxVersion;
import com.epam.sdmxproxy.exception.UnsupportedSdmxVersionException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static com.epam.sdmxproxy.common.data.SdmxMediaType.extractSdmxVersion;
import static com.epam.sdmxproxy.common.data.SdmxMediaType.mapMediaType;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for SdmxMediaType utility class.
 * Tests SDMX version extraction and format mapping from Accept headers.
 */
class SdmxMediaTypeTest {

    @Test
    void testExtractSdmxVersion_SDMX_3_0_XML() {
        // Given
        String acceptHeader = "application/vnd.sdmx.data+xml; version=3.0.0";

        // When
        SdmxVersion version = extractSdmxVersion(acceptHeader);

        // Then
        assertEquals(SdmxVersion.SDMX_3_0, version);
    }

    @Test
    void testExtractSdmxVersion_SDMX_3_0_JSON() {
        // Given
        String acceptHeader = "application/vnd.sdmx.data+json; version=2.0.0";

        // When
        SdmxVersion version = extractSdmxVersion(acceptHeader);

        // Then
        assertEquals(SdmxVersion.SDMX_3_0, version);
    }

    @Test
    void testExtractSdmxVersion_SDMX_3_0_CSV() {
        // Given
        String acceptHeader = "application/vnd.sdmx.data+csv; version=2.0.0";

        // When
        SdmxVersion version = extractSdmxVersion(acceptHeader);

        // Then
        assertEquals(SdmxVersion.SDMX_3_0, version);
    }

    @Test
    void testExtractSdmxVersion_SDMX_2_1_DraftJSON() {
        // Given
        String acceptHeader = "application/vnd.sdmx.draft-sdmx-json+json; version=2.1";

        // When
        SdmxVersion version = extractSdmxVersion(acceptHeader);

        // Then
        assertEquals(SdmxVersion.SDMX_2_1, version);
    }

    @Test
    void testExtractSdmxVersion_SDMX_2_1_CSV() {
        // Given
        String acceptHeader = "application/vnd.sdmx.data+csv; version=1.0.0";

        // When
        SdmxVersion version = extractSdmxVersion(acceptHeader);

        // Then
        assertEquals(SdmxVersion.SDMX_2_1, version);
    }

    @Test
    void testExtractSdmxVersion_SDMX_2_1_ExplicitVersion() {
        // Given
        String acceptHeader = "application/vnd.sdmx.data+xml; version=2.1";

        // When
        SdmxVersion version = extractSdmxVersion(acceptHeader);

        // Then
        assertEquals(SdmxVersion.SDMX_2_1, version);
    }

    @Test
    void testExtractSdmxVersion_GenericJSON_ReturnsSDMX_3_0() {
        // Given
        String acceptHeader = "application/json";

        // When
        SdmxVersion version = extractSdmxVersion(acceptHeader);

        // Then - Generic JSON defaults to SDMX 3.0
        assertEquals(SdmxVersion.SDMX_3_0, version);
    }

    @Test
    void testExtractSdmxVersion_GenericXML_ReturnsSDMX_3_0() {
        // Given
        String acceptHeader = "application/xml";

        // When
        SdmxVersion version = extractSdmxVersion(acceptHeader);

        // Then - Generic XML defaults to SDMX 3.0
        assertEquals(SdmxVersion.SDMX_3_0, version);
    }

    @Test
    void testExtractSdmxVersion_NullHeader_ReturnsSDMX_3_0() {
        // When
        SdmxVersion version = extractSdmxVersion(null);

        // Then - Null header defaults to SDMX 3.0
        assertEquals(SdmxVersion.SDMX_3_0, version);
    }

    @Test
    void testExtractSdmxVersion_EmptyHeader_ReturnsSDMX_3_0() {
        // Given
        String acceptHeader = "";

        // When
        SdmxVersion version = extractSdmxVersion(acceptHeader);

        // Then - Empty header defaults to SDMX 3.0
        assertEquals(SdmxVersion.SDMX_3_0, version);
    }

    @Test
    void testExtractSdmxVersion_BlankHeader_ReturnsSDMX_3_0() {
        // Given
        String acceptHeader = "   ";

        // When
        SdmxVersion version = extractSdmxVersion(acceptHeader);

        // Then - Blank header defaults to SDMX 3.0
        assertEquals(SdmxVersion.SDMX_3_0, version);
    }

    @Test
    void testExtractSdmxVersion_MultipleAcceptTypes_PrefersFirstMatch() {
        // Given - First type is 3.0, second is 2.1
        String acceptHeader = "application/vnd.sdmx.data+xml; version=3.0.0, application/vnd.sdmx.draft-sdmx-json+json; version=2.1";

        // When
        SdmxVersion version = extractSdmxVersion(acceptHeader);

        // Then - Should return first match (3.0)
        assertEquals(SdmxVersion.SDMX_3_0, version);
    }

    @Test
    void testExtractSdmxVersion_MultipleAcceptTypes_WithGenericFallback() {
        // Given - Generic JSON first, then SDMX 2.1
        String acceptHeader = "application/json, application/vnd.sdmx.draft-sdmx-json+json; version=2.1";

        // When
        SdmxVersion version = extractSdmxVersion(acceptHeader);

        // Then - Should find SDMX-specific type (2.1) - SDMX types take precedence over generic
        assertEquals(SdmxVersion.SDMX_2_1, version);
    }

    @Test
    void testExtractSdmxVersion_OnlyGenericTypes_ReturnsSDMX_3_0() {
        // Given - Only generic types, no SDMX-specific types
        String acceptHeader = "application/json, application/xml";

        // When
        SdmxVersion version = extractSdmxVersion(acceptHeader);

        // Then - Should default to SDMX 3.0
        assertEquals(SdmxVersion.SDMX_3_0, version);
    }

    @Test
    void testExtractSdmxVersion_UnsupportedSDMXVersion_ThrowsException() {
        // Given - SDMX media type but unsupported version
        String acceptHeader = "application/vnd.sdmx.data+xml; version=4.0.0";

        // When/Then - Should throw exception for unsupported version
        UnsupportedSdmxVersionException exception = assertThrows(UnsupportedSdmxVersionException.class, () -> {
            extractSdmxVersion(acceptHeader);
        });

        assertTrue(exception.getMessage().contains("Unsupported SDMX version"));
        assertTrue(exception.getMessage().contains("4.0.0"));
        assertTrue(exception.getMessage().contains("Supported versions: 2.1, 3.0.0"));
    }

    @Test
    void testExtractSdmxVersion_MultipleUnsupportedVersions_ThrowsExceptionWithAll() {
        // Given - Multiple SDMX types with unsupported versions
        String acceptHeader = "application/vnd.sdmx.data+xml; version=4.0.0, application/vnd.sdmx.data+json; version=5.0.0";

        // When/Then - Should throw exception listing all unsupported versions
        UnsupportedSdmxVersionException exception = assertThrows(UnsupportedSdmxVersionException.class, () -> {
            extractSdmxVersion(acceptHeader);
        });

        assertTrue(exception.getMessage().contains("Unsupported SDMX version"));
        assertTrue(exception.getMessage().contains("4.0.0") || exception.getMessage().contains("5.0.0"));
    }

    @Test
    void testExtractSdmxVersion_SDMXTypeWithoutVersion_ThrowsException() {
        // Given - SDMX type without version parameter
        String acceptHeader = "application/vnd.sdmx.data+xml";

        // When/Then - Should throw exception for SDMX type without recognized version
        UnsupportedSdmxVersionException exception = assertThrows(UnsupportedSdmxVersionException.class, () -> {
            extractSdmxVersion(acceptHeader);
        });

        assertTrue(exception.getMessage().contains("Unsupported SDMX version"));
    }

    // ========== Format Mapping Tests ==========

    @Test
    void testMapMediaType_GenericJSON_ReturnsJSON() {
        // Given
        String acceptHeader = "application/json";

        // When
        MediaType mediaType = mapMediaType(acceptHeader);

        // Then
        assertEquals(MediaType.APPLICATION_JSON, mediaType);
    }

    @Test
    void testMapMediaType_SDMXJSON_ReturnsJSON() {
        // Given
        String acceptHeader = "application/vnd.sdmx.data+json; version=2.0.0";

        // When
        MediaType mediaType = mapMediaType(acceptHeader);

        // Then
        assertTrue(mediaType.toString().contains("sdmx"));
        assertTrue(mediaType.toString().contains("json"));
        assertEquals("application", mediaType.getType());
        assertTrue(mediaType.getSubtype().contains("json"));
    }

    @Test
    void testMapMediaType_DraftJSON_ReturnsJSON() {
        // Given
        String acceptHeader = "application/vnd.sdmx.draft-sdmx-json+json; version=2.1";

        // When
        MediaType mediaType = mapMediaType(acceptHeader);

        // Then
        assertTrue(mediaType.toString().contains("sdmx"));
        assertEquals("application", mediaType.getType());
        assertTrue(mediaType.getSubtype().contains("json"));
    }

    @Test
    void testMapMediaType_GenericXML_ReturnsXML() {
        // Given
        String acceptHeader = "application/xml";

        // When
        MediaType mediaType = mapMediaType(acceptHeader);

        // Then
        assertEquals(MediaType.APPLICATION_XML, mediaType);
    }

    @Test
    void testMapMediaType_SDMXXML_ReturnsXML() {
        // Given
        String acceptHeader = "application/vnd.sdmx.data+xml; version=3.0.0";

        // When
        MediaType mediaType = mapMediaType(acceptHeader);

        // Then
        assertTrue(mediaType.toString().contains("sdmx"));
        assertTrue(mediaType.toString().contains("xml"));
        assertEquals("application", mediaType.getType());
        assertTrue(mediaType.getSubtype().contains("xml"));
    }

    @Test
    void testMapMediaType_GenericCSV_ReturnsCSV() {
        // Given
        String acceptHeader = "application/csv";

        // When
        MediaType mediaType = mapMediaType(acceptHeader);

        // Then
        assertTrue(mediaType.toString().contains("csv"));
        assertEquals("application", mediaType.getType());
        assertTrue(mediaType.getSubtype().contains("csv"));
    }

    @Test
    void testMapMediaType_TextCSV_ReturnsCSV() {
        // Given
        String acceptHeader = "text/csv";

        // When
        MediaType mediaType = mapMediaType(acceptHeader);

        // Then
        assertEquals(MediaType.valueOf("text/csv"), mediaType);
    }

    @Test
    void testMapMediaType_SDMXCSV_1_0_0_ReturnsCSV() {
        // Given
        String acceptHeader = "application/vnd.sdmx.data+csv; version=1.0.0";

        // When
        MediaType mediaType = mapMediaType(acceptHeader);

        // Then
        assertTrue(mediaType.toString().contains("sdmx"));
        assertTrue(mediaType.toString().contains("csv"));
        assertTrue(mediaType.toString().contains("1.0.0"));
    }

    @Test
    void testMapMediaType_SDMXCSV_2_0_0_ReturnsCSV() {
        // Given
        String acceptHeader = "application/vnd.sdmx.data+csv; version=2.0.0";

        // When
        MediaType mediaType = mapMediaType(acceptHeader);

        // Then
        assertTrue(mediaType.toString().contains("sdmx"));
        assertTrue(mediaType.toString().contains("csv"));
        assertTrue(mediaType.toString().contains("2.0.0"));
    }

    @Test
    void testMapMediaType_SDMXCSV_WithoutVersion_MapsTo2_0_0() {
        // Given - SDMX CSV without version should map to 2.0.0
        String acceptHeader = "application/vnd.sdmx.data+csv";

        // When
        MediaType mediaType = mapMediaType(acceptHeader);

        // Then - Should map to CSV 2.0.0
        assertTrue(mediaType.toString().contains("sdmx"));
        assertTrue(mediaType.toString().contains("csv"));
        assertTrue(mediaType.toString().contains("2.0.0"));
    }

    @Test
    void testMapMediaType_NullHeader_DefaultsToJSON() {
        // When
        MediaType mediaType = mapMediaType(null);

        // Then - Should default to JSON
        assertEquals(MediaType.APPLICATION_JSON, mediaType);
    }

    @Test
    void testMapMediaType_EmptyHeader_DefaultsToJSON() {
        // Given
        String acceptHeader = "";

        // When
        MediaType mediaType = mapMediaType(acceptHeader);

        // Then - Should default to JSON
        assertEquals(MediaType.APPLICATION_JSON, mediaType);
    }

    @Test
    void testMapMediaType_BlankHeader_DefaultsToJSON() {
        // Given
        String acceptHeader = "   ";

        // When
        MediaType mediaType = mapMediaType(acceptHeader);

        // Then - Should default to JSON
        assertEquals(MediaType.APPLICATION_JSON, mediaType);
    }

    @Test
    void testMapMediaType_MultipleAcceptTypes_PrefersFirstMatch() {
        // Given - JSON first, then XML
        String acceptHeader = "application/json, application/xml";

        // When
        MediaType mediaType = mapMediaType(acceptHeader);

        // Then - Should return first match (JSON)
        assertEquals(MediaType.APPLICATION_JSON, mediaType);
    }

    @Test
    void testMapMediaType_MultipleAcceptTypes_XMLBeforeJSON() {
        // Given - XML first, then JSON
        String acceptHeader = "application/xml, application/json";

        // When
        MediaType mediaType = mapMediaType(acceptHeader);

        // Then - Should return first match (XML)
        assertEquals(MediaType.APPLICATION_XML, mediaType);
    }

    @Test
    void testMapMediaType_MultipleAcceptTypes_CSVBeforeJSON() {
        // Given - CSV first, then JSON
        String acceptHeader = "application/csv, application/json";

        // When
        MediaType mediaType = mapMediaType(acceptHeader);

        // Then - Should return first match (CSV)
        assertTrue(mediaType.toString().contains("csv"));
    }

    @Test
    void testMapMediaType_UnknownMediaType_DefaultsToJSON() {
        // Given - Unknown media type
        String acceptHeader = "application/unknown";

        // When
        MediaType mediaType = mapMediaType(acceptHeader);

        // Then - Should default to JSON
        assertEquals(MediaType.APPLICATION_JSON, mediaType);
    }

    @Test
    void testMapMediaType_SDMXJSON_PreservesMediaType() {
        // Given
        String acceptHeader = "application/vnd.sdmx.data+json; version=2.0.0";

        // When
        MediaType mediaType = mapMediaType(acceptHeader);

        // Then - Should preserve the original media type
        MediaType expectedMediaType = MediaType.parseMediaType("application/vnd.sdmx.data+json; version=2.0.0");
        assertEquals(expectedMediaType.getType(), mediaType.getType());
        assertEquals(expectedMediaType.getSubtype(), mediaType.getSubtype());
    }

    @Test
    void testMapMediaType_SDMXXML_PreservesMediaType() {
        // Given
        String acceptHeader = "application/vnd.sdmx.data+xml; version=3.0.0";

        // When
        MediaType mediaType = mapMediaType(acceptHeader);

        // Then - Should preserve the original media type
        MediaType expectedMediaType = MediaType.parseMediaType("application/vnd.sdmx.data+xml; version=3.0.0");
        assertEquals(expectedMediaType.getType(), mediaType.getType());
        assertEquals(expectedMediaType.getSubtype(), mediaType.getSubtype());
    }
}
