package com.pms.domain;

public enum Unit {
    KG("kg"),
    G("g"),
    L("l"),
    ML("ml");

    private final String value;

    Unit(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static Unit fromValue(String value) {
        for (Unit unit : Unit.values()) {
            if (unit.value.equalsIgnoreCase(value)) {
                return unit;
            }
        }
        throw new IllegalArgumentException("Invalid unit: " + value);
    }
}
