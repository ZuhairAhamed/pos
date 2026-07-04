package com.company.pos.configuration.api;

public enum SettingKey {
    STORE_NAME("store.name", "My Store"),
    CURRENCY_CODE("currency.code", "SAR"),
    LOCALE("locale", "en"),
    TAX_INCLUSIVE("tax.inclusive", "false"),
    RECEIPT_PRINTER_PORT("printer.port", "COM1"),
    VAT_RATE("tax.rate", "0.15"),
    STORE_ID("store.id", "S01"),
    TERMINAL_ID("terminal.id", "T01"),
    INVENTORY_LOCATION("inventory.location", "MAIN"),
    DISCOUNT_REASON_CODES("discount.reason.codes", "DAMAGED,PRICE_MATCH,LOYALTY,MANAGER_COMP"),
    DISCOUNT_CASHIER_MAX_PERCENT("discount.cashier.max.percent", "10"),
    DISCOUNT_CASHIER_MAX_AMOUNT("discount.cashier.max.amount", "20.00"),
    DASHBOARD_REVENUE_WINDOW_DAYS("dashboard.revenue.window.days", "7"),
    DINING_TABLE_DEFAULT_SEATS("dining.table.default.seats", "4");

    private final String key;
    private final String defaultValue;

    SettingKey(String key, String defaultValue) {
        this.key = key;
        this.defaultValue = defaultValue;
    }

    public String key() {
        return key;
    }

    public String defaultValue() {
        return defaultValue;
    }
}
