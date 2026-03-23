package com.epam.sdmxproxy.services.fixture;

import com.epam.sdmxproxy.configuration.data.FixtureConfiguration;
import com.epam.sdmxproxy.configuration.data.FixtureType;
import com.epam.sdmxproxy.configuration.data.ReturnFormat;
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
        FixtureService service = new FixtureService(List.of());
        InputStream input = new ByteArrayInputStream("test".getBytes(StandardCharsets.UTF_8));

        InputStream result = service.applyFixtures(input, ReturnFormat.JSON_STRUCTURE_2_0_0, null);

        assertSame(input, result);
    }

    @Test
    void shouldReturnOriginalStreamWhenFixtureConfigsIsEmpty() {
        FixtureService service = new FixtureService(List.of());
        InputStream input = new ByteArrayInputStream("test".getBytes(StandardCharsets.UTF_8));

        InputStream result = service.applyFixtures(input, ReturnFormat.JSON_STRUCTURE_2_0_0, Collections.emptyList());

        assertSame(input, result);
    }

    @Test
    void shouldApplyMatchingFixture() {
        Fixture mockFixture = mock(Fixture.class);
        when(mockFixture.getType()).thenReturn(FixtureType.DSD_ATTRIBUTE_ATTACHMENT_LEVEL);
        when(mockFixture.supportedFormats()).thenReturn(Set.of(ReturnFormat.JSON_STRUCTURE_2_0_0));

        InputStream input = new ByteArrayInputStream("original".getBytes(StandardCharsets.UTF_8));
        InputStream fixedStream = new ByteArrayInputStream("fixed".getBytes(StandardCharsets.UTF_8));
        Map<String, String> config = Map.of("sourceValue", "none", "fallbackValue", "observation");

        when(mockFixture.apply(eq(input), eq(config))).thenReturn(fixedStream);

        FixtureService service = new FixtureService(List.of(mockFixture));

        FixtureConfiguration fc = new FixtureConfiguration();
        fc.setType(FixtureType.DSD_ATTRIBUTE_ATTACHMENT_LEVEL);
        fc.setConfig(config);

        InputStream result = service.applyFixtures(input, ReturnFormat.JSON_STRUCTURE_2_0_0, List.of(fc));

        assertSame(fixedStream, result);
        verify(mockFixture).apply(eq(input), eq(config));
    }

    @Test
    void shouldSkipFixtureWhenFormatDoesNotMatch() {
        Fixture jsonFixture = mock(Fixture.class);
        when(jsonFixture.getType()).thenReturn(FixtureType.DSD_ATTRIBUTE_ATTACHMENT_LEVEL);
        when(jsonFixture.supportedFormats()).thenReturn(Set.of(ReturnFormat.JSON_STRUCTURE_2_0_0));

        FixtureService service = new FixtureService(List.of(jsonFixture));

        InputStream input = new ByteArrayInputStream("original".getBytes(StandardCharsets.UTF_8));

        FixtureConfiguration fc = new FixtureConfiguration();
        fc.setType(FixtureType.DSD_ATTRIBUTE_ATTACHMENT_LEVEL);
        fc.setConfig(Map.of("sourceValue", "none", "fallbackValue", "observation"));

        // Request with XML format -- JSON fixture should not match
        InputStream result = service.applyFixtures(input, ReturnFormat.XML_2_1, List.of(fc));

        assertSame(input, result);
        verify(jsonFixture, never()).apply(any(), any());
    }

    @Test
    void shouldChainMultipleFixturesInOrder() {
        Fixture fixture1 = mock(Fixture.class);
        when(fixture1.getType()).thenReturn(FixtureType.DSD_ATTRIBUTE_ATTACHMENT_LEVEL);
        when(fixture1.supportedFormats()).thenReturn(Set.of(ReturnFormat.JSON_STRUCTURE_2_0_0));

        InputStream input = new ByteArrayInputStream("original".getBytes(StandardCharsets.UTF_8));
        InputStream after1 = new ByteArrayInputStream("after1".getBytes(StandardCharsets.UTF_8));

        Map<String, String> config1 = Map.of("sourceValue", "none", "fallbackValue", "observation");
        when(fixture1.apply(eq(input), eq(config1))).thenReturn(after1);

        FixtureService service = new FixtureService(List.of(fixture1));

        FixtureConfiguration fc1 = new FixtureConfiguration();
        fc1.setType(FixtureType.DSD_ATTRIBUTE_ATTACHMENT_LEVEL);
        fc1.setConfig(config1);

        // Two configs with the same type -- the same fixture is applied twice
        FixtureConfiguration fc2 = new FixtureConfiguration();
        fc2.setType(FixtureType.DSD_ATTRIBUTE_ATTACHMENT_LEVEL);
        fc2.setConfig(config1);

        InputStream after2 = new ByteArrayInputStream("after2".getBytes(StandardCharsets.UTF_8));
        when(fixture1.apply(eq(after1), eq(config1))).thenReturn(after2);

        InputStream result = service.applyFixtures(input, ReturnFormat.JSON_STRUCTURE_2_0_0, List.of(fc1, fc2));

        assertSame(after2, result);
        verify(fixture1).apply(eq(input), eq(config1));
        verify(fixture1).apply(eq(after1), eq(config1));
    }
}
