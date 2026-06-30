package com.company.pos.configuration.api;

import com.company.pos.common.events.DomainEvent;

/** A runtime setting was changed through the admin endpoint (not bootstrap/env seeding). */
public record SettingChanged(String key, String oldValue, String newValue, String actor)
        implements DomainEvent {
}
