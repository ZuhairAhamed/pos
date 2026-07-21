package com.company.pos.configuration.api;

public enum SettingKey {
    STORE_NAME("store.name", "My Store", SettingType.STRING),
    CURRENCY_CODE("currency.code", "SAR", SettingType.STRING),
    LOCALE("locale", "en", SettingType.STRING),
    TAX_INCLUSIVE("tax.inclusive", "false", SettingType.BOOLEAN),
    RECEIPT_PRINTER_PORT("printer.port", "COM1", SettingType.STRING),
    VAT_RATE("tax.rate", "0.15", SettingType.DECIMAL),
    STORE_ID("store.id", "S01", SettingType.STRING),
    TERMINAL_ID("terminal.id", "T01", SettingType.STRING),
    INVENTORY_LOCATION("inventory.location", "MAIN", SettingType.STRING),
    DISCOUNT_REASON_CODES("discount.reason.codes", "DAMAGED,PRICE_MATCH,LOYALTY,MANAGER_COMP", SettingType.CSV),
    DISCOUNT_CASHIER_MAX_PERCENT("discount.cashier.max.percent", "10", SettingType.PERCENT),
    DISCOUNT_CASHIER_MAX_AMOUNT("discount.cashier.max.amount", "20.00", SettingType.DECIMAL),
    DASHBOARD_REVENUE_WINDOW_DAYS("dashboard.revenue.window.days", "7", SettingType.INT),
    DINING_TABLE_DEFAULT_SEATS("dining.table.default.seats", "4", SettingType.INT),
    KITCHEN_DEFAULT_STATION("kitchen.default.station", "Kitchen", SettingType.STRING),
    SERVICE_CHARGE_ENABLED("service.charge.enabled", "false", SettingType.BOOLEAN),
    SERVICE_CHARGE_PERCENT("service.charge.percent", "0", SettingType.PERCENT),
    SERVICE_CHARGE_LABEL("service.charge.label", "Service Charge", SettingType.STRING);

    private final String key;
    private final String defaultValue;
    private final SettingType type;

    SettingKey(String key, String defaultValue, SettingType type) {
        this.key = key;
        this.defaultValue = defaultValue;
        this.type = type;
    }

    public String key() {
        return key;
    }

    public String defaultValue() {
        return defaultValue;
    }

    public SettingType type() {
        return type;
    }
}
