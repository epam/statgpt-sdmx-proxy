package com.epam.sdmxproxy.services.sdmxsource;

import com.epam.sdmxproxy.services.sdmxsource.CustomSdmxStructureIterator.AttraMapping;
import com.epam.sdmxproxy.services.sdmxsource.CustomSdmxStructureIterator.JsonDatasetStructuralMetadata;
import com.fasterxml.jackson.core.JsonToken;
import io.sdmx.api.collection.KeyValue;
import io.sdmx.api.exception.SdmxSemmanticException;
import io.sdmx.api.format.FormatDetails;
import io.sdmx.api.io.ReadableDataLocation;
import io.sdmx.api.sdmx.constants.ATTRIBUTE_ATTACHMENT_LEVEL;
import io.sdmx.api.sdmx.constants.DATASET_ACTION;
import io.sdmx.api.sdmx.constants.DATASET_POSITION;
import io.sdmx.api.sdmx.constants.DATA_TYPE;
import io.sdmx.api.sdmx.engine.DataReaderEngine;
import io.sdmx.api.sdmx.manager.structure.SdmxBeanRetrievalManager;
import io.sdmx.api.sdmx.model.beans.IDatasetStructures;
import io.sdmx.api.sdmx.model.beans.base.AnnotationBean;
import io.sdmx.api.sdmx.model.beans.base.DataProviderBean;
import io.sdmx.api.sdmx.model.beans.base.IURNAbsolute;
import io.sdmx.api.sdmx.model.beans.datastructure.AttributeBean;
import io.sdmx.api.sdmx.model.beans.datastructure.DataStructureBean;
import io.sdmx.api.sdmx.model.beans.datastructure.DataflowBean;
import io.sdmx.api.sdmx.model.beans.datastructure.GroupBean;
import io.sdmx.api.sdmx.model.beans.registry.ProvisionAgreementBean;
import io.sdmx.api.sdmx.model.data.FooterMessage;
import io.sdmx.api.sdmx.model.data.IDatasetAttributes;
import io.sdmx.api.sdmx.model.data.Keyable;
import io.sdmx.api.sdmx.model.data.Observation;
import io.sdmx.api.sdmx.model.header.DatasetStructureReferenceBean;
import io.sdmx.core.sdmx.api.error.DataError.ERROR_POSITION;
import io.sdmx.core.sdmx.api.error.DataReaderExceptionHandler;
import io.sdmx.core.sdmx.engine.data.AbstractDataReaderEngine;
import io.sdmx.core.sdmx.error.DataReadException.SEVERITY;
import io.sdmx.core.sdmx.error.DataReadException.TYPE;
import io.sdmx.format.json.engine.data.reader.HeaderIterator;
import io.sdmx.format.json.model.SdmxJsonDataFormat;
import io.sdmx.im.beans.container.DatasetStructures;
import io.sdmx.im.data.DatasetAttributes;
import io.sdmx.im.data.KeyableImpl;
import io.sdmx.im.data.ObservationImpl;
import io.sdmx.im.header.DatasetHeaderBeanImpl;
import io.sdmx.utils.core.collection.KeyValueImpl;
import io.sdmx.utils.core.date.DateUtil;
import io.sdmx.utils.core.object.ObjectUtil;
import io.sdmx.utils.json.JsonReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Custom SDMX-JSON 2.0 data reader that supports both indexed and inline attribute values
 * in data.dataSets (per SDMX-JSON 2.0 spec: when component values is empty, values appear inline).
 * Extends/copies sdmx-core SdmxJsonDataReaderEngineV2 with mixed attribute parsing.
 */
public class CustomSdmxJsonDataReaderEngineV2 extends AbstractDataReaderEngine {

    private static final Logger LOG = LoggerFactory.getLogger(CustomSdmxJsonDataReaderEngineV2.class);
    private static final long serialVersionUID = 1260457180276L;

    private JsonReader jReader;
    private boolean isFlat = false;
    private Integer obsKey = null;
    private List<List<String>> obsValues = null;
    private List<Integer> annotations;

    private String currentKeyEncoded;
    private List<KeyValue> currentDsAttributes;
    private JsonDatasetStructuralMetadata currentDsStructuralMetadata;
    private List<JsonDatasetStructuralMetadata> dsStructuralMetadata = new ArrayList<>();
    private Integer currentStructureIndex;
    private List<Integer> structureIndexes = new ArrayList<>();
    private List<AttributeValue> currentDsAttributeValues;
    private List<List<AttributeValue>> dsAttributeValues = new ArrayList<>();

    private Map<String, Set<String>> groupMap;
    private Map<String, Set<String>> groupMapMandatoryElements;
    private List<Keyable> groupAndSeriesStack = new ArrayList<>();
    // Set by lazyLoadKey when the series object had no "observations" sub-object;
    // tells moveNextObservationInternal to return false without consuming a token
    // (the cursor is already at END_OBJECT of the empty series). See issue #80 (#1).
    private boolean currentSeriesIsEmpty;

