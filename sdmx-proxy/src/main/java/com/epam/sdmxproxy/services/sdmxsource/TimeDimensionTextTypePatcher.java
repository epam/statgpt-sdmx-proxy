package com.epam.sdmxproxy.services.sdmxsource;

import lombok.extern.slf4j.Slf4j;
import org.apache.xmlbeans.XmlCursor;
import org.apache.xmlbeans.XmlObject;

import javax.xml.namespace.QName;

/**
 * Utility class to patch TimeDimension TextType from "String" to "ObservationalTimePeriod"
 * in SDMX StructureDocument (works with any version: 2.1, 3.0, etc.).
 * <p>
 * Navigates through XML structure: TimeDimension -> LocalRepresentation -> TextFormat -> textType attribute
 * Uses low-level XmlCursor for version-independent navigation.
 */
@Slf4j
public class TimeDimensionTextTypePatcher {

    private static final String TARGET_TEXT_TYPE = "ObservationalTimePeriod";
    private static final String SOURCE_TEXT_TYPE = "String";

    /**
     * Patches the TimeDimension TextType from "String" to "ObservationalTimePeriod"
     * in SDMX StructureDocument (any version).
     *
     * @param structureDoc The StructureDocument to patch (any SDMX version)
     */
    public static void patchTimeDimensionTextType(XmlObject structureDoc) {
        if (structureDoc == null) {
            log.debug("StructureDocument is null, skipping TimeDimension TextType patch");
            return;
        }

        patchTimeDimensionWithCursor(structureDoc);
    }

    /**
     * Patches TimeDimension TextType using XmlCursor for version-independent navigation.
     * Works with any SDMX version by searching for elements by name, not by namespace.
     *
     * @param xmlObject The XML document to patch
     */
    private static void patchTimeDimensionWithCursor(XmlObject xmlObject) {
        try {
            XmlCursor cursor = xmlObject.newCursor();
            try {
                // Navigate through the entire document looking for TimeDimension elements
                // We use toChild() and toNextSibling() to traverse the tree without knowing exact structure
                patchTimeDimensionsRecursive(cursor);
            } finally {
                cursor.dispose();
            }
        } catch (Exception e) {
            log.debug("Error patching TimeDimension TextType: {}", e.getMessage(), e);
        }
    }

    /**
     * Recursively traverses XML tree looking for TimeDimension -> LocalRepresentation -> TextFormat pattern.
     */
    private static void patchTimeDimensionsRecursive(XmlCursor cursor) {
        if (cursor == null) {
            return;
        }

        // Check if current element is TimeDimension (check local name, ignore namespace)
        String localName = cursor.getName() != null ? cursor.getName().getLocalPart() : null;

        if ("TimeDimension".equals(localName)) {
            // Found TimeDimension, look for LocalRepresentation -> TextFormat
            patchTextFormatInTimeDimension(cursor);
        }

        // Recursively traverse children
        if (cursor.toFirstChild()) {
            do {
                patchTimeDimensionsRecursive(cursor);
                // After processing children, cursor is still on the child, need to go back
            } while (cursor.toNextSibling());
            // Go back to parent
            cursor.toParent();
        }
    }

    /**
     * Patches TextFormat within a TimeDimension element.
     */
    private static void patchTextFormatInTimeDimension(XmlCursor timeDimensionCursor) {
        // Save current position
        timeDimensionCursor.push();

        try {
            // Look for LocalRepresentation child
            if (timeDimensionCursor.toFirstChild()) {
                do {
                    String localName = timeDimensionCursor.getName() != null
                            ? timeDimensionCursor.getName().getLocalPart()
                            : null;

                    if ("LocalRepresentation".equals(localName)) {
                        // Found LocalRepresentation, look for TextFormat
                        XmlCursor localRepCursor = timeDimensionCursor.newCursor();
                        try {
                            if (localRepCursor.toFirstChild()) {
                                do {
                                    String textFormatLocalName = localRepCursor.getName() != null
                                            ? localRepCursor.getName().getLocalPart()
                                            : null;

                                    if ("TextFormat".equals(textFormatLocalName)) {
                                        // Found TextFormat! Patch the textType attribute
                                        patchTextTypeAttribute(localRepCursor);
                                    }
                                } while (localRepCursor.toNextSibling());
                            }
                        } finally {
                            localRepCursor.dispose();
                        }
                    }
                } while (timeDimensionCursor.toNextSibling());
            }
        } finally {
            // Restore position
            timeDimensionCursor.pop();
        }
    }

    /**
     * Patches the textType attribute on a TextFormat element.
     */
    private static void patchTextTypeAttribute(XmlCursor textFormatCursor) {
        try {
            // Get current textType value (unqualified attribute)
            String textType = textFormatCursor.getAttributeText(new QName("textType"));

            // Patch if textType is "String" or null
            if (textType == null || SOURCE_TEXT_TYPE.equals(textType)) {
                textFormatCursor.setAttributeText(new QName("textType"), TARGET_TEXT_TYPE);
                log.debug("Patched TimeDimension TextType from '{}' to '{}'",
                        textType != null ? textType : "null", TARGET_TEXT_TYPE);
            } else {
                log.debug("TimeDimension TextType is already '{}', no patch needed", textType);
            }
        } catch (Exception e) {
            log.debug("Error patching textType attribute: {}", e.getMessage());
        }
    }
}
