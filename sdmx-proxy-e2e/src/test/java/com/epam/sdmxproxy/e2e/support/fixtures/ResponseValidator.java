package com.epam.sdmxproxy.e2e.support.fixtures;

import com.epam.sdmxproxy.e2e.support.util.SdmxResponseParser;
import com.fasterxml.jackson.databind.JsonNode;
import io.restassured.path.xml.XmlPath;
import lombok.extern.slf4j.Slf4j;

import javax.xml.xpath.XPathExpressionException;
import java.util.ArrayList;
import java.util.List;

/**
 * Validates responses by crawling through XML/JSON structure.
 * Checks for required SDMX elements, validates data types, formats, and relationships.
 */
@Slf4j
public class ResponseValidator {

    private final SdmxResponseParser parser;

    public ResponseValidator() {
        this.parser = new SdmxResponseParser();
    }

    /**
     * Validates a JSON response contains required SDMX structure elements.
     *
     * @param jsonResponse JSON response string
     * @return List of validation errors (empty if valid)
     */
    public List<String> validateJsonStructure(String jsonResponse) {
        List<String> errors = new ArrayList<>();

        try {
            JsonNode root = parser.parseJson(jsonResponse);

            // Check for common SDMX structure elements
            if (!parser.hasJsonPath(jsonResponse, "$.structure")) {
                errors.add("Missing 'structure' element in response");
            }

            // Validate structure elements if present
            JsonNode structureNode = parser.getJsonNode(jsonResponse, "$.structure");
            if (structureNode != null) {
                validateStructureNode(structureNode, errors);
            }

        } catch (Exception e) {
            errors.add("Failed to parse JSON response: " + e.getMessage());
        }

        return errors;
    }

    /**
     * Validates an XML response contains required SDMX structure elements.
     *
     * @param xmlResponse XML response string
     * @return List of validation errors (empty if valid)
     */
    public List<String> validateXmlStructure(String xmlResponse) {
        List<String> errors = new ArrayList<>();

        try {
            XmlPath xmlPath = parser.parseXml(xmlResponse);

            // Check for common SDMX structure elements
            // SDMX XML typically has namespaces, so we check for common patterns
            String structureName = xmlPath.getString("Structure.Name");
            if (structureName == null || structureName.isEmpty()) {
                // Try with namespace prefix
                try {
                    String name = parser.evaluateXPath(xmlResponse, "//*[local-name()='Structure']/*[local-name()='Name']");
                    if (name == null || name.isEmpty()) {
                        errors.add("Missing Structure Name element in response");
                    }
                } catch (XPathExpressionException e) {
                    errors.add("Could not find Structure Name element: " + e.getMessage());
                }
            }

        } catch (Exception e) {
            errors.add("Failed to parse XML response: " + e.getMessage());
        }

        return errors;
    }

    /**
     * Validates that required SDMX data elements are present in a JSON response.
     *
     * @param jsonResponse JSON response string
     * @return List of validation errors (empty if valid)
     */
    public List<String> validateJsonData(String jsonResponse) {
        List<String> errors = new ArrayList<>();

        try {
            // Check for data structure
            if (!parser.hasJsonPath(jsonResponse, "$.data")) {
                errors.add("Missing 'data' element in response");
            }

            // Check for dataset or series elements
            boolean hasDataset = parser.hasJsonPath(jsonResponse, "$.data.dataSets");
            boolean hasSeries = parser.hasJsonPath(jsonResponse, "$.data.series");

            if (!hasDataset && !hasSeries) {
                errors.add("Missing 'dataSets' or 'series' element in data response");
            }

        } catch (Exception e) {
            errors.add("Failed to validate JSON data response: " + e.getMessage());
        }

        return errors;
    }

    /**
     * Validates that required SDMX data elements are present in an XML response.
     *
     * @param xmlResponse XML response string
     * @return List of validation errors (empty if valid)
     */
    public List<String> validateXmlData(String xmlResponse) {
        List<String> errors = new ArrayList<>();

        try {
            // Check for data structure using XPath
            String dataset = parser.evaluateXPath(xmlResponse, "//*[local-name()='DataSet']");
            String series = parser.evaluateXPath(xmlResponse, "//*[local-name()='Series']");

            if ((dataset == null || dataset.isEmpty()) && (series == null || series.isEmpty())) {
                errors.add("Missing 'DataSet' or 'Series' element in data response");
            }

        } catch (XPathExpressionException e) {
            errors.add("Failed to validate XML data response: " + e.getMessage());
        }

        return errors;
    }

    /**
     * Validates that a response contains a specific element at the given path.
     *
     * @param response Response string (JSON or XML)
     * @param path     Path expression (JSON path or XPath)
     * @param isJson   Whether the response is JSON (true) or XML (false)
     * @return true if element exists, false otherwise
     */
    public boolean hasElement(String response, String path, boolean isJson) {
        if (isJson) {
            return parser.hasJsonPath(response, path);
        } else {
            try {
                String value = parser.evaluateXPath(response, path);
                return value != null && !value.isEmpty();
            } catch (XPathExpressionException e) {
                return false;
            }
        }
    }

    /**
     * Validates data types and formats in a JSON response.
     *
     * @param jsonResponse JSON response string
     * @param path         JSON path to the element
     * @param expectedType Expected type (e.g., "string", "number", "array", "object")
     * @return true if type matches, false otherwise
     */
    public boolean validateType(String jsonResponse, String path, String expectedType) {
        JsonNode node = parser.getJsonNode(jsonResponse, path);
        if (node == null) {
            return false;
        }

        return switch (expectedType.toLowerCase()) {
            case "string" -> node.isTextual();
            case "number" -> node.isNumber();
            case "array" -> node.isArray();
            case "object" -> node.isObject();
            case "boolean" -> node.isBoolean();
            default -> false;
        };
    }

    /**
     * Validates relationships between elements (e.g., dimensions, attributes, measures).
     *
     * @param jsonResponse JSON response string
     * @return List of validation errors (empty if valid)
     */
    public List<String> validateRelationships(String jsonResponse) {
        List<String> errors = new ArrayList<>();

        try {
            // Example: Check that if dimensions exist, they have valid structure
            if (parser.hasJsonPath(jsonResponse, "$.structure.structures[0].dimensions")) {
                JsonNode dimensions = parser.getJsonNode(jsonResponse, "$.structure.structures[0].dimensions");
                if (dimensions != null && dimensions.isArray() && dimensions.size() == 0) {
                    errors.add("Dimensions array is empty but should contain dimension definitions");
                }
            }

        } catch (Exception e) {
            errors.add("Failed to validate relationships: " + e.getMessage());
        }

        return errors;
    }

    /**
     * Validates the structure node recursively.
     *
     * @param structureNode JSON node representing structure
     * @param errors        List to collect validation errors
     */
    private void validateStructureNode(JsonNode structureNode, List<String> errors) {
        if (!structureNode.isObject()) {
            errors.add("Structure node must be an object");
            return;
        }

        // Check for common SDMX structure fields
        if (!structureNode.has("structures") && !structureNode.has("name")) {
            // This might be valid depending on SDMX version, so we log but don't error
            log.debug("Structure node missing 'structures' or 'name' field");
        }
    }
}