    private ReadableDataLocation dataLocation;

    public CustomSdmxJsonDataReaderEngineV2(ReadableDataLocation dataLocation, SdmxBeanRetrievalManager beanRetrieval,
                                            DataStructureBean defaultDsd, DataflowBean defaultDataflow, ProvisionAgreementBean defaultProvisionAgreement,
                                            DataReaderExceptionHandler exceptionHandler) {
        super(beanRetrieval, defaultDsd, defaultDataflow, defaultProvisionAgreement, exceptionHandler);
        this.dataLocation = dataLocation;
        preParseForDatasetAttributes();
        reset();
    }

    private void preParseForDatasetAttributes() {
        try {
            jReader = new JsonReader(dataLocation);
            while (jReader.moveNext()) {
                if (jReader.getCurrentFieldName() != null && jReader.getCurrentFieldName().equals("dataSets")
                        && jReader.isParentField("data")) {
                    analyseDataSets();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            jReader.close();
        }
    }

    private void analyseDataSets() {
        while (jReader.moveNext()) {
            String fieldName = jReader.getCurrentFieldName();
            switch (fieldName) {
                case "structure":
                    structureIndexes.add(jReader.getValueAsInt());
                    break;
                case "observations":
                case "series":
                    jReader.moveToEndCurrentObject();
                    break;
                case "attributes":
                    if (jReader.isStartArray()) {
                        dsAttributeValues.add(readMixedAttributeArray());
                    }
                    break;
                default:
                    break;
            }
        }
        currentStructureIndex = structureIndexes.isEmpty() ? 0 : structureIndexes.get(0);
        currentDsAttributeValues = dsAttributeValues.isEmpty() ? new ArrayList<>() : dsAttributeValues.get(0);
    }

    /**
     * Reads an attributes array that may contain indices (numbers), nulls, strings, objects, or arrays.
     */
    private List<AttributeValue> readMixedAttributeArray() {
        List<AttributeValue> result = new ArrayList<>();
        while (jReader.moveNext() && jReader.getToken() != JsonToken.END_ARRAY) {
            result.add(readMixedAttributeElement());
        }
        return result;
    }

    private AttributeValue readMixedAttributeElement() {
        JsonToken token = jReader.getToken();
        if (token == JsonToken.VALUE_NULL) {
            return AttributeValue.ofNull();
        }
        if (token == JsonToken.VALUE_NUMBER_INT) {
            return AttributeValue.ofIndex(jReader.getValueAsInt());
        }
        if (token == JsonToken.VALUE_STRING) {
            String s = jReader.getValueAsString();
            return s == null ? AttributeValue.ofNull() : AttributeValue.ofDirect(Collections.singletonList(s));
        }
        if (token == JsonToken.START_OBJECT) {
            return AttributeValue.ofDirect(extractLocalizedTextFromObject());
        }
        if (token == JsonToken.START_ARRAY) {
            return readMixedAttributeArrayElement();
        }
        return AttributeValue.ofNull();
    }

    private List<String> extractLocalizedTextFromObject() {
        Map<String, String> map = jReader.readStringMap();
        if (map == null || map.isEmpty()) {
            return Collections.emptyList();
        }
        String preferred = map.get("en");
        if (preferred != null) {
            return Collections.singletonList(preferred);
        }
        return Collections.singletonList(map.values().iterator().next());
    }

    private AttributeValue readMixedAttributeArrayElement() {
        List<Integer> indices = new ArrayList<>();
        List<String> direct = new ArrayList<>();
        boolean hasNumber = false;
        boolean hasNonNumber = false;
        while (jReader.moveNext() && jReader.getToken() != JsonToken.END_ARRAY) {
            JsonToken t = jReader.getToken();
            if (t == JsonToken.VALUE_NULL) {
                continue;
            }
            if (t == JsonToken.VALUE_NUMBER_INT) {
                indices.add(jReader.getValueAsInt());
                hasNumber = true;
            } else if (t == JsonToken.VALUE_STRING) {
                String s = jReader.getValueAsString();
                if (s != null) {
                    direct.add(s);
                }
                hasNonNumber = true;
            } else if (t == JsonToken.START_OBJECT) {
                List<String> fromObj = extractLocalizedTextFromObject();
                direct.addAll(fromObj);
                hasNonNumber = true;
            } else if (t == JsonToken.START_ARRAY) {
                AttributeValue nested = readMixedAttributeArrayElement();
                if (nested.isIndexed()) {
                    if (nested.getIndex() != null) {
                        indices.add(nested.getIndex());
                    } else if (nested.getIndices() != null) {
                        indices.addAll(nested.getIndices());
                    }
                    hasNumber = true;
                } else if (nested.getDirectValues() != null) {
                    direct.addAll(nested.getDirectValues());
                    hasNonNumber = true;
                }
            }
        }
        if (hasNonNumber) {
            return AttributeValue.ofDirect(direct);
        }
        if (hasNumber && indices.size() == 1) {
            return AttributeValue.ofIndex(indices.get(0));
        }
        if (hasNumber && indices.size() > 1) {
            return AttributeValue.ofIndices(indices);
        }
        return AttributeValue.ofNull();
    }

    /**
     * Skips the current array (used when we need to skip attributes during dataset iteration).
     */
    private void skipCurrentArray() {
        if (jReader.isStartArray()) {
            jReader.moveToEndArray();
        }
    }

    @Override
    public void reset() {
        super.reset();

        if (jReader != null) {
            jReader.close();
        }
        jReader = new JsonReader(dataLocation);
        CustomSdmxJsonMetadataIteratorV2 metadataIterator = new CustomSdmxJsonMetadataIteratorV2(jReader, this.exceptionHandler);

        LOG.debug("Iterate JSON Data Message");
        jReader.iterate(metadataIterator);
        HeaderIterator header = metadataIterator.getHeaderIterator();
        if (header != null) {
            headerBean = header.getHeader();
        }

        List<CustomSdmxStructureIterator> structureIterators = metadataIterator.getStructureIterators();
        if (structureIterators == null || structureIterators.isEmpty()) {
            throw new SdmxSemmanticException("Can not read JSON dataset, missing structure section");
        }

        structureIterators.forEach(i -> dsStructuralMetadata.add(i.getJsonDatasetStructuralMetadata()));

        currentDsStructuralMetadata = dsStructuralMetadata.get(currentStructureIndex);
        currentDsAttributes = decodeMixed(currentDsAttributeValues, currentDsStructuralMetadata.getDatasetAttributeList(), "attribute");

        LOG.debug("Reset JsonReader");
        jReader.reset();

        LOG.debug("Move to start of dataSets Array");
        while (jReader.moveNextStartArray()) {
            if ("dataSets".equals(jReader.getCurrentFieldName())) {
                datasetPosition = null;
                LOG.debug("Move successful");
                return;
            }
        }
        LOG.debug("dataSets Array not found - no data");
        super.hasNext = false;
    }

    @Override
    public FormatDetails getFormatDetails() {
        return new SdmxJsonDataFormat(DATA_TYPE.SDMXJSON_2_0_0, null).getFormatDetails();
    }

    @Override
    public String getDataFormat() {
        return "SDMX-JSON";
    }

    @Override
    public DataReaderEngine createCopy() {
        return new CustomSdmxJsonDataReaderEngineV2(dataLocation.copy(), beanRetrieval, super.defaultDsd,
                super.defaultDataflow, super.defaultProvisionAgreement, exceptionHandler);
    }

    @Override
    protected IDatasetAttributes lazyLoadDatasetAttributes() {
        return DatasetAttributes.builder().attributes(currentDsAttributes).build();
    }

    @Override
    public List<FooterMessage> close() {
        jReader.close();
        dataLocation.close();
        return new ArrayList<>();
    }

    @Override
    public boolean moveNextDataset() {
        boolean result = super.moveNextDataset();

        if (!result) {
            return false;
        }

        int dsPos = getDatasetPosition();
        currentStructureIndex = dsPos >= 0 && dsPos < structureIndexes.size() ? structureIndexes.get(dsPos) : currentStructureIndex;
        currentDsStructuralMetadata = dsStructuralMetadata.get(currentStructureIndex);
        currentDsAttributeValues = dsAttributeValues.size() > getDatasetPosition()
                ? dsAttributeValues.get(getDatasetPosition())
                : new ArrayList<>();
        currentDsAttributes = decodeMixed(currentDsAttributeValues, currentDsStructuralMetadata.getDatasetAttributeList(), "attribute");
        this.currentDatasetAttributes = lazyLoadDatasetAttributes();
        IDatasetStructures dsStructures = new DatasetStructures(
                currentDsStructuralMetadata.getDatasetStructureReference().getStructureReference(), beanRetrieval);
        currentDsd = dsStructures.getDataStructure();

        if (result) {
            groupMap = new HashMap<>();
            groupMapMandatoryElements = new HashMap<>();
            for (AttributeBean attributeBean : this.currentDsd.getGroupAttributes()) {
                Set<String> set = groupMap.get(attributeBean.getAttachmentGroup());
                if (set == null) {
                    set = new HashSet<>();
                    groupMap.put(attributeBean.getAttachmentGroup(), set);
                }
                set.add(attributeBean.getId());

                if (attributeBean.isMandatory()) {
                    Set<String> setMandatory = groupMapMandatoryElements.get(attributeBean.getAttachmentGroup());
                    if (setMandatory == null) {
                        setMandatory = new HashSet<>();
                        groupMapMandatoryElements.put(attributeBean.getAttachmentGroup(), setMandatory);
                    }
                    setMandatory.add(attributeBean.getId());
                }
            }
        }
        return result;
    }

    @Override
    protected boolean moveNextDatasetInternal() {
        while (jReader.moveNextStartObject()) {
            if (jReader.isParentField("dataSets")) {
                datasetPosition = DATASET_POSITION.DATASET;

                DATASET_ACTION action = DATASET_ACTION.INFORMATION;
                String datasetId = null;
                IURNAbsolute<DataProviderBean> dataProviderRef = null;
                Date reportingBeginDate = null;
                Date reportingEndDate = null;
                Date validFrom = null;
                Date validTo = null;
                int publicationYear = -1;
                String publicationPeriod = null;
                String reportingYearStartDate = null;
                DatasetStructureReferenceBean dsRef = currentDsStructuralMetadata.getDatasetStructureReference();
                boolean hasData = false;
                outer:
                while (jReader.moveNext()) {
                    if (jReader.isEndObject() && jReader.getCurrentStackItem() != null && "dataSets".equals(jReader.getCurrentStackItem().getFieldName())) {
                        break;
                    }
                    String fieldName = jReader.getCurrentFieldName();
                    switch (fieldName) {
                        case "observations":
                            isFlat = true;
                            hasData = true;
                            break outer;
                        case "series":
                            isFlat = false;
                            hasData = true;
                            break outer;
                        case "annotations":
                            annotations = jReader.readIntegerArray();
                            break;
                        case "attributes":
                            // Dataset attributes were already pre-parsed by preParseForDatasetAttributes;
                            // skip the array. JsonReader.moveNext() advances past FIELD_NAME directly to
                            // its value token, so we are already positioned at START_ARRAY here -- calling
                            // moveNext() again would step into the array body and break moveToEndArray's
                            // depth tracking, causing the iteration to fall through into the series and
                            // misread an inner `observations` field as the dataset-level one.
                            if (jReader.isStartArray()) {
                                jReader.moveToEndArray();
                            }
                            break;
                        case "action":
                            action = DATASET_ACTION.getAction(jReader.getValueAsString());
                            break;
                        case "reportingBegin":
                            reportingBeginDate = DateUtil.formatDate(jReader.getValueAsString(), true);
                            break;
                        case "reportingEnd":
                            reportingEndDate = DateUtil.formatDate(jReader.getValueAsString(), true);
                            break;
                        case "validFrom":
                            validFrom = DateUtil.formatDate(jReader.getValueAsString(), true);
                            break;
                        case "validTo":
                            validTo = DateUtil.formatDate(jReader.getValueAsString(), true);
                            break;
                        case "publicationYear":
                            publicationYear = Integer.parseInt(jReader.getValueAsString());
                            break;
                        case "publicationPeriod":
                            publicationPeriod = jReader.getValueAsString();
                            break;
                        default:
                            break;
                    }
                }
                if (!hasData) {
                    LOG.debug("Skipping dataset with no series or observations");
                    continue;
                }
                super.datasetHeaderBean = new DatasetHeaderBeanImpl(datasetId, action, dsRef, dataProviderRef,
                        reportingBeginDate, reportingEndDate, validFrom, validTo, publicationYear, publicationPeriod,
                        reportingYearStartDate);
                return true;
            }
        }
        return false;
    }

    private List<KeyValue> decodeMixed(List<AttributeValue> rawValues, List<AttraMapping> decodeList, String nodeType) {
        List<KeyValue> returnList = new ArrayList<>();
        for (int i = 0; i < rawValues.size(); i++) {
            AttributeValue val = rawValues.get(i);
            if (val == null || val.isNull()) {
                continue;
            }
            if (i >= decodeList.size()) {
                continue;
            }
            AttraMapping m = decodeList.get(i);
            if (val.isIndexed()) {
                List<Integer> idxList = val.getIndex() != null
                        ? Collections.singletonList(val.getIndex())
                        : (val.getIndices() != null ? val.getIndices() : Collections.emptyList());
                for (Integer idx : idxList) {
                    Map<Integer, KeyValue> kvMap = m.componentMap;
                    boolean inBounds = idx != null && idx >= 0 && kvMap != null && idx < kvMap.size();
                    if (!inBounds) {
                        super.handleException(nodeType + " attribute '" + m.id + "' does not contain a value at index: " + idx,
                                true, SEVERITY.MEDIUM, TYPE.JSON_ATTRIBUTES_INDEX_OUT_OF_BOUNDS);
                    }
                    KeyValue decoded = kvMap.get(idx);
                    if (decoded != null) {
                        returnList.add(decoded);
                    }
                }
            } else {
                List<String> direct = val.getDirectValues();
                if (direct != null && !direct.isEmpty()) {
                    returnList.add(KeyValueImpl.getInstance(m.id, direct.toArray(new String[0])));
                }
            }
        }
        return returnList;
    }

    private List<KeyValue> decodeNestedMixed(List<List<AttributeValue>> encodedValues, List<AttraMapping> decodeList, String nodeType) {
        Map<String, List<String>> returnMap = new HashMap<>();
        for (int i = 0; i < encodedValues.size(); i++) {
            List<AttributeValue> list = encodedValues.get(i);
            if (list != null) {
                for (AttributeValue val : list) {
                    if (val == null || val.isNull()) {
                        continue;
                    }
                    if (decodeList.size() > i) {
                        AttraMapping m = decodeList.get(i);
                        if (val.isIndexed()) {
                            List<Integer> idxList = val.getIndex() != null
                                    ? Collections.singletonList(val.getIndex())
                                    : (val.getIndices() != null ? val.getIndices() : Collections.emptyList());
                            for (Integer idx : idxList) {
                                Map<Integer, KeyValue> kvMap = m.componentMap;
                                boolean inBounds = idx != null && idx >= 0 && kvMap != null && idx < kvMap.size();
                                if (!inBounds) {
                                    super.handleException(nodeType + " attribute '" + m.id + "' does not contain a value at index: " + idx,
                                            true, SEVERITY.MEDIUM, TYPE.JSON_ATTRIBUTES_INDEX_OUT_OF_BOUNDS);
                                }
                                KeyValue decoded = kvMap.get(idx);
                                if (decoded != null) {
                                    String concept = decoded.getConcept();
                                    String code = decoded.getCode();
                                    returnMap.computeIfAbsent(concept, k -> new ArrayList<>()).add(code);
                                }
                            }
                        } else {
                            List<String> direct = val.getDirectValues();
                            if (direct != null && !direct.isEmpty()) {
                                String concept = m.id;
                                for (String code : direct) {
                                    returnMap.computeIfAbsent(concept, k -> new ArrayList<>()).add(code);
                                }
                            }
                        }
                    }
                }
            }
        }
        List<KeyValue> returnList = new ArrayList<>();
        returnMap.forEach((k, vl) -> returnList.add(KeyValueImpl.getInstance(k, vl.toArray(new String[0]))));
        return returnList;
    }

    private List<KeyValue> decode(List<Integer> encodedValues, List<AttraMapping> decodeList, String nodeType) {
        List<KeyValue> returnList = new ArrayList<>();
        for (int i = 0; i < encodedValues.size(); i++) {
            Integer val = encodedValues.get(i);
            if (val != null && decodeList.size() > i) {
                AttraMapping m = decodeList.get(i);
                Map<Integer, KeyValue> kvMap = m.componentMap;
                boolean inBounds = (val >= 0) && (val < kvMap.size());
                if (!inBounds || val > kvMap.size()) {
                    super.handleException(nodeType + " attribute '" + m.id + "' does not contain a value at index: " + val,
                            true, SEVERITY.MEDIUM, TYPE.JSON_ATTRIBUTES_INDEX_OUT_OF_BOUNDS);
                }
                KeyValue decoded = decodeList.get(i).componentMap.get(val);
                if (decoded != null) {
                    returnList.add(decoded);
                }
            }
        }
        return returnList;
    }

    private List<KeyValue> decodeNested(List<List<Integer>> encodedValues, List<AttraMapping> decodeList, String nodeType) {
        Map<String, List<String>> returnMap = new HashMap<>();
        for (int i = 0; i < encodedValues.size(); i++) {
            List<Integer> list = encodedValues.get(i);
            if (list != null) {
                for (Integer val : list) {
                    if (decodeList.size() > i) {
                        AttraMapping m = decodeList.get(i);
                        Map<Integer, KeyValue> kvMap = m.componentMap;
                        boolean inBounds = (val >= 0) && (val < kvMap.size());
                        if (!inBounds) {
                            super.handleException(nodeType + " attribute '" + m.id + "' does not contain a value at index: " + val,
                                    true, SEVERITY.MEDIUM, TYPE.JSON_ATTRIBUTES_INDEX_OUT_OF_BOUNDS);
                        }
                        KeyValue decoded = kvMap.get(val);
                        if (decoded != null) {
                            String concept = decoded.getConcept();
                            String code = decoded.getCode();
                            returnMap.computeIfAbsent(concept, k -> new ArrayList<>()).add(code);
                        }
                    }
                }
            }
        }
        List<KeyValue> returnList = new ArrayList<>();
        returnMap.forEach((k, vl) -> returnList.add(KeyValueImpl.getInstance(k, vl.toArray(new String[0]))));
        return returnList;
    }

    @Override
    protected boolean moveNextKeyableInternal() {
        if (!groupAndSeriesStack.isEmpty()) {
            return true;
        }

        if (datasetPosition == DATASET_POSITION.OBSERVATION && isFlat && jReader.isStartArray()
                && jReader.isParentField("observations")) {
            return true;
        }
        while (jReader.moveNext()) {
            if (isFlat) {
                if (jReader.isStartArray()) {
                    datasetPosition = DATASET_POSITION.SERIES;
                    String fieldName = jReader.getCurrentFieldName();
                    String series = fieldName.substring(0, fieldName.lastIndexOf(":"));
                    if (!series.equals(currentKeyEncoded)) {
                        currentKeyEncoded = series;
                        return true;
                    }
                } else if (jReader.isEndObject() || jReader.isEndArray()) {
                    String fieldName = jReader.getCurrentStackItem().getFieldName();
                    if ("dataSets".equals(fieldName)) {
                        return false;
                    }
                }
            } else {
                if (jReader.isStartObject() && jReader.isParentField("series")) {
                    datasetPosition = DATASET_POSITION.SERIES;
                    currentKeyEncoded = jReader.getCurrentFieldName();
                    return true;
                } else if (jReader.isEndObject()) {
                    if (jReader.isParentField("observations")) {
                        return false;
                    }
                    if (jReader.isParentField("dataSets")) {
                        return false;
                    }
                }
            }
        }
        return false;
    }

    @Override
    protected boolean moveNextObservationInternal() {
        if (currentKey == null || !currentKey.isSeries()) {
            return false;
        }

        datasetPosition = DATASET_POSITION.OBSERVATION;
        obsKey = null;
        obsValues = null;
        if (isFlat) {
            if (jReader.isStartArray()) {
                String[] keySplit = jReader.getCurrentFieldName().split(":");
                obsKey = Integer.parseInt(keySplit[keySplit.length - 1]);
                readObsValuesNestedArray();
                return true;
            }
            if (jReader.isEndArray()) {
                jReader.moveNext();
                if (jReader.isStartArray()) {
                    String fieldName = jReader.getCurrentFieldName();
                    String series = fieldName.substring(0, fieldName.lastIndexOf(":"));
                    if (series.equals(currentKeyEncoded)) {
                        String[] keySplit = fieldName.split(":");
                        obsKey = Integer.parseInt(keySplit[keySplit.length - 1]);
                        readObsValuesNestedArray();
                        return true;
                    } else {
                        currentKeyEncoded = series;
                    }
                }
                return false;
            }
        } else {
            // Issue #80 (#1): for an empty series, lazyLoadKey already exited the
            // series object on END_OBJECT; consuming another moveNext here would
            // step past the next series's START_OBJECT and cause moveNextKeyableInternal
            // to miss it. The empty-series flag short-circuits this.
            if (currentSeriesIsEmpty) {
                currentSeriesIsEmpty = false;
                return false;
            }
            jReader.moveNext();
            if (jReader.isStartArray()) {
                obsKey = Integer.parseInt(jReader.getCurrentFieldName());
                readObsValuesNestedArray();
                return true;
            } else {
                return false;
            }
        }
        return false;
    }

    private void readObsValuesNestedArray() {
        obsValues = new ArrayList<>();
        while (jReader.moveNext()) {
            if (jReader.isStartArray()) {
                obsValues.add(jReader.readStringArray());
            } else if (jReader.isEndArray()) {
                break;
            } else {
                String val = jReader.getValueAsString();
                if (!ObjectUtil.validString(val)) {
                    obsValues.add(null);
                } else {
                    obsValues.add(Collections.singletonList(val));
                }
            }
        }
    }

    @Override
    protected Observation lazyLoadObservation() {
        List<AnnotationBean> annotations = new ArrayList<>();
        List<List<AttributeValue>> obsAttributes = new ArrayList<>();
        int measuresSize = currentDsd.getMeasures().size();

        for (int i = measuresSize; i < obsValues.size(); i++) {
            List<String> obsVals = obsValues.get(i);
            boolean shouldProcessAnnotations = currentDsStructuralMetadata.getObsAttributeList().size() < i + 1 - measuresSize;

            if (obsVals == null) {
                obsAttributes.add(null);
            } else if (obsVals.size() == 1) {
                try {
                    int asInt = Integer.parseInt(obsVals.get(0));
                    if (shouldProcessAnnotations) {
                        if (currentDsStructuralMetadata.getAnnotationList().size() < asInt + 1) {
                            super.handleException("Error in Series #" + (getKeyablePosition() + 1) + ", Observation #"
                                            + (getObsPosition() + 1)
                                            + ". Observation array length inconsistent with DSD and reported annotations",
                                    true, SEVERITY.MEDIUM, TYPE.JSON_ATTRIBUTES_INDEX_OUT_OF_BOUNDS);
                        } else {
                            annotations.add(currentDsStructuralMetadata.getAnnotationList().get(asInt));
                        }
                    } else {
                        obsAttributes.add(Collections.singletonList(AttributeValue.ofIndex(asInt)));
                    }
                } catch (NumberFormatException e) {
                    if (shouldProcessAnnotations) {
                        obsAttributes.add(null);
                    } else {
                        obsAttributes.add(Collections.singletonList(AttributeValue.ofDirect(obsVals)));
                    }
                }
            } else {
                List<AttributeValue> attrList = new ArrayList<>();
                boolean allInt = true;
                for (String val : obsVals) {
                    try {
                        int asInt = Integer.parseInt(val);
                        if (shouldProcessAnnotations) {
                            if (currentDsStructuralMetadata.getAnnotationList().size() < asInt + 1) {
                                super.handleException("Error in Series #" + (getKeyablePosition() + 1) + ", Observation #"
                                                + (getObsPosition() + 1)
                                                + ". Observation array length inconsistent with DSD and reported annotations",
                                        true, SEVERITY.MEDIUM, TYPE.JSON_ATTRIBUTES_INDEX_OUT_OF_BOUNDS);
                            } else {
                                annotations.add(currentDsStructuralMetadata.getAnnotationList().get(asInt));
                            }
                        } else {
                            attrList.add(AttributeValue.ofIndex(asInt));
                        }
                    } catch (NumberFormatException ex) {
                        allInt = false;
                        if (!shouldProcessAnnotations) {
                            attrList.add(AttributeValue.ofDirect(Collections.singletonList(val)));
                        }
                    }
                }
                if (!shouldProcessAnnotations) {
                    obsAttributes.add(attrList.isEmpty() ? null : attrList);
                }
            }
        }
        List<KeyValue> attributes = decodeNestedMixed(obsAttributes, currentDsStructuralMetadata.getObsAttributeList(), "observation");

        List<KeyValue> measuresValues = new ArrayList<>();
        List<String> measureIds = currentDsStructuralMetadata.getMeasureIds();
        for (int i = 0; i < measureIds.size(); i++) {
            String concept = measureIds.get(i);
            // Data may have fewer columns than structure (e.g. no obs-level attributes in JSON);
            // avoid IndexOutOfBounds when obsValues has only the primary measure value.
            List<String> values = i < obsValues.size() ? obsValues.get(i) : null;

            if (values != null) {
                if (values.size() == 1) {
                    measuresValues.add(KeyValueImpl.getInstance(concept, values.get(0)));
                } else if (values.size() > 1) {
                    measuresValues.add(KeyValueImpl.getInstance(concept, values.toArray(new String[0])));
                }
            }
        }

        AnnotationBean[] annArr = new AnnotationBean[annotations.size()];
        annotations.toArray(annArr);

        String obsTime = null;
        if (currentDsStructuralMetadata.getObsIds().size() <= obsKey) {
            super.handleException("Error in Series #" + (getKeyablePosition() + 1) + ", Observation #" + (getObsPosition() + 1)
                            + ". Observation references a Time Period at an invalid index of '" + obsKey + "'",
                    true, SEVERITY.MEDIUM, TYPE.JSON_ATTRIBUTES_INDEX_OUT_OF_BOUNDS);
        } else {
            obsTime = currentDsStructuralMetadata.getObsIds().get(obsKey);
            if (obsTime != null) {
                int obsTimeLength = obsTime.length();
                if (obsTimeLength > 4 && obsTime.charAt(5) == 'W' && obsTimeLength != 8) {
                    handleException("Weekly data for SDMX CSV is of the format YYYY-Www. Reported value is: " + obsTime,
                            true, SEVERITY.HIGH, TYPE.INVALID_DATE, ERROR_POSITION.OBSERVATION);
                }
            }
        }
        return ObservationImpl.multipleMeasureTimeSeries(currentKey, obsTime, measuresValues, attributes, annArr);
    }

    @Override
    protected Keyable lazyLoadKey() {
        if (!groupAndSeriesStack.isEmpty()) {
            return groupAndSeriesStack.remove(0);
        }

        String[] keyParts = currentKeyEncoded.split(":");
        List<KeyValue> key = decode(keyParts, currentDsStructuralMetadata.getSeriesList(), "series");
        List<KeyValue> attributes = new ArrayList<>();
        AnnotationBean[] annotations = null;
        currentSeriesIsEmpty = false;
        if (!isFlat) {
            while (jReader.moveNext()) {
                if (jReader.isStartArray()) {
                    if ("annotations".equals(jReader.getCurrentFieldName())) {
                        annotations = decodeAnnotations(jReader.readIntegerArray());
                    } else if ("attributes".equals(jReader.getCurrentFieldName())) {
                        List<List<AttributeValue>> readMixed = readMixedSeriesAttributesArray();
                        attributes = decodeNestedMixed(readMixed, currentDsStructuralMetadata.getSeriesAttributeList(), "series");
                    }
                } else if (jReader.isStartObject()) {
                    if (jReader.getCurrentFieldName().equals("observations")) {
                        break;
                    }
                } else if (jReader.isEndObject()) {
                    // Issue #80 (#1): an empty series (writeKey but no observations key) was
                    // leaking this loop into the next series, stealing its attributes and
                    // observations. When the current series object ends, the popped stack
                    // lands at the parent "series" map -- break, mark the series empty so
                    // moveNextObservationInternal does not consume the next series's
                    // START_OBJECT, and let moveNextKeyableInternal pick up cleanly from
                    // the END_OBJECT cursor.
                    JsonReader.JsonStackItem stackItem = jReader.getCurrentStackItem();
                    if (stackItem != null && "series".equals(stackItem.getFieldName())) {
                        currentSeriesIsEmpty = true;
                        break;
                    }
                }
            }
        }

        if (currentDsd.getGroups().isEmpty()) {
            return KeyableImpl.seriesKey(currentDataflow, currentDsd, key, attributes, annotations);
        }

        List<KeyValue> serAtt = new ArrayList<>();
        List<KeyValue> grpAttributes = new ArrayList<>();

        for (KeyValue attr : attributes) {
            AttributeBean attribute = currentDsd.getAttribute(attr.getConcept());
            if (attribute == null) {
                serAtt.add(attr);
            } else {
                ATTRIBUTE_ATTACHMENT_LEVEL attachmentLevel = attribute.getAttachmentLevel();
                if (attachmentLevel == ATTRIBUTE_ATTACHMENT_LEVEL.GROUP) {
                    grpAttributes.add(attr);
                } else {
                    serAtt.add(attr);
                }
            }
        }

        String grpName = null;
        List<KeyValue> dimensionsForGroup = new ArrayList<>();
        List<GroupBean> groups = currentDsd.getGroups();
        for (GroupBean groupBean : groups) {
            if (!groupMap.containsKey(groupBean.getId())) {
                continue;
            }
            List<String> dimensionRefs = groupBean.getDimensionRefs();

            Set<String> attSet = new HashSet<>();
            for (String aDim : groupMap.get(groupBean.getId())) {
                for (KeyValue kv : grpAttributes) {
                    if (kv.getConcept().contentEquals(aDim)) {
                        attSet.add(kv.getConcept());
                    }
                }
            }

            Set<String> mandatoryElements = groupMapMandatoryElements.get(groupBean.getId());
            if (attSet.containsAll(mandatoryElements)) {
                grpName = groupBean.getId();
                for (String ref : dimensionRefs) {
                    for (KeyValue kv : key) {
                        if (kv.getConcept().equals(ref)) {
                            dimensionsForGroup.add(kv);
                            break;
                        }
                    }
                }
                break;
            }
        }

        if (grpName != null) {
            groupAndSeriesStack.add(KeyableImpl.groupKey(currentDataflow, currentDsd, grpName, dimensionsForGroup, grpAttributes));
        }
        groupAndSeriesStack.add(KeyableImpl.seriesKey(currentDataflow, currentDsd, key, serAtt, annotations));

        return groupAndSeriesStack.remove(0);
    }

    private List<List<AttributeValue>> readMixedSeriesAttributesArray() {
        List<List<AttributeValue>> result = new ArrayList<>();
        while (jReader.moveNext() && jReader.getToken() != JsonToken.END_ARRAY) {
            if (jReader.isStartArray()) {
                result.add(readMixedAttributeArray());
            } else {
                result.add(Collections.singletonList(readMixedAttributeElement()));
            }
        }
        return result;
    }

    private AnnotationBean[] decodeAnnotations(List<Integer> encodedValues) {
        List<AnnotationBean> annList = new ArrayList<>();
        for (Integer annIdx : encodedValues) {
            if (currentDsStructuralMetadata.getAnnotationList().size() > annIdx) {
                annList.add(currentDsStructuralMetadata.getAnnotationList().get(annIdx));
            } else {
                LOG.warn("Annotation not found at index '" + annIdx + "'");
            }
        }
        return annList.toArray(new AnnotationBean[0]);
    }

    private List<KeyValue> decode(String[] encodedValues, List<AttraMapping> decodeList, String nodeType) {
        List<Integer> l = new ArrayList<>();
        for (String str : encodedValues) {
            l.add(Integer.parseInt(str));
        }
        return decode(l, decodeList, nodeType);
    }

    /**
     * Holds either an integer index (for coded attributes), multiple indices, or direct string value(s) (for uncoded).
     */
    private static class AttributeValue {
        private final Integer index;
        private final List<Integer> indices;
        private final List<String> directValues;

        private AttributeValue(Integer index, List<Integer> indices, List<String> directValues) {
            this.index = index;
            this.indices = indices;
            this.directValues = directValues;
        }

        static AttributeValue ofIndex(int idx) {
            return new AttributeValue(idx, null, null);
        }

        static AttributeValue ofIndices(List<Integer> idxs) {
            return new AttributeValue(null, idxs != null ? idxs : Collections.emptyList(), null);
        }

        static AttributeValue ofDirect(List<String> values) {
            return new AttributeValue(null, null, values != null ? values : Collections.emptyList());
        }

        static AttributeValue ofNull() {
            return new AttributeValue(null, null, null);
        }

        boolean isIndexed() {
            return index != null || (indices != null && !indices.isEmpty());
        }

        boolean isNull() {
            return index == null && (indices == null || indices.isEmpty()) && directValues == null;
        }

        Integer getIndex() {
            return index;
        }

        List<Integer> getIndices() {
            return indices;
        }

        List<String> getDirectValues() {
            return directValues;
        }
    }
}
