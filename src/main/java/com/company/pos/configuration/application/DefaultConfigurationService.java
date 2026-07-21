package com.company.pos.configuration.application;

import com.company.pos.configuration.api.ConfigurationService;
import com.company.pos.configuration.api.SettingKey;
import com.company.pos.configuration.api.SettingView;
import com.company.pos.configuration.domain.Setting;
import com.company.pos.configuration.infrastructure.SettingRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class DefaultConfigurationService implements ConfigurationService {

    private final SettingRepository repository;

    DefaultConfigurationService(SettingRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public String getString(SettingKey key) {
        return repository.findById(key.key())
                .map(Setting::getValue)
                .orElseGet(key::defaultValue);
    }

    @Override
    @Transactional(readOnly = true)
    public int getInt(SettingKey key) {
        return Integer.parseInt(getString(key));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean getBoolean(SettingKey key) {
        return Boolean.parseBoolean(getString(key));
    }

    @Override
    public void put(SettingKey key, String value) {
        key.type().validate(value);
        Setting setting = repository.findById(key.key())
                .orElseGet(() -> new Setting(key.key(), value));
        setting.setValue(value);
        repository.save(setting);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SettingView> list() {
        return java.util.Arrays.stream(SettingKey.values())
                .map(k -> new SettingView(k.name(), k.key(), getString(k), k.defaultValue(), k.type().name()))
                .toList();
    }
}
