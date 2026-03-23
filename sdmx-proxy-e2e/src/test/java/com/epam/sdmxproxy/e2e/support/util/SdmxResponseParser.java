package com.epam.sdmxproxy.e2e.support.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import io.restassured.path.json.JsonPath;
import io.restassured.path.xml.XmlPath;
import lombok.extern.slf4j.Slf4j;
import org.xml.sax.InputSource;

import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.StringReader;

/**
 * Parses SDMX XML/JSON responses and provides methods to crawl through response objects.
 * Supports both RestAssured path expressions and standard XPath/JSONPath evaluation.
 */
@Slf4j
public class SdmxResponseParser {

    private static final ObjectMapper jsonMapper = new ObjectMapper();
    private static final XmlMapper xmlMapper = new XmlMapper();
    private static final XPathFactory xPathFactory = XPathFactory.newInstance();

    /**
     * Parses a JSON response string into a JsonNode for navigation.
     *
     * @param jsonResponse JSON response string
     * @return JsonNode for crawling the JSON structure
     * @throws IllegalArgumentException if the JSON is invalid
     */
    public JsonNode parseJson(String jsonResponse) {
        try {
            return jsonMapper.readTree(jsonResponse);
        } catch (Exception e) {
            log.error("Failed to parse JSON response", e);
            throw new IllegalArgumentException("Invalid JSON response", e);
        }
    }

    /**
     * Parses an XML response string into an XmlPath for navigation.
     *
     * @param xmlResponse XML response string
     * @return XmlPath for crawling the XML structure
     * @throws IllegalArgumentException if the XML is invalid
     */
    public XmlPath parseXml(String xmlResponse) {
        try {
            return XmlPath.from(xmlResponse);
        } catch (Exception e) {
            log.error("Failed to parse XML response", e);
            throw new IllegalArgumentException("Invalid XML response", e);
        }
    }

    /**
     * Evaluates an XPath expression against an XML response.
     *
     * @param xmlResponse     XML response string
     * @param xpathExpression XPath expression (e.g., "//structure:Structure/structure:Name")
     * @return Result of the XPath evaluation as a string
     * @throws XPathExpressionException if the XPath expression is invalid
     */
    public String evaluateXPath(String xmlResponse, String xpathExpression) throws XPathExpressionException {
        try {
            XPath xpath = xPathFactory.newXPath();
            XPathExpression expr = xpath.compile(xpathExpression);
            InputSource inputSource = new InputSource(new StringReader(xmlResponse));
            return expr.evaluate(inputSource);
        } catch (Exception e) {
            log.error("Failed to evaluate XPath expression: {}", xpathExpression, e);
            throw new XPathExpressionException("XPath evaluation failed: " + e.getMessage());
        }
    }

    /**
     * Checks if a JSON path exists in the response.
     *
     * @param jsonResponse JSON response string
     * @param jsonPath     JSON path expression (e.g., "$.structure.structures[0].name")
     * @return true if the path exists, false otherwise
     */
    public boolean hasJsonPath(String jsonResponse, String jsonPath) {
        try {
            JsonPath path = JsonPath.from(jsonResponse);
            Object value = path.get(jsonPath);
            return value != null;
        } catch (Exception e) {
            log.debug("JSON path '{}' not found or invalid", jsonPath);
            return false;
        }
    }

    /**
     * Gets a JsonNode at the specified JSON path.
     *
     * @param jsonResponse JSON response string
     * @param jsonPath     JSON path expression (e.g., "$.structure.structures[0]")
     * @return JsonNode at the path, or null if not found
     */
    public JsonNode getJsonNode(String jsonResponse, String jsonPath) {
        try {
            JsonNode root = parseJson(jsonResponse);
            JsonPath path = JsonPath.from(jsonResponse);
            Object value = path.get(jsonPath);

            if (value == null) {
                return null;
            }

            // Convert the value back to JsonNode for further navigation
            return jsonMapper.valueToTree(value);
        } catch (Exception e) {
            log.debug("Failed to get JSON node at path '{}'", jsonPath, e);
            return null;
        }
    }

    /**
     * Gets a value from JSON response at the specified path.
     *
     * @param jsonResponse JSON response string
     * @param jsonPath     JSON path expression
     * @param clazz        Expected type of the value
     * @param <T>          Type of the value
     * @return Value at the path, or null if not found
     */
    public <T> T getJsonValue(String jsonResponse, String jsonPath, Class<T> clazz) {
        try {
            JsonPath path = JsonPath.from(jsonResponse);
            return path.get(jsonPath);
        } catch (Exception e) {
            log.debug("Failed to get JSON value at path '{}'", jsonPath, e);
            return null;
        }
    }

    /**
     * Gets a value from XML response using XPath.
     *
     * @param xmlResponse     XML response string
     * @param xpathExpression XPath expression
     * @return Value as string, or null if not found
     */
    public String getXmlValue(String xmlResponse, String xpathExpression) {
        try {
            return evaluateXPath(xmlResponse, xpathExpression);
        } catch (XPathExpressionException e) {
            log.debug("Failed to get XML value at XPath '{}'", xpathExpression, e);
            return null;
        }
    }

    /**
     * Gets a value from XML response using RestAssured XmlPath.
     *
     * @param xmlResponse XML response string
     * @param xmlPath     RestAssured XML path expression (e.g., "structure.Structure.Name")
     * @return Value as string, or null if not found
     */
    public String getXmlPathValue(String xmlResponse, String xmlPath) {
        try {
            XmlPath path = parseXml(xmlResponse);
            return path.getString(xmlPath);
        } catch (Exception e) {
            log.debug("Failed to get XML value at path '{}'", xmlPath, e);
            return null;
        }
    }
}
