package com.epam.sdmxproxy.exception;

/**
 * Base class for every exception originating in the SDMX Proxy's own code.
 *
 * <p>Status is encoded by the family subclass ({@link BadRequestException},
 * {@link ServerErrorException}, etc.), not by a field on the base. The handler
 * dispatches on the family type and never has to introspect a status code.
 *
 * <p>Subclasses MUST expose at least {@code (String message)} and
 * {@code (String message, Throwable cause)} constructors so that wrap-and-rethrow sites
 * always have a way to preserve the cause.
 */
public abstract class BaseException extends RuntimeException {

    protected BaseException(String message) {
        super(message);
    }

    protected BaseException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * The message the handler will echo to the client. Defaults to {@link #getMessage()}.
     *
     * <p>5xx-family subclasses override this to return a generic, non-leaking phrase while
     * {@link #getMessage()} retains the original detail for logs.
     */
    public String getClientMessage() {
        return getMessage();
    }
}
