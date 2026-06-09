package com.epam.sdmxproxy.services.fixture.structure;

import com.epam.sdmxproxy.configuration.data.SdmxFormat;
import com.epam.sdmxproxy.configuration.data.fixture.StructureFixtureType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * JSON fixture that rescues annotation {@code value} fields lost by sdmx-core's SDMX-JSON 2.0
 * reader.
 * <p>
 * SDMX-JSON 2.0 defines {@code Annotation} with both a non-localised {@code value} (string) and
 * a localised {@code text} / {@code texts} pair (see structure schema 2.0.0). sdmx-core's
 * {@code SdmxJsonAnnotableUtil.buildAnnotation} ignores {@code value}, and the {@code AnnotationBean}
 * model exposes no slot for it, so the field is dropped before the proxy mapper runs.
 * <p>
 * This fixture walks every {@code annotations} array in the JSON tree and, for each annotation
 * that carries a {@code value} but no {@code text} / {@code texts}, rewrites {@code value} into
 * {@code text} (a non-localised string). sdmx-core's reader handles {@code text}, so the data
 * survives the round trip.
 * <p>
 * Trade-off: the wire field changes from {@code value} to {@code text}. Per the SDMX-JSON 2.0.0
 * structure schema, {@code text} is typed as {@code localisedBestMatchText} (a plain string), so
 * the substitution is type-correct; only the semantic distinction between localised and
 * non-localised is lost. When the annotation already carries {@code text} or {@code texts}, the
 * fixture leaves it untouched.
 * <p>
 * No configuration parameters are required.
 */
@Slf4j
@Component
public class AnnotationValueToTextJsonFixture implements StructureFixture {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public StructureFixtureType getType() {
        return StructureFixtureType.ANNOTATION_VALUE_TO_TEXT;
    }

    @Override
    public Set<SdmxFormat> supportedFormats() {
        return Set.of(SdmxFormat.JSON_STRUCTURE_2_0_0);
    }

    @Override
    public InputStream apply(InputStream input, Map<String, String> config) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(input);
            int rewritten = rewriteAnnotations(root);
            if (rewritten > 0) {
                log.debug("Rewrote {} annotation value->text entries", rewritten);
            }
            return new ByteArrayInputStream(OBJECT_MAPPER.writeValueAsBytes(root));
        } catch (IOException e) {
            log.error("Failed to process JSON for annotation value->text rewrite, returning original stream", e);
            return input;
        }
    }

    private int rewriteAnnotations(JsonNode node) {
        int total = 0;
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                if ("annotations".equals(entry.getKey()) && entry.getValue().isArray()) {
                    for (JsonNode annotation : entry.getValue()) {
                        if (rewriteOne(annotation)) {
                            total++;
                        }
                    }
                } else {
                    total += rewriteAnnotations(entry.getValue());
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                total += rewriteAnnotations(child);
            }
        }
        return total;
    }

    private boolean rewriteOne(JsonNode annotation) {
        if (!annotation.isObject()) {
            return false;
        }
        JsonNode value = annotation.get("value");
        if (value == null || !value.isTextual()) {
            return false;
        }
        if (annotation.has("text") || annotation.has("texts")) {
            return false;
        }
        ObjectNode obj = (ObjectNode) annotation;
        obj.set("text", value);
        obj.remove("value");
        return true;
    }
}
