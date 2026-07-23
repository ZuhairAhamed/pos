package com.company.pos.terminal.viewmodel;

/**
 * Pure display formatters for the variant builder. No JavaFX imports; no HTTP.
 * Null-safe throughout.
 */
public final class VariantRows {

    private VariantRows() {
    }

    /**
     * "sku — displayLabel", e.g. "BEER-S — Small". Null-safe: returns the bare sku if label is null.
     */
    public static String memberLabel(String sku, String displayLabel) {
        if (sku == null) {
            return "";
        }
        if (displayLabel == null || displayLabel.isBlank()) {
            return sku;
        }
        return sku + " — " + displayLabel;
    }

    /** "Active" or "Inactive". */
    public static String statusLabel(boolean active) {
        return active ? "Active" : "Inactive";
    }
}
