package com.company.pos.configuration.api;

public enum SettingKey {
    STORE_NAME("store.name", "My Store"),
    CURRENCY_CODE("currency.code", "SAR"),
    LOCALE("locale", "en"),
    TAX_INCLUSIVE("tax.inclusive", "false"),
    RECEIPT_PRINTER_PORT("printer.port", "COM1");

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
