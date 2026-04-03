package com.epam.sdmxproxy.common.mapping;

import com.epam.jsdmx.infomodel.sdmx30.InternationalString;
import com.epam.sdmxproxy.common.utils.StreamUtils;
import io.sdmx.api.sdmx.model.beans.base.TextTypeWrapper;
import io.sdmx.api.sdmx.model.mutable.base.TextTypeWrapperMutableBean;
import io.sdmx.im.mutable.base.TextTypeWrapperMutableBeanImpl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class TextMapper {

    private static Map<String, String> toMap(List<TextTypeWrapper> texts) {
        record LocalisedString(String locale, String value) {
        }
        return StreamUtils.streamOfNullable(texts)
                .map(text -> new LocalisedString(text.getLocale(), text.getValue()))
                .collect(Collectors.toMap(LocalisedString::locale, LocalisedString::value));
    }

    public InternationalString map(List<TextTypeWrapper> texts) {
        return new InternationalString(toMap(texts));
    }

    public List<TextTypeWrapperMutableBean> map(InternationalString string) {
        if (string == null) {
            return new ArrayList<>();
        }

        return string.getAllAsStream()
                .map(entry -> new TextTypeWrapperMutableBeanImpl(entry.getKey(), entry.getValue()))
                .collect(Collectors.toList());
    }
}
