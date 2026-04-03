package com.epam.sdmxproxy.services.adapter;

import io.sdmx.api.io.ReadableDataLocation;
import io.sdmx.api.sdmx.builder.IBeansBuilder;
import io.sdmx.api.sdmx.model.beans.SdmxBeans;
import io.sdmx.format.ml.factory.structure.SdmxMLStructureReaderFactory;
import io.sdmx.utils.core.io.SdmxSourceReadableDataLocationFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;


@SpringBootTest(classes = com.epam.sdmxproxy.SdmxApiProxyApplication.class)
@TestPropertySource(properties = {
        "spring.main.allow-bean-definition-overriding=true"
})
public class XmlStructureReaderTest {

    @Autowired
    private SdmxSourceReadableDataLocationFactory sdmxSourceReadableDataLocationFactory;
    @Autowired
    private SdmxMLStructureReaderFactory sdmxMLStructureReaderFactory;
    @Autowired
    private IBeansBuilder iBeansBuilder;

    @Test
    void shouldConvertToSdmxBeans() {
        //GIVEN
        InputStream input = getClass().getResourceAsStream("imf_2_1_availability_response.xml");
        ReadableDataLocation location = sdmxSourceReadableDataLocationFactory.getReadableDataLocation(input);
        //WHEN
        SdmxBeans sdmxBeans = sdmxMLStructureReaderFactory.getSdmxBeans(location, iBeansBuilder);

        //THEN
        assertNotNull(sdmxBeans);
        assertNotNull(sdmxBeans.getContentConstraintBeans());
        assertEquals(1, sdmxBeans.getContentConstraintBeans().size());
        assertEquals("urn:sdmx:org.sdmx.infomodel.registry.DataConstraint=IMF.STA:NSDP(7.0.0)", sdmxBeans.getContentConstraintBeans().stream().findFirst().get().getUrn());
    }

}
