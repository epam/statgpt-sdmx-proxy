package com.epam.sdmxproxy.configuration.data;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the {@link SdmxFormat} catalogue: every content type must be a well-formed media type and
 * no two members may collide on type + subtype + version. Pure config-module test -- no Spring,
 * so media types are parsed structurally rather than via {@code MediaType.valueOf}.
 */
class SdmxFormatTest {

    @Test
    void everyContentTypeIsParseableAndUnique() {
        Map<String, SdmxFormat> seen = new HashMap<>();
        for (SdmxFormat format : SdmxFormat.values()) {
            String contentType = format.getContentType();
            assertNotNull(contentType, format + " has a null content type");
            assertFalse(contentType.isBlank(), format + " has a blank content type");

            String[] parts = contentType.split(";");
            String[] typeSubtype = parts[0].trim().split("/");
            assertTrue(typeSubtype.length == 2 && !typeSubtype[0].isBlank() && !typeSubtype[1].isBlank(),
                    format + " content type is not a valid type/subtype: " + contentType);

            String version = "";
            for (int i = 1; i < parts.length; i++) {
                String param = parts[i].trim();
                if (param.toLowerCase().startsWith("version")) {
                    int eq = param.indexOf('=');
                    if (eq >= 0) {
                        version = param.substring(eq + 1).trim();
                    }
                }
            }

            String key = typeSubtype[0].trim().toLowerCase() + "/" + typeSubtype[1].trim().toLowerCase() + ";version=" + version;
            SdmxFormat previous = seen.put(key, format);
            assertNull(previous, "Duplicate type+subtype+version '" + key + "' shared by " + previous + " and " + format);
        }
    }
}
