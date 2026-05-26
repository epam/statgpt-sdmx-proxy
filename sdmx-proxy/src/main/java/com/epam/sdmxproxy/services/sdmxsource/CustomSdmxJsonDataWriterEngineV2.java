package com.epam.sdmxproxy.services.sdmxsource;

import io.sdmx.api.exception.SdmxException;
import io.sdmx.api.sdmx.manager.structure.SdmxBeanRetrievalManager;
import io.sdmx.api.sdmx.manager.structure.SdmxSuperBeanRetrievalManager;
import io.sdmx.api.sdmx.model.beans.IDatasetStructures;
import io.sdmx.api.sdmx.model.beans.base.AnnotationBean;
import io.sdmx.api.sdmx.model.beans.datastructure.DimensionBean;
import io.sdmx.api.sdmx.model.data.DataFormat;
import io.sdmx.api.sdmx.model.data.IDatasetAttributes;
import io.sdmx.api.sdmx.model.header.DatasetHeaderBean;
import io.sdmx.api.sdmx.model.header.DatasetStructureReferenceBean;
import io.sdmx.format.json.engine.data.writer.sdmxjson.SdmxJsonDataWriterEngineV2;
import io.sdmx.utils.core.object.ObjectUtil;

import java.io.IOException;
import java.io.OutputStream;

/**
 * SDMX-JSON 2.0 data writer engine that swaps in
 * {@link CustomSdmxJsonSeriesDataWriterV2} / {@link CustomSdmxJsonFlatDataWriterV2}
 * for the stock proxy writers, so the {@code roles} / TIME_PERIOD fixes from
 * {@link SdmxJsonV2WriterOverrides} take effect.
 * <p>
 * Mirrors {@link SdmxJsonDataWriterEngineV2#startDataset} from sdmx-core 2.3.9.
 */
public class CustomSdmxJsonDataWriterEngineV2 extends SdmxJsonDataWriterEngineV2 {

    public CustomSdmxJsonDataWriterEngineV2(DataFormat df,
                                            OutputStream out,
                                            SdmxSuperBeanRetrievalManager superBeanRetrievalManager,
                                            SdmxBeanRetrievalManager beanRetrievalManager,
                                            boolean forceFlat) {
        super(df, out, superBeanRetrievalManager, beanRetrievalManager, forceFlat);
    }

    @Override
    public void startDataset(IDatasetStructures structures, DatasetHeaderBean header, IDatasetAttributes datasetAttributes, AnnotationBean... annotations) {
        if (!headerWritten) {
            writeDocumentHeader();
            headerWritten = true;
            String dimensionAtObservation = null;
            if (header != null && header.getDataStructureReference() != null) {
                dimensionAtObservation = header.getDataStructureReference().getDimensionAtObservation();
            }
            if (!ObjectUtil.validString(dimensionAtObservation)) {
                dimensionAtObservation = DimensionBean.TIME_DIMENSION_FIXED_ID;
            }
            try {
                jsonGenerator.writeObjectFieldStart("data");
                if (forceFlat || DatasetStructureReferenceBean.ALL_DIMENSIONS.equals(dimensionAtObservation)) {
                    super.setProxy(new CustomSdmxJsonFlatDataWriterV2(jsonGenerator, this.header, superBeanRetrievalManager));
                } else {
                    super.setProxy(new CustomSdmxJsonSeriesDataWriterV2(jsonGenerator, this.header, superBeanRetrievalManager));
                }
            } catch (IOException e) {
                throw new SdmxException(e, e.getMessage());
            }
        }
        getProxy().startDataset(structures, header, datasetAttributes, annotations);
    }
}
