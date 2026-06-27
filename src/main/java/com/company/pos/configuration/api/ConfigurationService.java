package com.company.pos.configuration.api;

public interface ConfigurationService {

    String getString(SettingKey key);

    int getInt(SettingKey key);

    boolean getBoolean(SettingKey key);

    void put(SettingKey key, String value);
}
