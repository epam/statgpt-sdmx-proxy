package com.epam.sdmxproxy.common.data;

import com.epam.sdmxproxy.configuration.data.SdmxFormat;
import com.epam.sdmxproxy.configuration.data.SdmxVersion;
import com.epam.sdmxproxy.exception.UnsupportedMediaTypeParameterException;
import com.epam.sdmxproxy.exception.UnsupportedSdmxVersionException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static com.epam.sdmxproxy.common.data.SdmxMediaTypeResolver.extractSdmxVersion;
import static com.epam.sdmxproxy.common.data.SdmxMediaTypeResolver.mapMediaType;
import static com.epam.sdmxproxy.common.data.SdmxMediaTypeResolver.parseMediaType;
import static com.epam.sdmxproxy.common.data.SdmxMediaTypeResolver.validateCsvParameters;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for SdmxMediaTypeResolver utility class.
 * Tests SDMX version extraction and format mapping from Accept headers.
 */
class SdmxMediaTypeResolverTest {

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
    void testExtractSdmxVersion_SDMXTypeWithoutVersion_DefaultsToSDMX_3_0() {
        // Given - SDMX type without version parameter
        String acceptHeader = "application/vnd.sdmx.data+xml";

        // When
        SdmxVersion version = extractSdmxVersion(acceptHeader);

        // Then - Unversioned SDMX types default to 3.0
        assertEquals(SdmxVersion.SDMX_3_0, version);
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

    // ========== extractSdmxVersion - generic types with parameters (Step 1 fix) ==========

    @Test
    void testExtractSdmxVersion_GenericJSONWithLabelsParam_ReturnsSDMX_3_0() {
        assertEquals(SdmxVersion.SDMX_3_0, extractSdmxVersion("application/json; labels=id"));
    }

    @Test
    void testExtractSdmxVersion_GenericXMLWithLabelsParam_ReturnsSDMX_3_0() {
        assertEquals(SdmxVersion.SDMX_3_0, extractSdmxVersion("application/xml; labels=id"));
    }

    @Test
    void testExtractSdmxVersion_GenericJSONWithVersionParam_ReturnsSDMX_3_0() {
        // version param has no meaning on generic types -- ignored
        assertEquals(SdmxVersion.SDMX_3_0, extractSdmxVersion("application/json; version=2.0.0"));
    }

    @Test
    void testExtractSdmxVersion_TextCSV_ReturnsSDMX_3_0() {
        assertEquals(SdmxVersion.SDMX_3_0, extractSdmxVersion("text/csv"));
    }

    @Test
    void testExtractSdmxVersion_ApplicationCSV_ReturnsSDMX_3_0() {
        assertEquals(SdmxVersion.SDMX_3_0, extractSdmxVersion("application/csv"));
    }

    @Test
    void testExtractSdmxVersion_TextCSVWithParams_ReturnsSDMX_3_0() {
        assertEquals(SdmxVersion.SDMX_3_0, extractSdmxVersion("text/csv; labels=both; keys=series"));
    }

    @Test
    void testExtractSdmxVersion_SDMXCSVFullParams_ReturnsSDMX_3_0() {
        assertEquals(SdmxVersion.SDMX_3_0, extractSdmxVersion("application/vnd.sdmx.data+csv; version=2.0.0; labels=name; timeFormat=normalized; keys=series"));
    }

    // ========== extractSdmxVersion - unversioned SDMX types (Step 3 fix) ==========

    @Test
    void testExtractSdmxVersion_UnversionedSDMXCSVWithLabels_DefaultsToSDMX_3_0() {
        assertEquals(SdmxVersion.SDMX_3_0, extractSdmxVersion("application/vnd.sdmx.data+csv; labels=id"));
    }

    @Test
    void testExtractSdmxVersion_UnversionedSDMXCSV_DefaultsToSDMX_3_0() {
        assertEquals(SdmxVersion.SDMX_3_0, extractSdmxVersion("application/vnd.sdmx.data+csv"));
    }

    @Test
    void testExtractSdmxVersion_UnversionedSDMXJSON_DefaultsToSDMX_3_0() {
        assertEquals(SdmxVersion.SDMX_3_0, extractSdmxVersion("application/vnd.sdmx.data+json"));
    }

    // ========== validateCsvParameters tests ==========

    @Test
    void testValidateCsvParameters_JSONWithLabels_ThrowsException() {
        MediaType mediaType = MediaType.parseMediaType("application/json; labels=id");
        UnsupportedMediaTypeParameterException ex = assertThrows(UnsupportedMediaTypeParameterException.class, () -> validateCsvParameters(mediaType));
        assertTrue(ex.getMessage().contains("labels"));
        assertTrue(ex.getMessage().contains("CSV"));
    }

    @Test
    void testValidateCsvParameters_XMLWithTimeFormat_ThrowsException() {
        MediaType mediaType = MediaType.parseMediaType("application/xml; timeformat=normalized");
        assertThrows(UnsupportedMediaTypeParameterException.class, () -> validateCsvParameters(mediaType));
    }

    @Test
    void testValidateCsvParameters_SDMXJSONWithKeys_ThrowsException() {
        MediaType mediaType = MediaType.parseMediaType("application/vnd.sdmx.data+json; version=2.0.0; keys=series");
        assertThrows(UnsupportedMediaTypeParameterException.class, () -> validateCsvParameters(mediaType));
    }

    @Test
    void testValidateCsvParameters_SDMXXMLWithLabels_ThrowsException() {
        MediaType mediaType = MediaType.parseMediaType("application/vnd.sdmx.data+xml; version=3.0.0; labels=both");
        assertThrows(UnsupportedMediaTypeParameterException.class, () -> validateCsvParameters(mediaType));
    }

    @Test
    void testValidateCsvParameters_PlainJSON_NoException() {
        assertDoesNotThrow(() -> validateCsvParameters(MediaType.APPLICATION_JSON));
    }

    @Test
    void testValidateCsvParameters_JSONWithVersionParam_NoException() {
        // version is not a CSV-only param
        MediaType mediaType = MediaType.parseMediaType("application/json; version=2.0.0");
        assertDoesNotThrow(() -> validateCsvParameters(mediaType));
    }

    @Test
    void testValidateCsvParameters_SDMXJSONWithUnknownParam_NoException() {
        // unknown params are tolerated
        MediaType mediaType = MediaType.parseMediaType("application/vnd.sdmx.data+json; version=2.0.0; foo=bar");
        assertDoesNotThrow(() -> validateCsvParameters(mediaType));
    }

    @Test
    void testValidateCsvParameters_SDMXCSVWithAllParams_NoException() {
        MediaType mediaType = MediaType.parseMediaType("application/vnd.sdmx.data+csv; version=2.0.0; labels=name; timeFormat=normalized; keys=series");
        assertDoesNotThrow(() -> validateCsvParameters(mediaType));
    }

    @Test
    void testValidateCsvParameters_TextCSVWithLabels_NoException() {
        MediaType mediaType = MediaType.parseMediaType("text/csv; labels=both");
        assertDoesNotThrow(() -> validateCsvParameters(mediaType));
    }

    @Test
    void testValidateCsvParameters_ApplicationCSVWithKeys_NoException() {
        MediaType mediaType = MediaType.parseMediaType("application/csv; keys=obs");
        assertDoesNotThrow(() -> validateCsvParameters(mediaType));
    }

    // ========== parseMediaType integration tests ==========

    @Test
    void testParseMediaType_JSONWithLabels_ThrowsParameterException() {
        // End-to-end: version extraction passes but param validation rejects
        assertThrows(UnsupportedMediaTypeParameterException.class, () -> parseMediaType("application/json; labels=id"));
    }

    @Test
    void testParseMediaType_SDMXCSVWithParams_ReturnsValidResult() {
        // End-to-end: valid CSV with params works
        MediaTypeParseResult result = parseMediaType("application/vnd.sdmx.data+csv; version=2.0.0; labels=name");
        assertEquals(SdmxVersion.SDMX_3_0, result.getSdmxVersion());
        assertTrue(result.getMediaType().toString().contains("csv"));
        assertEquals("name", result.getMediaType().getParameter("labels"));
    }

    @Test
    void testParseMediaType_PlainJSON_ReturnsValidResult() {
        // End-to-end: plain JSON works
        MediaTypeParseResult result = parseMediaType("application/json");
        assertEquals(SdmxVersion.SDMX_3_0, result.getSdmxVersion());
        assertEquals(MediaType.APPLICATION_JSON, result.getMediaType());
    }

    // ========== mapMediaType - CSV with params preserved ==========

    @Test
    void testMapMediaType_SDMXCSVWithAllParams_PreservesParams() {
        String acceptHeader = "application/vnd.sdmx.data+csv; version=2.0.0; labels=name; timeFormat=normalized";
        MediaType mediaType = mapMediaType(acceptHeader);
        assertTrue(mediaType.toString().contains("csv"));
        assertEquals("name", mediaType.getParameter("labels"));
        assertEquals("normalized", mediaType.getParameter("timeformat"));
    }

    // ========== SdmxFormat.sdmxVersion parity guard (design 035, Edge Case #1) ==========

    @Test
    void sdmxVersionMatchesLegacyExtractMapping() {
        for (SdmxFormat format : SdmxFormat.values()) {
            assertEquals(format.getSdmxVersion(), extractSdmxVersion(format.getContentType()),
                    "SdmxFormat." + format + " declares getSdmxVersion()=" + format.getSdmxVersion()
                            + " but extractSdmxVersion(\"" + format.getContentType() + "\") disagrees");
        }
    }
}
