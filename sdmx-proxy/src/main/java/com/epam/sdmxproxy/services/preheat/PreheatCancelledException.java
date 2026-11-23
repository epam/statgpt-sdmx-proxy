package com.epam.sdmxproxy.services.preheat;

/**
 * Stops a preheat run: the configuration changed, or the preheat thread was interrupted.
 */
class PreheatCancelledException extends RuntimeException {

    PreheatCancelledException(String message) {
        super(message);
    }
}
