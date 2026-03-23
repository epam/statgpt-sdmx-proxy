package com.epam.sdmxproxy.services.filter;

public class FilterValidationResult {
    private final boolean valid;
    private final String errorMessage;

    public FilterValidationResult(boolean valid, String errorMessage) {
        this.valid = valid;
        this.errorMessage = errorMessage;
    }

    public static FilterValidationResult valid() {
        return new FilterValidationResult(true, null);
    }

    public static FilterValidationResult invalid(String errorMessage) {
        return new FilterValidationResult(false, errorMessage);
    }

    public boolean isValid() {
        return valid;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}
