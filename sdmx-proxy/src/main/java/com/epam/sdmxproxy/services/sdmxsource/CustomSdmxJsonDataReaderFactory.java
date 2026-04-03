package com.epam.sdmxproxy.services.sdmxsource;

import io.sdmx.api.io.ReadableDataLocation;
import io.sdmx.api.sdmx.engine.DataReaderEngine;
import io.sdmx.api.sdmx.manager.structure.SdmxBeanRetrievalManager;
import io.sdmx.api.sdmx.model.beans.datastructure.DataStructureBean;
import io.sdmx.api.sdmx.model.beans.datastructure.DataflowBean;
import io.sdmx.api.sdmx.model.beans.registry.ProvisionAgreementBean;
import io.sdmx.api.sdmx.model.data.DataFormat;
import io.sdmx.api.singleton.IFusionSingleton;
import io.sdmx.core.sdmx.api.error.DataReaderExceptionHandler;
import io.sdmx.core.sdmx.api.error.DataValidationErrorHandler;
import io.sdmx.core.sdmx.api.factory.data.DataReaderFactory;
import io.sdmx.core.sdmx.error.DataErrorCategory;
import io.sdmx.core.sdmx.error.DataReadException;
import io.sdmx.format.json.engine.data.reader.SdmxJsonDataReaderEngine;
import io.sdmx.format.json.model.SdmxJsonDataFormat;
import io.sdmx.utils.core.application.FusionBeanStore;

import java.util.ArrayList;
import java.util.List;

public class CustomSdmxJsonDataReaderFactory implements DataReaderFactory, IFusionSingleton {
    private static CustomSdmxJsonDataReaderFactory INSTANCE;
    private List<DataErrorCategory> throwableErrors;

    private CustomSdmxJsonDataReaderFactory() {
    }

    //Private constructor - lazy load instance
    public static CustomSdmxJsonDataReaderFactory getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new CustomSdmxJsonDataReaderFactory();
            FusionBeanStore.registerInstance(INSTANCE);
        }
        return INSTANCE;
    }

    public static CustomSdmxJsonDataReaderFactory registerInstance() {
        FusionBeanStore.registerInstance(getInstance());
        return getInstance();
    }

    @Override
    public void destroyInstance() {
        INSTANCE = null;
    }

    @Override
    public DataReaderEngine getDataReaderEngine(DataFormat dataFormat,
                                                ReadableDataLocation sourceData,
                                                DataStructureBean dsd,
                                                DataflowBean dataflowBean,
                                                ProvisionAgreementBean provisionAgreement,
                                                DataValidationErrorHandler exceptionHandler) {
        return getDre(dataFormat, sourceData, dsd, dataflowBean, provisionAgreement, exceptionHandler, null);
    }

    private DataReaderEngine getDre(DataFormat dataFormat, ReadableDataLocation sourceData, DataStructureBean dsd, DataflowBean dataflowBean,
                                    ProvisionAgreementBean provisionAgreement, DataValidationErrorHandler exceptionHandler,
                                    SdmxBeanRetrievalManager retrievalManager) {
        if (dataFormat instanceof SdmxJsonDataFormat) {
            DataReaderExceptionHandler readerEx = getDataReaderExceptionHandler(exceptionHandler);

            String format = dataFormat.getFormatDetails().getFormatAsString();
            if (format.equals("SDMX-JSON-V1")) {
                return new SdmxJsonDataReaderEngine(sourceData, retrievalManager, dsd, dataflowBean, provisionAgreement, readerEx);
            } else if (format.equals("SDMX-JSON-V2")) {
                return new CustomSdmxJsonDataReaderEngineV2(sourceData, retrievalManager, dsd, dataflowBean, provisionAgreement, readerEx);
            }
        }
        return null;
    }

    @Override
    public DataReaderEngine getDataReaderEngine(DataFormat dataFormat,
                                                ReadableDataLocation sourceData,
                                                SdmxBeanRetrievalManager retrievalManager,
                                                DataValidationErrorHandler exceptionHandler) {
        return getDre(dataFormat, sourceData, null, null, null, exceptionHandler, retrievalManager);
    }

    @Override
    public String getOverride() {
        return null;
    }

    @Override
    public List<DataErrorCategory> getThrowableErrors() {
        if (throwableErrors == null) {
            throwableErrors = new ArrayList<>();
            throwableErrors.add(new DataErrorCategory(this, DataReadException.TYPE.JSON_ATTRIBUTES_INDEX_OUT_OF_BOUNDS, "Attributes index out of bounds", "Reported when the JSON Dataset contains a pointer to a list item which does not exist"));
        }
        return throwableErrors;
    }

    @Override
    public String getId() {
        return "SDMX-JSON";
    }

    @Override
    public String getName() {
        return "SDMX-JSON";
    }
}
