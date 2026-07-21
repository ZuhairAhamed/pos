package com.company.pos.terminal.viewmodel;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class SettingValidationTest {

    @Test
    void stringBlankRejected() {
        assertNotNull(SettingValidation.validate("STRING", "  "));
        assertNull(SettingValidation.validate("STRING", "x"));
    }

    @Test
    void booleanChecked() {
        assertNull(SettingValidation.validate("BOOLEAN", "TRUE"));
        assertNotNull(SettingValidation.validate("BOOLEAN", "yes"));
    }

    @Test
    void intChecked() {
        assertNull(SettingValidation.validate("INT", "7"));
        assertNotNull(SettingValidation.validate("INT", "x"));
        assertNotNull(SettingValidation.validate("INT", "-1"));
    }

    @Test
    void decimalChecked() {
        assertNull(SettingValidation.validate("DECIMAL", "0.15"));
        assertNotNull(SettingValidation.validate("DECIMAL", "abc"));
        assertNotNull(SettingValidation.validate("DECIMAL", "-1"));
    }

    @Test
    void percentChecked() {
        assertNull(SettingValidation.validate("PERCENT", "10"));
        assertNotNull(SettingValidation.validate("PERCENT", "150"));
    }

    @Test
    void csvChecked() {
        assertNull(SettingValidation.validate("CSV", "A,B"));
        assertNotNull(SettingValidation.validate("CSV", "  "));
    }

    @Test
    void unknownTypeAccepts() {
        assertNull(SettingValidation.validate("MYSTERY", "anything"));
    }
}
