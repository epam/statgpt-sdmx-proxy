package com.epam.sdmxproxy.services.fixture.data;

import com.epam.sdmxproxy.configuration.data.SdmxFormat;
import com.epam.sdmxproxy.configuration.data.fixture.DataFixtureType;
import com.epam.sdmxproxy.configuration.data.fixture.FixtureConfiguration;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DataFixtureServiceTest {

    @Test
    void shouldReturnOriginalStreamWhenFixtureConfigsIsNull() {
        DataFixtureService service = new DataFixtureService(List.of());
        InputStream input = new ByteArrayInputStream("test".getBytes(StandardCharsets.UTF_8));

        InputStream result = service.applyFixtures(input, SdmxFormat.JSON_DATA_1_0_0, null, null);

        assertSame(input, result);
    }

    @Test
    void shouldReturnOriginalStreamWhenFixtureConfigsIsEmpty() {
        DataFixtureService service = new DataFixtureService(List.of());
        InputStream input = new ByteArrayInputStream("test".getBytes(StandardCharsets.UTF_8));

        InputStream result = service.applyFixtures(input, SdmxFormat.JSON_DATA_1_0_0, null, Collections.emptyList());

        assertSame(input, result);
    }

    @Test
    void shouldApplyMatchingFixture() {
        DataFixture mockFixture = mock(DataFixture.class);
        when(mockFixture.getType()).thenReturn(DataFixtureType.TIME_PERIOD_MONTHLY_NORMALIZATION);
        when(mockFixture.supportedFormats()).thenReturn(Set.of(SdmxFormat.JSON_DATA_1_0_0));

        InputStream input = new ByteArrayInputStream("original".getBytes(StandardCharsets.UTF_8));
        InputStream fixedStream = new ByteArrayInputStream("fixed".getBytes(StandardCharsets.UTF_8));
        SdmxBeans sdmxBeans = mock(SdmxBeans.class);
        Map<String, String> config = Map.of();

        when(mockFixture.apply(input, sdmxBeans, config)).thenReturn(fixedStream);

        DataFixtureService service = new DataFixtureService(List.of(mockFixture));

        FixtureConfiguration<DataFixtureType> fc = new FixtureConfiguration<>();
        fc.setType(DataFixtureType.TIME_PERIOD_MONTHLY_NORMALIZATION);
        fc.setConfig(config);

        InputStream result = service.applyFixtures(input, SdmxFormat.JSON_DATA_1_0_0, sdmxBeans, List.of(fc));

        assertSame(fixedStream, result);
        verify(mockFixture).apply(input, sdmxBeans, config);
    }

    @Test
    void shouldRunRepairFirst() {
        DataFixture monthly = mock(DataFixture.class);
        when(monthly.getType()).thenReturn(DataFixtureType.TIME_PERIOD_MONTHLY_NORMALIZATION);
        when(monthly.supportedFormats()).thenReturn(Set.of(SdmxFormat.XML_STRUCTURE_SPECIFIC_DATA_2_1));
        DataFixture repair = mock(DataFixture.class);
        when(repair.getType()).thenReturn(DataFixtureType.REPAIR_EMPTY_DATASET);
        when(repair.supportedFormats()).thenReturn(Set.of(SdmxFormat.XML_STRUCTURE_SPECIFIC_DATA_2_1));
        InputStream input = new ByteArrayInputStream("raw".getBytes(StandardCharsets.UTF_8));
        InputStream repaired = new ByteArrayInputStream("repaired".getBytes(StandardCharsets.UTF_8));
        InputStream normalized = new ByteArrayInputStream("normalized".getBytes(StandardCharsets.UTF_8));
        when(repair.apply(any(), any(), any())).thenReturn(repaired);
        when(monthly.apply(any(), any(), any())).thenReturn(normalized);

        DataFixtureService service = new DataFixtureService(List.of(monthly, repair));
        // Listed second on purpose: a StAX fixture would fail on the malformed body ahead of it.
        InputStream result = service.applyFixtures(input, SdmxFormat.XML_STRUCTURE_SPECIFIC_DATA_2_1, null, List.of(config(DataFixtureType.TIME_PERIOD_MONTHLY_NORMALIZATION), config(DataFixtureType.REPAIR_EMPTY_DATASET)));

        assertSame(normalized, result);
        InOrder order = inOrder(repair, monthly);
        order.verify(repair).apply(any(), any(), any());
        order.verify(monthly).apply(any(), any(), any());
    }

    @Test
    void shouldApplyOnlyTheRequestedTypes() {
        DataFixture monthly = mock(DataFixture.class);
        when(monthly.getType()).thenReturn(DataFixtureType.TIME_PERIOD_MONTHLY_NORMALIZATION);
        DataFixture repair = mock(DataFixture.class);
        when(repair.getType()).thenReturn(DataFixtureType.REPAIR_EMPTY_DATASET);
        when(repair.supportedFormats()).thenReturn(Set.of(SdmxFormat.XML_STRUCTURE_SPECIFIC_DATA_2_1));
        InputStream input = new ByteArrayInputStream("raw".getBytes(StandardCharsets.UTF_8));
        InputStream repaired = new ByteArrayInputStream("repaired".getBytes(StandardCharsets.UTF_8));
        when(repair.apply(any(), any(), any())).thenReturn(repaired);

        DataFixtureService service = new DataFixtureService(List.of(monthly, repair));
        InputStream result = service.applyFixtures(input, SdmxFormat.XML_STRUCTURE_SPECIFIC_DATA_2_1, null, List.of(config(DataFixtureType.TIME_PERIOD_MONTHLY_NORMALIZATION), config(DataFixtureType.REPAIR_EMPTY_DATASET)), Set.of(DataFixtureType.REPAIR_EMPTY_DATASET));

        assertSame(repaired, result);
        verify(monthly, never()).apply(any(), any(), any());
    }

    private static FixtureConfiguration<DataFixtureType> config(DataFixtureType type) {
        FixtureConfiguration<DataFixtureType> fc = new FixtureConfiguration<>();
        fc.setType(type);
        return fc;
    }

    @Test
    void shouldSkipFixtureWhenFormatDoesNotMatch() {
        DataFixture jsonFixture = mock(DataFixture.class);
        when(jsonFixture.getType()).thenReturn(DataFixtureType.TIME_PERIOD_MONTHLY_NORMALIZATION);
        when(jsonFixture.supportedFormats()).thenReturn(Set.of(SdmxFormat.JSON_DATA_1_0_0));

        DataFixtureService service = new DataFixtureService(List.of(jsonFixture));

        InputStream input = new ByteArrayInputStream("original".getBytes(StandardCharsets.UTF_8));

        FixtureConfiguration<DataFixtureType> fc = new FixtureConfiguration<>();
        fc.setType(DataFixtureType.TIME_PERIOD_MONTHLY_NORMALIZATION);
        fc.setConfig(Map.of());

        InputStream result = service.applyFixtures(input, SdmxFormat.XML_GENERIC_DATA_2_1, null, List.of(fc));

        assertSame(input, result);
        verify(jsonFixture, never()).apply(any(), any(), any());
    }

    @Test
    void shouldChainMultipleFixturesInOrder() {
        DataFixture fixture = mock(DataFixture.class);
        when(fixture.getType()).thenReturn(DataFixtureType.TIME_PERIOD_MONTHLY_NORMALIZATION);
        when(fixture.supportedFormats()).thenReturn(Set.of(SdmxFormat.JSON_DATA_1_0_0));

        InputStream input = new ByteArrayInputStream("original".getBytes(StandardCharsets.UTF_8));
        InputStream after1 = new ByteArrayInputStream("after1".getBytes(StandardCharsets.UTF_8));
        InputStream after2 = new ByteArrayInputStream("after2".getBytes(StandardCharsets.UTF_8));

        Map<String, String> config = Map.of();
        when(fixture.apply(input, null, config)).thenReturn(after1);
        when(fixture.apply(after1, null, config)).thenReturn(after2);

        DataFixtureService service = new DataFixtureService(List.of(fixture));

        FixtureConfiguration<DataFixtureType> fc1 = new FixtureConfiguration<>();
        fc1.setType(DataFixtureType.TIME_PERIOD_MONTHLY_NORMALIZATION);
        fc1.setConfig(config);

        FixtureConfiguration<DataFixtureType> fc2 = new FixtureConfiguration<>();
        fc2.setType(DataFixtureType.TIME_PERIOD_MONTHLY_NORMALIZATION);
        fc2.setConfig(config);

        InputStream result = service.applyFixtures(input, SdmxFormat.JSON_DATA_1_0_0, null, List.of(fc1, fc2));

        assertSame(after2, result);
        InOrder inOrder = inOrder(fixture);
        inOrder.verify(fixture).apply(input, null, config);
        inOrder.verify(fixture).apply(after1, null, config);
    }
}
