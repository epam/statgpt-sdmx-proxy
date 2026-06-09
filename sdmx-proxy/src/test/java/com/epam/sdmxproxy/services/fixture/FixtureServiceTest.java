package com.epam.sdmxproxy.services.fixture;

import com.epam.sdmxproxy.configuration.data.SdmxFormat;
import com.epam.sdmxproxy.configuration.data.fixture.FixtureConfiguration;
import com.epam.sdmxproxy.configuration.data.fixture.StructureFixtureType;
import com.epam.sdmxproxy.services.fixture.structure.StructureFixture;
import com.epam.sdmxproxy.services.fixture.structure.StructureFixtureService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FixtureServiceTest {

    @Test
    void shouldReturnOriginalStreamWhenFixtureConfigsIsNull() {
        StructureFixtureService service = new StructureFixtureService(List.of());
        InputStream input = new ByteArrayInputStream("test".getBytes(StandardCharsets.UTF_8));

        InputStream result = service.applyFixtures(input, SdmxFormat.JSON_STRUCTURE_2_0_0, null);

        assertSame(input, result);
    }

    @Test
    void shouldReturnOriginalStreamWhenFixtureConfigsIsEmpty() {
        StructureFixtureService service = new StructureFixtureService(List.of());
        InputStream input = new ByteArrayInputStream("test".getBytes(StandardCharsets.UTF_8));

        InputStream result = service.applyFixtures(input, SdmxFormat.JSON_STRUCTURE_2_0_0, Collections.emptyList());

        assertSame(input, result);
    }

    @Test
    void shouldApplyMatchingFixture() {
        StructureFixture mockFixture = mock(StructureFixture.class);
        when(mockFixture.getType()).thenReturn(StructureFixtureType.DSD_ATTRIBUTE_ATTACHMENT_LEVEL);
        when(mockFixture.supportedFormats()).thenReturn(Set.of(SdmxFormat.JSON_STRUCTURE_2_0_0));

        InputStream input = new ByteArrayInputStream("original".getBytes(StandardCharsets.UTF_8));
        InputStream fixedStream = new ByteArrayInputStream("fixed".getBytes(StandardCharsets.UTF_8));
        Map<String, String> config = Map.of("sourceValue", "none", "fallbackValue", "observation");

        when(mockFixture.apply(eq(input), eq(config))).thenReturn(fixedStream);

        StructureFixtureService service = new StructureFixtureService(List.of(mockFixture));

        FixtureConfiguration<StructureFixtureType> fc = new FixtureConfiguration<>();
        fc.setType(StructureFixtureType.DSD_ATTRIBUTE_ATTACHMENT_LEVEL);
        fc.setConfig(config);

        List<FixtureConfiguration<StructureFixtureType>> configs = List.of(fc);
        InputStream result = service.applyFixtures(input, SdmxFormat.JSON_STRUCTURE_2_0_0, configs);

        assertSame(fixedStream, result);
        verify(mockFixture).apply(eq(input), eq(config));
    }

    @Test
    void shouldSkipFixtureWhenFormatDoesNotMatch() {
        StructureFixture jsonFixture = mock(StructureFixture.class);
        when(jsonFixture.getType()).thenReturn(StructureFixtureType.DSD_ATTRIBUTE_ATTACHMENT_LEVEL);
        when(jsonFixture.supportedFormats()).thenReturn(Set.of(SdmxFormat.JSON_STRUCTURE_2_0_0));

        StructureFixtureService service = new StructureFixtureService(List.of(jsonFixture));

        InputStream input = new ByteArrayInputStream("original".getBytes(StandardCharsets.UTF_8));

        FixtureConfiguration<StructureFixtureType> fc = new FixtureConfiguration<>();
        fc.setType(StructureFixtureType.DSD_ATTRIBUTE_ATTACHMENT_LEVEL);
        fc.setConfig(Map.of("sourceValue", "none", "fallbackValue", "observation"));

        // Request with XML format -- JSON fixture should not match
        List<FixtureConfiguration<StructureFixtureType>> configs = List.of(fc);
        InputStream result = service.applyFixtures(input, SdmxFormat.XML_STRUCTURE_2_1, configs);

        assertSame(input, result);
        verify(jsonFixture, never()).apply(any(), any());
    }

    @Test
    void shouldChainMultipleFixturesInOrder() {
        StructureFixture fixture1 = mock(StructureFixture.class);
        when(fixture1.getType()).thenReturn(StructureFixtureType.DSD_ATTRIBUTE_ATTACHMENT_LEVEL);
        when(fixture1.supportedFormats()).thenReturn(Set.of(SdmxFormat.JSON_STRUCTURE_2_0_0));

        InputStream input = new ByteArrayInputStream("original".getBytes(StandardCharsets.UTF_8));
        InputStream after1 = new ByteArrayInputStream("after1".getBytes(StandardCharsets.UTF_8));

        Map<String, String> config1 = Map.of("sourceValue", "none", "fallbackValue", "observation");
        when(fixture1.apply(eq(input), eq(config1))).thenReturn(after1);

        StructureFixtureService service = new StructureFixtureService(List.of(fixture1));

        FixtureConfiguration<StructureFixtureType> fc1 = new FixtureConfiguration<>();
        fc1.setType(StructureFixtureType.DSD_ATTRIBUTE_ATTACHMENT_LEVEL);
        fc1.setConfig(config1);

        // Two configs with the same type -- the same fixture is applied twice
        FixtureConfiguration<StructureFixtureType> fc2 = new FixtureConfiguration<>();
        fc2.setType(StructureFixtureType.DSD_ATTRIBUTE_ATTACHMENT_LEVEL);
        fc2.setConfig(config1);

        InputStream after2 = new ByteArrayInputStream("after2".getBytes(StandardCharsets.UTF_8));
        when(fixture1.apply(eq(after1), eq(config1))).thenReturn(after2);

        List<FixtureConfiguration<StructureFixtureType>> configs = List.of(fc1, fc2);
        InputStream result = service.applyFixtures(input, SdmxFormat.JSON_STRUCTURE_2_0_0, configs);

        assertSame(after2, result);
        verify(fixture1).apply(eq(input), eq(config1));
        verify(fixture1).apply(eq(after1), eq(config1));
    }
}
