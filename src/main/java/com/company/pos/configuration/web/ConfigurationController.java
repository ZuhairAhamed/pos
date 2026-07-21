package com.company.pos.configuration.web;

import com.company.pos.common.events.DomainEvents;
import com.company.pos.common.exception.DomainException;
import com.company.pos.configuration.api.ConfigurationService;
import com.company.pos.configuration.api.SettingChanged;
import com.company.pos.configuration.api.SettingKey;
import com.company.pos.configuration.api.SettingView;
import java.security.Principal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ConfigurationController {

    private final ConfigurationService config;
    private final DomainEvents events;

    ConfigurationController(ConfigurationService config, DomainEvents events) {
        this.config = config;
        this.events = events;
    }

    @GetMapping("/config")
    @PreAuthorize("hasRole('ADMIN')")
    List<SettingView> list() {
        return config.list();
    }

    @PutMapping("/config/{key}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    void update(@PathVariable String key, @RequestBody UpdateRequest request, Principal principal) {
        SettingKey settingKey = resolve(key);
        String oldValue = config.getString(settingKey);
        config.put(settingKey, request.value());
        events.publish(new SettingChanged(settingKey.key(), oldValue, request.value(),
                principal.getName()));
    }

    private SettingKey resolve(String key) {
        try {
            return SettingKey.valueOf(key);
        } catch (IllegalArgumentException ex) {
            throw DomainException.validation("Unknown setting key " + key);
        }
    }

    record UpdateRequest(String value) {
    }
}
