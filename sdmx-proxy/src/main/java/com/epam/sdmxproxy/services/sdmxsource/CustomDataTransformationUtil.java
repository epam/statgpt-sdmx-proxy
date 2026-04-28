package com.epam.sdmxproxy.services.sdmxsource;

import io.sdmx.api.collection.KeyValue;
import io.sdmx.api.sdmx.engine.DataReaderEngine;
import io.sdmx.api.sdmx.model.beans.IDatasetStructures;
import io.sdmx.api.sdmx.model.data.Keyable;
import io.sdmx.api.sdmx.model.data.Observation;
import io.sdmx.api.sdmx.model.header.DatasetHeaderBean;
import io.sdmx.core.data.model.dataset.DataRow;
import io.sdmx.core.data.util.DataHeaderUtil;
import io.sdmx.core.data.util.DataTransformationUtil;
import io.sdmx.core.sdmx.api.engine.data.IFlatDataWriterEngine;
import io.sdmx.im.beans.container.DatasetStructures;
import io.sdmx.utils.core.collection.CollectionUtil;
import io.sdmx.utils.core.object.ObjectUtil;
import lombok.experimental.UtilityClass;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Local replacements for {@link DataTransformationUtil} routines
 */
@UtilityClass
public class CustomDataTransformationUtil {

    /**
     * Mirror of {@link DataTransformationUtil#copyData(DataReaderEngine, IFlatDataWriterEngine,
     * boolean, boolean, boolean)} with the measure key/value put fixed: uses
     * {@link KeyValue#getConcept()} as the row-data map key (component id, e.g. {@code "OBS_VALUE"})
     * and {@link KeyValue#getCode()} as the value (the observation value), matching the
     * convention {@code CollectionUtil.keyValuesToFlatMap} uses for dimensions and attributes.
     * The upstream version (sdmx-core 2.3.9, line 192) has these arguments swapped, so the
     * CSV writer drops every measure value silently. See
     * <a href="https://github.com/epam/statgpt-sdmx-proxy/issues/58">issue #58</a>.
     */
    public static void copyDataToFlatWriter(DataReaderEngine reader, IFlatDataWriterEngine writer) {
        reader.reset();
        writer.writeHeader(reader.getHeader());

        Map<String, String> lastDatasetAttributes = null;
        DatasetHeaderBean lastDatasetHeaderBean = null;
        IDatasetStructures structures = null;

        while (reader.moveNextDataset()) {
            DatasetHeaderBean newDatasetHeaderBean = reader.getCurrentDatasetHeaderBean();
            Map<String, String> dsAttributes = CollectionUtil.keyValuesToFlatMap(
                    reader.getDatasetAttributes().getAttributes());
            if (!ObjectUtil.equivalentMap(lastDatasetAttributes, dsAttributes)
                    || DataHeaderUtil.isHeaderEqual(lastDatasetHeaderBean, newDatasetHeaderBean)) {
                writer.startDataset(newDatasetHeaderBean);
                lastDatasetHeaderBean = newDatasetHeaderBean;
                structures = new DatasetStructures(reader.getDataStructure(), reader.getDataFlow(),
                        reader.getProvisionAgreement(), null);
            }
            lastDatasetAttributes = dsAttributes;

            boolean wasRowEverWritten = false;
            while (reader.moveNextKeyable()) {
                Keyable series = reader.getCurrentKey();
                Map<String, String> seriesData = CollectionUtil.keyValuesToFlatMap(series.getKey());
                seriesData.putAll(CollectionUtil.keyValuesToFlatMap(series.getAttributes()));
                seriesData.putAll(dsAttributes);
                boolean hasObs = false;
                while (reader.moveNextObservation()) {
                    hasObs = true;
                    Observation obs = reader.getCurrentObservation();
                    Map<String, String> rowData = new HashMap<>(seriesData);
                    rowData.putAll(CollectionUtil.keyValuesToFlatMap(obs.getAttributes()));
                    if (obs.getDimensionId() != null) {
                        rowData.put(obs.getDimensionId(), obs.getDimensionValue());
                    }
                    Map<String, Set<String>> multivalueMeasures = new HashMap<>();
                    for (KeyValue measure : obs.getMeasures()) {
                        if (measure.hasMultipleValues()) {
                            multivalueMeasures.put(measure.getConcept(), new HashSet<>(measure.getValues()));
                        } else {
                            rowData.put(measure.getConcept(), measure.getCode());
                        }
                    }
                    writer.writeRow(new DataRow(structures, rowData, multivalueMeasures, null, null));
                    wasRowEverWritten = true;
                }
                if (!hasObs) {
                    writer.writeRow(new DataRow(structures, seriesData, null, null, null));
                    wasRowEverWritten = true;
                }
            }

            if (!wasRowEverWritten && !dsAttributes.isEmpty()) {
                writer.writeRow(new DataRow(structures, dsAttributes, null, null, null));
            }
        }
        writer.close();
    }
}
