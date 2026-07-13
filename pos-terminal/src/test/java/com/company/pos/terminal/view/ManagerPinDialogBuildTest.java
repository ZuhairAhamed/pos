package com.company.pos.terminal.view;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ManagerPinDialogBuildTest {

    @Test
    void bothFieldsRequiredAndTrimmed() {
        assertNull(ManagerPinDialog.build(null, "1234"));
        assertNull(ManagerPinDialog.build("", "1234"));
        assertNull(ManagerPinDialog.build("M01", null));
        assertNull(ManagerPinDialog.build("M01", "  "));
        ManagerPinDialog.Credentials c = ManagerPinDialog.build(" M01 ", " 1234 ");
        assertNotNull(c);
        assertEquals("M01", c.cashierCode());
        assertEquals("1234", c.pin());
    }
}
