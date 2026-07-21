package com.company.pos.configuration.api;

import com.company.pos.common.exception.DomainException;
import java.math.BigDecimal;

/** The value type of a {@link SettingKey}, used to render the right editor and to validate writes. */
public enum SettingType {
    STRING, BOOLEAN, INT, DECIMAL, PERCENT, CSV;

    /** Rejects a value that does not fit this type. Callers pass the raw string a client submitted. */
    public void validate(String value) {
        switch (this) {
            case STRING -> {
                if (value == null || value.isBlank()) {
                    throw DomainException.validation("Value is required");
                }
            }
            case BOOLEAN -> {
                if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
                    throw DomainException.validation("Must be true or false");
                }
            }
            case INT -> {
                int n;
                try {
                    n = Integer.parseInt(value == null ? "" : value.trim());
                } catch (NumberFormatException e) {
                    throw DomainException.validation("Must be a whole number");
                }
                if (n < 0) {
                    throw DomainException.validation("Must be zero or greater");
                }
            }
            case DECIMAL -> {
                BigDecimal d = parseDecimal(value);
                if (d.signum() < 0) {
                    throw DomainException.validation("Must be zero or greater");
                }
            }
            case PERCENT -> {
                BigDecimal p = parseDecimal(value);
                if (p.signum() < 0 || p.compareTo(new BigDecimal("100")) > 0) {
                    throw DomainException.validation("Must be a percentage between 0 and 100");
                }
            }
            case CSV -> {
                boolean hasToken = value != null && value.lines()
                        .flatMap(l -> java.util.Arrays.stream(l.split(",")))
                        .anyMatch(t -> !t.isBlank());
                if (!hasToken) {
                    throw DomainException.validation("Enter at least one value");
                }
            }
        }
    }

    private static BigDecimal parseDecimal(String value) {
        try {
            return new BigDecimal(value == null ? "" : value.trim());
        } catch (NumberFormatException e) {
            throw DomainException.validation("Must be a number");
        }
    }
}
