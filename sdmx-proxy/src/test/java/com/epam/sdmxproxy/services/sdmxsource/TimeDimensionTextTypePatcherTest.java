package com.epam.sdmxproxy.services.sdmxsource;

import org.apache.xmlbeans.XmlCursor;
import org.apache.xmlbeans.XmlObject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.xml.namespace.QName;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Unit test for TimeDimensionTextTypePatcher to verify TimeDimension TextType patching.
 */
class TimeDimensionTextTypePatcherTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "structures_with_only_string_timeperiod.xml"
    })
    void testPatchTimeDimensionTextTypeFromStringToObservationalTimePeriod(String fileName) throws Exception {
        InputStream xmlStream = getClass().getResourceAsStream(fileName);
        XmlObject xmlDoc = XmlObject.Factory.parse(xmlStream);

        TimeDimensionTextTypePatcher.patchTimeDimensionTextType(xmlDoc);

        verifyAllTimeDimensionsHaveCorrectTextType(xmlDoc);
    }

    private void verifyAllTimeDimensionsHaveCorrectTextType(XmlObject xmlDoc) {
        List<String> foundTextTypes = new ArrayList<>();

        XmlCursor cursor = xmlDoc.newCursor();
        try {
            findAndVerifyTextFormats(cursor, foundTextTypes);
        } finally {
            cursor.dispose();
        }

        assertFalse(foundTextTypes.isEmpty(),
                "Should find at least one TimeDimension/TextFormat element");

        for (String textType : foundTextTypes) {
            assertNotEquals("String", textType,
                    "TimeDimension TextType should NOT be 'String' after patching. Found: " + textType);
            assertEquals("ObservationalTimePeriod", textType,
                    "TimeDimension TextType should be 'ObservationalTimePeriod' after patching. Found: " + textType);
        }
    }

    private void findAndVerifyTextFormats(XmlCursor cursor, List<String> foundTextTypes) {
        if (cursor == null) {
            return;
        }

        String localName = cursor.getName() != null ? cursor.getName().getLocalPart() : null;

        if ("TimeDimension".equals(localName)) {
            XmlCursor timeDimCursor = cursor.newCursor();
            try {
                if (timeDimCursor.toFirstChild()) {
                    do {
                        String childLocalName = timeDimCursor.getName() != null
                                ? timeDimCursor.getName().getLocalPart()
                                : null;

                        if ("LocalRepresentation".equals(childLocalName)) {
                            XmlCursor localRepCursor = timeDimCursor.newCursor();
                            try {
                                if (localRepCursor.toFirstChild()) {
                                    do {
                                        String textFormatLocalName = localRepCursor.getName() != null
                                                ? localRepCursor.getName().getLocalPart()
                                                : null;

                                        if ("TextFormat".equals(textFormatLocalName)) {
                                            String textType = localRepCursor.getAttributeText(new QName("textType"));
                                            if (textType != null) {
                                                foundTextTypes.add(textType);
                                            }
                                        }
                                    } while (localRepCursor.toNextSibling());
                                }
                            } finally {
                                localRepCursor.dispose();
                            }
                        }
                    } while (timeDimCursor.toNextSibling());
                }
            } finally {
                timeDimCursor.dispose();
            }
        }

        if (cursor.toFirstChild()) {
            do {
                findAndVerifyTextFormats(cursor, foundTextTypes);
            } while (cursor.toNextSibling());
            cursor.toParent();
        }
    }
}
