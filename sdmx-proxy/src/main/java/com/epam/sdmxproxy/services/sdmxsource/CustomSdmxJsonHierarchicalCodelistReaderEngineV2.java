package com.epam.sdmxproxy.services.sdmxsource;

import io.sdmx.api.sdmx.model.beans.base.MaintainableBean;
import io.sdmx.api.sdmx.model.beans.codelist.HierarchyBean;
import io.sdmx.api.sdmx.model.mutable.base.HierarchyMutableBean;
import io.sdmx.api.sdmx.model.mutable.base.TextFormatMutableBean;
import io.sdmx.api.sdmx.model.mutable.codelist.LevelMutableBean;
import io.sdmx.api.sdmx.model.mutable.reference.CodeRefMutableBean;
import io.sdmx.api.singleton.IFusionSingleton;
import io.sdmx.format.json.engine.structure.reader.sdmx.AbstractSdmxJsonReaderEngine;
import io.sdmx.format.json.engine.structure.reader.util.SdmxJsonIdentifiableUtil;
import io.sdmx.format.json.engine.structure.reader.util.SdmxJsonNameableUtil;
import io.sdmx.format.json.engine.structure.reader.util.SdmxJsonRepresentationUtil;
import io.sdmx.im.mutable.codelist.HierarchyMutableBeanImpl;
import io.sdmx.im.mutable.codelist.LevelMutableBeanImpl;
import io.sdmx.im.mutable.reference.CodeRefMutableBeanImpl;
import io.sdmx.utils.core.application.FusionBeanStore;
import io.sdmx.utils.core.date.SdmxDateImpl;
import io.sdmx.utils.json.JsonReader;

import java.util.ArrayList;
import java.util.List;

/**
 * Custom override of {@link io.sdmx.format.json.engine.structure.reader.sdmx.v2.SdmxJsonHierarchicalCodelistReaderEngineV2}
 * that fixes the parsing of the "level" field within hierarchicalCodes.
 *
 * <p>Per the SDMX-JSON 2.0.0 spec (HierarchicalCodeType), the "level" field is an idType
 * (plain string ID like "0", "L1") — NOT a URN reference. The upstream sdmx-core reader
 * incorrectly attempts to parse it as a URN via StructureReferenceBeanImpl.buildAndVerify(),
 * which crashes on plain IDs like "0" with: URN '0' is not well formed, missing '='.
 *
 * <p>This override treats "level" as a plain ID and sets it directly via setLevelReference().
 */
public class CustomSdmxJsonHierarchicalCodelistReaderEngineV2 extends AbstractSdmxJsonReaderEngine<HierarchyMutableBean> implements IFusionSingleton {

    private static CustomSdmxJsonHierarchicalCodelistReaderEngineV2 INSTANCE;

    public static CustomSdmxJsonHierarchicalCodelistReaderEngineV2 getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new CustomSdmxJsonHierarchicalCodelistReaderEngineV2();
            FusionBeanStore.registerInstance(INSTANCE);
        }
        return INSTANCE;
    }

    @Override
    public void destroyInstance() {
        INSTANCE = null;
    }

    @Override
    public String getContainerElement() {
        return "hierarchies";
    }

    @Override
    protected Class<? extends MaintainableBean> getMaintainableType() {
        return HierarchyBean.class;
    }

    @Override
    protected HierarchyMutableBean getMutable() {
        return new HierarchyMutableBeanImpl();
    }

    @Override
    protected boolean processMaintainableProperty(String fieldName, HierarchyMutableBean mutable, JsonReader reader) {
        switch (fieldName) {
            case "hasFormalLevels":
                boolean levelled = reader.getValueAsBoolean();
                mutable.setFormalLevels(levelled);
                return true;
            case "hierarchicalCodes":
                List<CodeRefMutableBean> hRefBeans = readHrefBeans(reader);
                mutable.setHierarchicalCodeBeans(hRefBeans);
                return true;
            case "level":
                LevelMutableBean level = readLevel(reader);
                mutable.setChildLevel(level);
                return true;
        }
        return false;
    }

    private List<CodeRefMutableBean> readHrefBeans(JsonReader jReader) {
        List<CodeRefMutableBean> retList = new ArrayList<>();
        while (jReader.moveNext()) {
            if (jReader.isEndArray()) {
                break;
            }
            CodeRefMutableBean hRefBean = new CodeRefMutableBeanImpl();
            while (jReader.moveNext()) {
                if (jReader.isEndObject()) {
                    break;
                }
                String value = jReader.getValueAsString();
                if (SdmxJsonIdentifiableUtil.processBean(hRefBean, jReader)) {
                    continue;
                }
                String fieldName = jReader.getCurrentFieldName();
                switch (fieldName) {
                    case "validFrom":
                        hRefBean.setStartDate(SdmxDateImpl.getSdmxDate(value, true));
                        break;
                    case "validTo":
                        hRefBean.setEndDate(SdmxDateImpl.getSdmxDate(value, false));
                        break;
                    case "code":
                        hRefBean.setCodeReference(new io.sdmx.utils.sdmx.xs.StructureReferenceBeanImpl(value));
                        break;
                    case "hierarchicalCodes":
                        hRefBean.setCodeRefs(readHrefBeans(jReader));
                        break;
                    case "level":
                        // Per SDMX-JSON 2.0.0 spec, "level" is an idType (plain string ID), not a URN.
                        // The upstream reader incorrectly parses this as a URN reference.
                        hRefBean.setLevelReference(value);
                        break;
                }
            }
            retList.add(hRefBean);
        }
        return retList;
    }

    private LevelMutableBean readLevel(JsonReader jReader) {
        LevelMutableBean ret = new LevelMutableBeanImpl();
        while (jReader.moveNext()) {
            if (jReader.isEndObject()) {
                break;
            }
            if (SdmxJsonNameableUtil.processBean(ret, jReader)) {
                continue;
            }
            String fieldName = jReader.getCurrentFieldName();
            switch (fieldName) {
                case "codingFormat":
                    TextFormatMutableBean readTextFormat = SdmxJsonRepresentationUtil.readTextFormat(jReader, true);
                    ret.setCodingFormat(readTextFormat);
                    break;
                case "level":
                    LevelMutableBean readLevel = readLevel(jReader);
                    ret.setChildLevel(readLevel);
                    break;
            }
        }
        return ret;
    }
}
