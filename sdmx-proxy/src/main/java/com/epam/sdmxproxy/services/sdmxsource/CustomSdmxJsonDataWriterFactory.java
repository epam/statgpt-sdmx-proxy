package com.epam.sdmxproxy.services.sdmxsource;

import io.sdmx.api.exception.SdmxNotImplementedException;
import io.sdmx.api.sdmx.constants.DATA_TYPE;
import io.sdmx.api.sdmx.engine.ISeriesObsDataWriterEngine;
import io.sdmx.api.sdmx.manager.structure.SdmxBeanRetrievalManager;
import io.sdmx.api.sdmx.manager.structure.SdmxSuperBeanRetrievalManager;
import io.sdmx.api.sdmx.model.beans.datastructure.DimensionBean;
import io.sdmx.api.sdmx.model.data.DataFormat;
import io.sdmx.api.sdmx.model.data.query.DataQuery;
import io.sdmx.api.sdmx.model.header.DatasetStructureReferenceBean;
import io.sdmx.core.data.engine.writer.GroupDataWriterEngine;
import io.sdmx.core.data.factory.format.MetadataAwareDataWriterFactory;
import io.sdmx.format.json.engine.data.writer.sdmxjson.SdmxJsonDataWriterEngine;
import io.sdmx.format.json.model.SdmxJsonDataFormat;
import io.sdmx.utils.core.object.ObjectUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.OutputStream;

@Slf4j
@RequiredArgsConstructor
public class CustomSdmxJsonDataWriterFactory extends MetadataAwareDataWriterFactory {
    private final SdmxSuperBeanRetrievalManager superBeanRetrievalManager;
    private final SdmxBeanRetrievalManager beanRetrievalManager;

    @Override
    public ISeriesObsDataWriterEngine getDataWriterEngine(DataFormat dataFormat, OutputStream out, DataQuery dataQuery) {
        if (dataFormat instanceof SdmxJsonDataFormat) {
            String dimAtObs = null;
            SdmxJsonDataFormat jsonFormat = (SdmxJsonDataFormat) dataFormat;

            if (dataQuery != null) {
                dimAtObs = dataQuery.dimensionAtObservation();
            } else if (jsonFormat.getRestDataQuery() != null) {
                dimAtObs = jsonFormat.getRestDataQuery().getStringParamValue("dimensionAtObservation");
            }
            if (ObjectUtil.validString(dimAtObs)) {
                if (!(dimAtObs.equalsIgnoreCase(DatasetStructureReferenceBean.ALL_DIMENSIONS) ||
                        dimAtObs.equalsIgnoreCase(DimensionBean.TIME_DIMENSION_FIXED_ID))) {
                    throw new SdmxNotImplementedException("The dimension at observation '" + dimAtObs + "' is not supported. Only the values '" + DatasetStructureReferenceBean.ALL_DIMENSIONS + "' and '" + DimensionBean.TIME_DIMENSION_FIXED_ID + "' are supported");
                }
            } else {
                dimAtObs = DimensionBean.TIME_DIMENSION_FIXED_ID;
            }

            boolean forceFlat = DatasetStructureReferenceBean.ALL_DIMENSIONS.equalsIgnoreCase(dimAtObs);

            DATA_TYPE format = ((SdmxJsonDataFormat) dataFormat).getSdmxDataFormat();
            if (format == DATA_TYPE.SDMXJSON_1_0_0) {
                // JSON 1.0 has no dimensionGroupAttributes concept; the GroupDataWriterEngine
                // wrapper folds group keyables into series, which is the spec-correct shape.
                ISeriesObsDataWriterEngine dwe = new SdmxJsonDataWriterEngine(dataFormat, out, superBeanRetrievalManager, beanRetrievalManager, forceFlat);
                return new GroupDataWriterEngine(dwe);
            }
            if (format == DATA_TYPE.SDMXJSON_2_0_0) {
                // Issue #83 / design 030: the custom SDMX-JSON 2.0 writer emits group
                // keyables natively under data.dataSets[*].dimensionGroupAttributes
                // and splits the structure sidecar into four buckets (dataset /
                // dimensionGroup / series / observation). Wrapping it in
                // GroupDataWriterEngine would re-fold groups into series and erase
                // the buffered group keyables, so the wrapper is omitted here.
                return new CustomSdmxJsonDataWriterEngineV2(dataFormat, out, superBeanRetrievalManager, beanRetrievalManager, forceFlat);
            }
        }
        return null;
    }
}