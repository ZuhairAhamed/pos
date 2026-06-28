package com.company.pos.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.pos.configuration.api.SettingKey;
import org.junit.jupiter.api.Test;

class SettingKeyTest {

    @Test
    void exposesCheckoutDefaults() {
        assertThat(SettingKey.VAT_RATE.key()).isEqualTo("tax.rate");
        assertThat(SettingKey.VAT_RATE.defaultValue()).isEqualTo("0.15");
        assertThat(SettingKey.STORE_ID.defaultValue()).isEqualTo("S01");
        assertThat(SettingKey.TERMINAL_ID.defaultValue()).isEqualTo("T01");
        assertThat(SettingKey.INVENTORY_LOCATION.defaultValue()).isEqualTo("MAIN");
    }
}
