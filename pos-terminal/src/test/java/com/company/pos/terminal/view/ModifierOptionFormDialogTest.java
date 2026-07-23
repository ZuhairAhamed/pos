package com.company.pos.terminal.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ModifierOptionFormDialogTest {

    @Test
    void parseDeltaHandlesSignAndBlank() {
        assertEquals(new BigDecimal("2.50"), ModifierOptionFormDialog.parseDelta(" 2.50 "));
        assertEquals(new BigDecimal("-1"), ModifierOptionFormDialog.parseDelta("-1"));
        assertNull(ModifierOptionFormDialog.parseDelta("  "));
        assertNull(ModifierOptionFormDialog.parseDelta("abc"));
    }

    @Test
    void validateChecksNameAndDelta() {
        assertNull(ModifierOptionFormDialog.validate("Cheese", "2.50"));
        assertNull(ModifierOptionFormDialog.validate("No ice", "-1.00"));
        assertNull(ModifierOptionFormDialog.validate("Free", "0"));
        assertNotNull(ModifierOptionFormDialog.validate(" ", "2.50"));
        assertNotNull(ModifierOptionFormDialog.validate("Cheese", "abc"));
    }
}
