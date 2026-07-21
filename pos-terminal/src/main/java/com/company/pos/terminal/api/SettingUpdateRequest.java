package com.company.pos.terminal.api;

/** Outbound body for PUT /config/{name}. */
public record SettingUpdateRequest(String value) {
}
