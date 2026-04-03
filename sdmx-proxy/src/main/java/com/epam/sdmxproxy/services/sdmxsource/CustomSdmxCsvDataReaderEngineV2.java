package com.epam.sdmxproxy.services.sdmxsource;

import io.sdmx.api.csv.DELIMITER;
import io.sdmx.api.io.ReadableDataLocation;
import io.sdmx.api.sdmx.engine.DataReaderEngine;
import io.sdmx.api.sdmx.manager.retrieval.HeaderRetrievalManager;
import io.sdmx.api.sdmx.manager.structure.SdmxBeanRetrievalManager;
import io.sdmx.api.sdmx.model.beans.base.ComponentBean;
import io.sdmx.api.sdmx.model.beans.datastructure.DataStructureBean;
import io.sdmx.api.sdmx.model.beans.datastructure.DataflowBean;
import io.sdmx.api.sdmx.model.beans.registry.ProvisionAgreementBean;
import io.sdmx.core.sdmx.api.error.DataReaderExceptionHandler;
import io.sdmx.format.csv.engine.v2.SdmxCsvDataReaderEngineV2;

/**
 * Custom override of SdmxCsvDataReaderEngineV2 that fixes header column parsing for labels=both format.
 * <p>
 * When CSV uses labels=both, column headers contain "ID:Name" (e.g., "FREQ:Frequency").
 * The base implementation passes the full string to DSD component lookup, which fails because
 * the DSD only knows the ID part. This override strips the ":Name" suffix before lookup.
 */
public class CustomSdmxCsvDataReaderEngineV2 extends SdmxCsvDataReaderEngineV2 {
    private static final long serialVersionUID = 1L;

    public CustomSdmxCsvDataReaderEngineV2(ReadableDataLocation rdl, SdmxBeanRetrievalManager retrievalManager, HeaderRetrievalManager headerRetrievalManager, DataReaderExceptionHandler exceptionHandler) {
        super(rdl, retrievalManager, headerRetrievalManager, exceptionHandler);
    }

    public CustomSdmxCsvDataReaderEngineV2(ReadableDataLocation rdl, SdmxBeanRetrievalManager retrievalManager, HeaderRetrievalManager headerRetrievalManager, DELIMITER delimiter, DataReaderExceptionHandler exceptionHandler) {
        super(rdl, retrievalManager, headerRetrievalManager, delimiter, exceptionHandler);
    }

    public CustomSdmxCsvDataReaderEngineV2(ReadableDataLocation rdl, DataStructureBean dsd, DataflowBean dataflow, ProvisionAgreementBean prov, HeaderRetrievalManager headerRetrievalManager, DELIMITER delimiter, DataReaderExceptionHandler exceptionHandler) {
        super(rdl, dsd, dataflow, prov, headerRetrievalManager, delimiter, exceptionHandler);
    }

    @Override
    public DataReaderEngine createCopy() {
        if (this.beanRetrieval != null) {
            return new CustomSdmxCsvDataReaderEngineV2(dataLocation.copy(), beanRetrieval, headerRetrievalManager, exceptionHandler);
        }
        return new CustomSdmxCsvDataReaderEngineV2(dataLocation.copy(), defaultDsd, defaultDataflow, defaultProvisionAgreement, headerRetrievalManager, delimiter, exceptionHandler);
    }

    /**
     * Strips the ":Name" suffix from column headers before DSD component lookup.
     * For "FREQ:Frequency", extracts "FREQ". For "FREQ" (no label), passes through unchanged.
     * The colon stripping is applied before the parent's array notation handling ([] syntax).
     */
    @Override
    protected ComponentBean getHeaderColumnComponent(String columnId) {
        int colonIndex = columnId.indexOf(":");
        if (colonIndex > 0) {
            return super.getHeaderColumnComponent(columnId.substring(0, colonIndex));
        }
        return super.getHeaderColumnComponent(columnId);
    }
}
