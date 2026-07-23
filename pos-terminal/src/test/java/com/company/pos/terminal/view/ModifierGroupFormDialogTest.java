package com.company.pos.terminal.view;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class ModifierGroupFormDialogTest {

    @Test
    void validAccepted() {
        assertNull(ModifierGroupFormDialog.validate("Add-ons", 0, 2));
        assertNull(ModifierGroupFormDialog.validate("Forced", 1, 1));
    }

    @Test
    void blankNameRejected() {
        assertNotNull(ModifierGroupFormDialog.validate("  ", 0, 2));
    }

    @Test
    void badSelectionsRejected() {
        assertNotNull(ModifierGroupFormDialog.validate("X", 3, 1));   // min > max
        assertNotNull(ModifierGroupFormDialog.validate("X", 0, 0));   // max < 1
    }
}
