package com.company.pos.terminal.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class TableFormDialogTest {

    @Test
    void counterPrependsPrefixOnce() {
        assertEquals("Counter 5", TableFormDialog.normalizeLabel("5", true, "Counter "));
        assertEquals("Counter 5", TableFormDialog.normalizeLabel("Counter 5", true, "Counter "));
    }

    @Test
    void dineInStripsPrefix() {
        assertEquals("5", TableFormDialog.normalizeLabel("Counter 5", false, "Counter "));
        assertEquals("T3", TableFormDialog.normalizeLabel(" T3 ", false, "Counter "));
    }

    @Test
    void blankIsNull() {
        assertNull(TableFormDialog.normalizeLabel("   ", true, "Counter "));
        assertNull(TableFormDialog.normalizeLabel(null, false, "Counter "));
    }
}
