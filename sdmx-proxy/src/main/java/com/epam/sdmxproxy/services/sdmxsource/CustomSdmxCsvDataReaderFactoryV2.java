package com.epam.sdmxproxy.services.sdmxsource;

import io.sdmx.api.csv.DELIMITER;
import io.sdmx.api.exception.SdmxException;
import io.sdmx.api.format.FILE_FORMAT;
import io.sdmx.api.io.ReadableDataLocation;
import io.sdmx.api.sdmx.constants.DATA_TYPE;
import io.sdmx.api.sdmx.engine.DataReaderEngine;
import io.sdmx.api.sdmx.manager.retrieval.HeaderRetrievalManager;
import io.sdmx.api.sdmx.manager.structure.SdmxBeanRetrievalManager;
import io.sdmx.api.sdmx.model.beans.datastructure.DataStructureBean;
import io.sdmx.api.sdmx.model.beans.datastructure.DataflowBean;
import io.sdmx.api.sdmx.model.beans.registry.ProvisionAgreementBean;
import io.sdmx.api.sdmx.model.data.DataFormat;
import io.sdmx.api.singleton.IFusionSingleton;
import io.sdmx.core.data.model.io.CSVDataReadableDataLocation;
import io.sdmx.core.sdmx.api.error.DataValidationErrorHandler;
import io.sdmx.core.sdmx.api.factory.data.DataReaderFactory;
import io.sdmx.core.sdmx.error.DataErrorCategory;
import io.sdmx.core.sdmx.error.DataReadException;
import io.sdmx.format.csv.format.SdmxCsvDataFormat;
import io.sdmx.utils.core.application.FusionBeanStore;
import io.sdmx.utils.core.application.SingletonStore;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * Custom CSV v2 data reader factory that creates {@link CustomSdmxCsvDataReaderEngineV2} instances
 * instead of the standard SdmxCsvDataReaderEngineV2. Fixes labels=both header parsing.
 */
@Slf4j
public class CustomSdmxCsvDataReaderFactoryV2 implements DataReaderFactory, IFusionSingleton {
    private static CustomSdmxCsvDataReaderFactoryV2 INSTANCE;

    private HeaderRetrievalManager headerRetrievalManager;
    private List<DataErrorCategory> throwableErrors;

    public CustomSdmxCsvDataReaderFactoryV2() {
        SingletonStore.registerInterest(HeaderRetrievalManager.class, (instance) -> {
            this.headerRetrievalManager = instance;
        });
    }

    public static CustomSdmxCsvDataReaderFactoryV2 getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new CustomSdmxCsvDataReaderFactoryV2();
            FusionBeanStore.registerInstance(INSTANCE);
        }
        return INSTANCE;
    }

    @Override
    public void destroyInstance() {
        INSTANCE = null;
    }

    @Override
    public String getId() {
        return "CUSTOM-SDMX-CSV-V2";
    }

    @Override
    public String getName() {
        return "CUSTOM-SDMX-CSV-V2";
    }

    @Override
    public String getOverride() {
        return null;
    }

    @Override
    public List<DataErrorCategory> getThrowableErrors() {
        if (throwableErrors == null) {
            throwableErrors = new ArrayList<>();
            throwableErrors.add(new DataErrorCategory(this, DataReadException.TYPE.INCONSISTENT_DATASET_ATTR, "Inconsistent Dataset Attributes",
                    "Ensures that the Dataset Attributes reported on each line are consistent with each other."));
        }
        return throwableErrors;
    }

    @Override
    public DataReaderEngine getDataReaderEngine(DataFormat format, ReadableDataLocation sourceData, DataStructureBean dsd, DataflowBean dataflowBean, ProvisionAgreementBean provisionAgreement, DataValidationErrorHandler exceptionHandler) {
        if (tryFormat(format)) {
            DELIMITER delimiter = obtainDelimiter(sourceData, format);
            if (sourceData.getFormat() == FILE_FORMAT.CSV) {
                boolean wasDisabled = SdmxException.isDisabledExceptionTrace();
                try {
                    SdmxException.disableExceptionTrace(true);
                    CustomSdmxCsvDataReaderEngineV2 reader = new CustomSdmxCsvDataReaderEngineV2(sourceData, dsd, dataflowBean, provisionAgreement, this.headerRetrievalManager, delimiter, getDataReaderExceptionHandler(exceptionHandler));
                    log.info("Using Custom SDMX-CSV v2 Data Reader");
                    return reader;
                } catch (Exception e) {
                    return null;
                } finally {
                    SdmxException.disableExceptionTrace(wasDisabled);
                }
            }
        }
        return null;
    }

    @Override
    public DataReaderEngine getDataReaderEngine(DataFormat format, ReadableDataLocation sourceData, SdmxBeanRetrievalManager retrievalManager, DataValidationErrorHandler exceptionHandler) {
        if (tryFormat(format)) {
            DELIMITER delimiter = obtainDelimiter(sourceData, format);
            if (sourceData.getFormat() == FILE_FORMAT.CSV) {
                boolean wasDisabled = SdmxException.isDisabledExceptionTrace();
                try {
                    SdmxException.disableExceptionTrace(true);
                    CustomSdmxCsvDataReaderEngineV2 reader = new CustomSdmxCsvDataReaderEngineV2(sourceData, retrievalManager, this.headerRetrievalManager, delimiter, getDataReaderExceptionHandler(exceptionHandler));
                    log.info("Using Custom SDMX-CSV v2 Data Reader");
                    return reader;
                } catch (Exception e) {
                    return null;
                } finally {
                    SdmxException.disableExceptionTrace(wasDisabled);
                }
            }
        }
        return null;
    }

    private boolean tryFormat(DataFormat format) {
        if (format == null) {
            return true;
        }
        if (format instanceof SdmxCsvDataFormat csvFormat) {
            return csvFormat.getSdmxDataFormat() == DATA_TYPE.SDMX_CSV_2_0_0;
        }
        return false;
    }

    private DELIMITER obtainDelimiter(ReadableDataLocation sourceData, DataFormat format) {
        DELIMITER delimiter = null;
        if (sourceData instanceof CSVDataReadableDataLocation csvRdl) {
            delimiter = csvRdl.getDelimiter();
        }
        if ((delimiter == null || delimiter == DELIMITER.COMMA) && format instanceof SdmxCsvDataFormat csvFormat) {
            return csvFormat.getDelimiter();
        }
        return delimiter;
    }
}
