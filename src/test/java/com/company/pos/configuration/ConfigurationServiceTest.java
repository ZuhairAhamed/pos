package com.company.pos.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.configuration.api.ConfigurationService;
import com.company.pos.configuration.api.SettingKey;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("embedded")
class ConfigurationServiceTest {

    @Autowired
    ConfigurationService configuration;

    @Test
    void returnsDefaultWhenUnset() {
        assertThat(configuration.getString(SettingKey.CURRENCY_CODE)).isEqualTo("SAR");
    }

    @Test
    void storesAndRetrievesOverride() {
        configuration.put(SettingKey.STORE_NAME, "Riyadh Branch");

        assertThat(configuration.getString(SettingKey.STORE_NAME)).isEqualTo("Riyadh Branch");
    }

    @Test
    void coercesTypedValues() {
        configuration.put(SettingKey.TAX_INCLUSIVE, "true");

        assertThat(configuration.getBoolean(SettingKey.TAX_INCLUSIVE)).isTrue();
    }
}
