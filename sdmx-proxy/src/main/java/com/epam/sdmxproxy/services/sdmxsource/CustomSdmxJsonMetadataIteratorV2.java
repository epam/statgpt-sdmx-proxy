package com.epam.sdmxproxy.services.sdmxsource;

import io.sdmx.core.sdmx.api.error.DataReaderExceptionHandler;
import io.sdmx.format.json.engine.data.reader.AbstractIterator;
import io.sdmx.format.json.engine.data.reader.HeaderIterator;
import io.sdmx.utils.json.JsonReader;

import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

public class CustomSdmxJsonMetadataIteratorV2 extends AbstractIterator {

    private HeaderIterator headerIterator;
    private List<CustomSdmxStructureIterator> structureIterators;

    public CustomSdmxJsonMetadataIteratorV2(JsonReader jReader, DataReaderExceptionHandler exceptionHandler) {
        super(jReader, exceptionHandler);
    }

    @Override
    public JsonReader.Iterator start(String fieldName, boolean isObject) {
        if ("meta".equals(fieldName)) {
            headerIterator = new HeaderIterator(jReader, this.exceptionHandler);
            return headerIterator;

        } else if ("structures".equals(fieldName)) {
            // sdmxJson v2 allows multiple structure objects
            // iterate and store these while inside the structures array

            boolean stackEmpty = false;
            structureIterators = new ArrayList<>();
            Stack<JsonReader.Iterator> iteratorStack = new Stack<>();
            JsonReader.Iterator it = newStructureIterator();

            while (jReader.moveNext()) {
                JsonReader.JsonStackItem currentStackItem = jReader.getCurrentStackItem();

                if (jReader.isEndObject() || jReader.isEndArray()) {
                    fieldName = currentStackItem != null ? currentStackItem.getFieldName() : null;
                    if (stackEmpty && fieldName == null) {
                        // there are no more structure objects
                        break;
                    }
                    it.end(fieldName, jReader.isEndObject());

                    if (it instanceof CustomSdmxStructureIterator && !structureIterators.contains(it)) {
                        // add unique SdmxStructureIterator
                        structureIterators.add((CustomSdmxStructureIterator) it);
                    }
                    if (iteratorStack.isEmpty()) {
                        // but there might be more structure objects
                        stackEmpty = true;
                        it = newStructureIterator();
                        continue;
                    }
                    it = iteratorStack.pop();

                } else if (jReader.isStartObject() || jReader.isStartArray()) {
                    // reset stackEmpty as there are more structure objects
                    stackEmpty = false;
                    fieldName = currentStackItem != null ? currentStackItem.getFieldName() : null;
                    JsonReader.Iterator subObjectIt = it.start(fieldName, jReader.isStartObject());

                    // store the old iterator
                    iteratorStack.push(it);
                    if (subObjectIt != null) {
                        it = subObjectIt;
                    }
                } else {
                    it.next(jReader.getCurrentFieldName());
                }
            }

        } else if ("dataSets".equals(fieldName)) {
            if (structureIterators != null && !structureIterators.isEmpty()) {
                // stop iterating as all information to read the dataset has been gathered
                jReader.stopIterating();
            }
            return null;
        }
        return null;
    }

    private JsonReader.Iterator newStructureIterator() {
        // move as StructureIterator expects reader to be inside object
        jReader.moveNext();
        return new CustomSdmxStructureIterator(jReader, this.exceptionHandler);
    }

    public HeaderIterator getHeaderIterator() {
        return headerIterator;
    }

    public List<CustomSdmxStructureIterator> getStructureIterators() {
        return structureIterators;
    }
}
