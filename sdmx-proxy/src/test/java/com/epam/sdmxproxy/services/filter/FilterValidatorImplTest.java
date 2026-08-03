package com.epam.sdmxproxy.services.filter;

import com.epam.sdmxproxy.configuration.data.SdmxVersion;
import com.epam.sdmxproxy.configuration.data.VersionSpecificRegistryConfiguration;
import com.epam.sdmxproxy.services.misc.DimensionService;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FilterValidatorImplTest {

    private DimensionService dimensionService;
    private FilterValidatorImpl validator;
    private VersionSpecificRegistryConfiguration version21;
    private SdmxBeans sdmxBeans;

    @BeforeEach
    void setUp() {
        dimensionService = mock(DimensionService.class);
        validator = new FilterValidatorImpl(dimensionService);
        version21 = new VersionSpecificRegistryConfiguration();
        version21.setSdmxVersion(SdmxVersion.SDMX_2_1);
        sdmxBeans = mock(SdmxBeans.class);
        when(dimensionService.getDimensionIds(any(), anyString(), anyString(), anyString()))
                .thenReturn(List.of("FREQ", "REF_AREA"));
        when(dimensionService.getTimeDimensionId(any(), anyString(), anyString(), anyString()))
                .thenReturn("TIME_PERIOD");
    }

    @Test
    void validateFilters_nullFilters_returnsValid() {
        // WHEN
        FilterValidationResult result = validator.validateFilters(
                version21, sdmxBeans, null, "BIS", "TEST", "1.0");

        // THEN
        assertTrue(result.isValid());
    }

    @Test
    void validateFilters_emptyFilters_returnsValid() {
        // GIVEN
        MultiValueMap<String, String> filters = new LinkedMultiValueMap<>();

        // WHEN
        FilterValidationResult result = validator.validateFilters(
                version21, sdmxBeans, filters, "BIS", "TEST", "1.0");

        // THEN
        assertTrue(result.isValid());
    }

    @Test
    void validateFilters_sdmx30_returnsValid() {
        // GIVEN
        VersionSpecificRegistryConfiguration version30 = new VersionSpecificRegistryConfiguration();
        version30.setSdmxVersion(SdmxVersion.SDMX_3_0);
        MultiValueMap<String, String> filters = new LinkedMultiValueMap<>();
        filters.add("TIME_PERIOD", "ne:2020");

        // WHEN
        FilterValidationResult result = validator.validateFilters(
                version30, sdmxBeans, filters, "BIS", "TEST", "1.0");

        // THEN
        assertTrue(result.isValid());
    }

    @Test
    void validateFilters_timeDimension_eq_returnsValid() {
        // GIVEN
        MultiValueMap<String, String> filters = new LinkedMultiValueMap<>();
        filters.add("TIME_PERIOD", "2020-01");

        // WHEN
        FilterValidationResult result = validator.validateFilters(
                version21, sdmxBeans, filters, "BIS", "TEST", "1.0");

        // THEN
        assertTrue(result.isValid());
    }

    @Test
    void validateFilters_timeDimension_eqWithPrefix_returnsValid() {
        // GIVEN
        MultiValueMap<String, String> filters = new LinkedMultiValueMap<>();
        filters.add("TIME_PERIOD", "eq:2020-01");

        // WHEN
        FilterValidationResult result = validator.validateFilters(
                version21, sdmxBeans, filters, "BIS", "TEST", "1.0");

        // THEN
        assertTrue(result.isValid());
    }

    @Test
    void validateFilters_timeDimension_ge_returnsValid() {
        // GIVEN
        MultiValueMap<String, String> filters = new LinkedMultiValueMap<>();
        filters.add("TIME_PERIOD", "ge:2020-01");

        // WHEN
        FilterValidationResult result = validator.validateFilters(
                version21, sdmxBeans, filters, "BIS", "TEST", "1.0");

        // THEN
        assertTrue(result.isValid());
    }

    @Test
    void validateFilters_timeDimension_gt_returnsValid() {
        // GIVEN
        MultiValueMap<String, String> filters = new LinkedMultiValueMap<>();
        filters.add("TIME_PERIOD", "gt:2020-01");

        // WHEN
        FilterValidationResult result = validator.validateFilters(
                version21, sdmxBeans, filters, "BIS", "TEST", "1.0");

        // THEN
        assertTrue(result.isValid());
    }

    @Test
    void validateFilters_timeDimension_le_returnsValid() {
        // GIVEN
        MultiValueMap<String, String> filters = new LinkedMultiValueMap<>();
        filters.add("TIME_PERIOD", "le:2020-12");

        // WHEN
        FilterValidationResult result = validator.validateFilters(
                version21, sdmxBeans, filters, "BIS", "TEST", "1.0");

        // THEN
        assertTrue(result.isValid());
    }

    @Test
    void validateFilters_timeDimension_lt_returnsValid() {
        // GIVEN
        MultiValueMap<String, String> filters = new LinkedMultiValueMap<>();
        filters.add("TIME_PERIOD", "lt:2020-12");

        // WHEN
        FilterValidationResult result = validator.validateFilters(
                version21, sdmxBeans, filters, "BIS", "TEST", "1.0");

        // THEN
        assertTrue(result.isValid());
    }

    @Test
    void validateFilters_timeDimension_geAndLe_returnsValid() {
        // GIVEN
        MultiValueMap<String, String> filters = new LinkedMultiValueMap<>();
        filters.add("TIME_PERIOD", "ge:2020-01+le:2020-12");

        // WHEN
        FilterValidationResult result = validator.validateFilters(
                version21, sdmxBeans, filters, "BIS", "TEST", "1.0");

        // THEN
        assertTrue(result.isValid());
    }

    @Test
    void validateFilters_timeDimension_ne_returnsInvalid() {
        // GIVEN
        MultiValueMap<String, String> filters = new LinkedMultiValueMap<>();
        filters.add("TIME_PERIOD", "ne:2020");

        // WHEN
        FilterValidationResult result = validator.validateFilters(
                version21, sdmxBeans, filters, "BIS", "TEST", "1.0");

        // THEN
        assertFalse(result.isValid());
        assertTrue(result.getErrorMessage().contains("ne"));
        assertTrue(result.getErrorMessage().contains("time dimension"));
    }

    @Test
    void validateFilters_timeDimension_co_returnsInvalid() {
        // GIVEN
        MultiValueMap<String, String> filters = new LinkedMultiValueMap<>();
        filters.add("TIME_PERIOD", "co:2020");

        // WHEN
        FilterValidationResult result = validator.validateFilters(
                version21, sdmxBeans, filters, "BIS", "TEST", "1.0");

        // THEN
        assertFalse(result.isValid());
        assertTrue(result.getErrorMessage().contains("co"));
    }

    @Test
    void validateFilters_timeDimension_nc_returnsInvalid() {
        // GIVEN
        MultiValueMap<String, String> filters = new LinkedMultiValueMap<>();
        filters.add("TIME_PERIOD", "nc:2020");

        // WHEN
        FilterValidationResult result = validator.validateFilters(
                version21, sdmxBeans, filters, "BIS", "TEST", "1.0");

        // THEN
        assertFalse(result.isValid());
        assertTrue(result.getErrorMessage().contains("nc"));
    }

    @Test
    void validateFilters_timeDimension_sw_returnsInvalid() {
        // GIVEN
        MultiValueMap<String, String> filters = new LinkedMultiValueMap<>();
        filters.add("TIME_PERIOD", "sw:2020");

        // WHEN
        FilterValidationResult result = validator.validateFilters(
                version21, sdmxBeans, filters, "BIS", "TEST", "1.0");

        // THEN
        assertFalse(result.isValid());
        assertTrue(result.getErrorMessage().contains("sw"));
    }

    @Test
    void validateFilters_timeDimension_ew_returnsInvalid() {
        // GIVEN
        MultiValueMap<String, String> filters = new LinkedMultiValueMap<>();
        filters.add("TIME_PERIOD", "ew:2020");

        // WHEN
        FilterValidationResult result = validator.validateFilters(
                version21, sdmxBeans, filters, "BIS", "TEST", "1.0");

        // THEN
        assertFalse(result.isValid());
        assertTrue(result.getErrorMessage().contains("ew"));
    }

    @Test
    void validateFilters_nonTimeDimension_eq_returnsValid() {
        // GIVEN
        MultiValueMap<String, String> filters = new LinkedMultiValueMap<>();
        filters.add("FREQ", "M");

        // WHEN
        FilterValidationResult result = validator.validateFilters(
                version21, sdmxBeans, filters, "BIS", "TEST", "1.0");

        // THEN
        assertTrue(result.isValid());
    }

    @Test
    void validateFilters_nonTimeDimension_ne_returnsInvalid() {
        // GIVEN
        MultiValueMap<String, String> filters = new LinkedMultiValueMap<>();
        filters.add("FREQ", "ne:M");

        // WHEN
        FilterValidationResult result = validator.validateFilters(
                version21, sdmxBeans, filters, "BIS", "TEST", "1.0");

        // THEN
        assertFalse(result.isValid());
        assertTrue(result.getErrorMessage().contains("eq"));
    }

    @Test
    void validateFilters_nonTimeDimension_plusSeparator_returnsInvalid() {
        // GIVEN
        MultiValueMap<String, String> filters = new LinkedMultiValueMap<>();
        filters.add("FREQ", "eq:M+eq:Q");

        // WHEN
        FilterValidationResult result = validator.validateFilters(
                version21, sdmxBeans, filters, "BIS", "TEST", "1.0");

        // THEN
        assertFalse(result.isValid());
        assertTrue(result.getErrorMessage().contains("AND"));
    }

    @Test
    void validateFilters_unknownComponent_returnsInvalid() {
        // GIVEN
        when(dimensionService.getDimensionIds(any(), anyString(), anyString(), anyString()))
                .thenReturn(List.of("FREQ", "REF_AREA"));
        when(dimensionService.getTimeDimensionId(any(), anyString(), anyString(), anyString()))
                .thenReturn("TIME_PERIOD");
        MultiValueMap<String, String> filters = new LinkedMultiValueMap<>();
        filters.add("UNKNOWN_DIM", "value");

        // WHEN
        FilterValidationResult result = validator.validateFilters(
                version21, sdmxBeans, filters, "BIS", "TEST", "1.0");

        // THEN
        assertFalse(result.isValid());
        assertTrue(result.getErrorMessage().contains("UNKNOWN_DIM"));
    }
}
