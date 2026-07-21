package com.company.pos.configuration.api;

/** A read projection of one setting. {@code name} is the enum constant (the {@code PUT /config/{name}}
 *  path segment); {@code type} is {@link SettingType#name()}. */
public record SettingView(String name, String key, String value, String defaultValue, String type) {
}
