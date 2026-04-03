package com.epam.sdmxproxy.common.mapping;

import com.epam.jsdmx.infomodel.sdmx30.MaintainableArtefact;
import com.epam.sdmxproxy.common.utils.StreamUtils;

import java.util.Collection;

import static java.util.stream.Collectors.toList;

public interface Mapper<T> {
    default Iterable<MaintainableArtefact> map(Collection<T> beans) {
        return StreamUtils.streamOfNullable(beans)
                .map(this::map)
                .collect(toList());
    }

    MaintainableArtefact map(T bean);
}
