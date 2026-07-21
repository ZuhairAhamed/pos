package com.company.pos.configuration.api;

import java.util.List;

public interface ConfigurationService {

    String getString(SettingKey key);

    int getInt(SettingKey key);

    boolean getBoolean(SettingKey key);

    void put(SettingKey key, String value);

    List<SettingView> list();
}
