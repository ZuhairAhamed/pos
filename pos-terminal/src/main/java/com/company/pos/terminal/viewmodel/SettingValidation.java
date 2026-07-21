package com.company.pos.terminal.viewmodel;

import java.math.BigDecimal;
import java.util.Arrays;

/** Client-side mirror of the server's SettingType validation. Returns a friendly message, or null
 *  when the value is acceptable. The server re-validates authoritatively. */
public final class SettingValidation {

    private SettingValidation() {
    }

    public static String validate(String type, String value) {
        if (type == null) {
            return null;
        }
        switch (type) {
            case "STRING":
                return (value == null || value.isBlank()) ? "Value is required" : null;
            case "BOOLEAN":
                return ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value))
                        ? null : "Must be true or false";
            case "INT": {
                int n;
                try {
                    n = Integer.parseInt(value == null ? "" : value.trim());
                } catch (NumberFormatException e) {
                    return "Must be a whole number";
                }
                return n < 0 ? "Must be zero or greater" : null;
            }
            case "DECIMAL": {
                BigDecimal d = parse(value);
                if (d == null) {
                    return "Must be a number";
                }
                return d.signum() < 0 ? "Must be zero or greater" : null;
            }
            case "PERCENT": {
                BigDecimal p = parse(value);
                if (p == null) {
                    return "Must be a number";
                }
                return (p.signum() < 0 || p.compareTo(new BigDecimal("100")) > 0)
                        ? "Must be a percentage between 0 and 100" : null;
            }
            case "CSV": {
                boolean hasToken = value != null && value.lines()
                        .flatMap(l -> Arrays.stream(l.split(",")))
                        .anyMatch(t -> !t.isBlank());
                return hasToken ? null : "Enter at least one value";
            }
            default:
                return null;
        }
    }

    private static BigDecimal parse(String value) {
        try {
            return new BigDecimal(value == null ? "" : value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
