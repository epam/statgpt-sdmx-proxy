package com.epam.sdmxproxy.services.preheat;

/**
 * Marks one preheat dataflow as failed, for example when the registry answers with no structures.
 */
class PreheatDataflowException extends RuntimeException {

    PreheatDataflowException(String message) {
        super(message);
    }
}
