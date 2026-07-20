package com.company.pos.terminal.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class StationPickerDialogTest {

    @Test
    void normalizeTrims() {
        assertEquals("Grill", StationPickerDialog.normalize("  Grill "));
    }

    @Test
    void normalizeBlankIsNull() {
        assertNull(StationPickerDialog.normalize("   "));
    }

    @Test
    void normalizeNullIsNull() {
        assertNull(StationPickerDialog.normalize(null));
    }
}
