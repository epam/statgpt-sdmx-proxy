package com.epam.sdmxproxy.common.data;

public enum DataComponentFilterOperator {

    /**
     * Default if no operator is specified and there is only one value (e.g. c[FREQ]=M is equivalent to c[FREQ]=eq:M)
     */
    eq("Equals"),
    ne("Not equal to"),
    lt("Less than"),
    le("Less than or equal to"),
    gt("Greater than"),
    ge("Greater than or equal to"),
    co("Contains"),
    nc("Does not contain"),
    sw("Starts with"),
    ew("Ends with");

    private final String name;

    DataComponentFilterOperator(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
}
