package com.company.pos.terminal.api;

/** The five report endpoints, each carrying its /reports/<path> segment. */
public enum ReportType {
    SALES("sales"), PAYMENTS("payments"), TAX("tax"), CASHIERS("cashiers"), PRODUCTS("products");

    private final String path;

    ReportType(String path) {
        this.path = path;
    }

    public String path() {
        return path;
    }
}
