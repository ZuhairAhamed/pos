package com.company.pos.terminal.view;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class StartShiftDialogParseTest {

    @Test
    void blankParsesToZero() {
        assertEquals(BigDecimal.ZERO, StartShiftDialog.parse(""));
        assertEquals(BigDecimal.ZERO, StartShiftDialog.parse(null));
        assertEquals(BigDecimal.ZERO, StartShiftDialog.parse("   "));
    }

    @Test
    void plainDecimalParses() {
        assertEquals(new BigDecimal("500"), StartShiftDialog.parse("500"));
        assertEquals(new BigDecimal("12.50"), StartShiftDialog.parse(" 12.50 "));
    }

    @Test
    void negativeIsInvalid() {
        assertNull(StartShiftDialog.parse("-1"));
    }

    @Test
    void garbageIsInvalid() {
        assertNull(StartShiftDialog.parse("abc"));
        assertNull(StartShiftDialog.parse("12..5"));
    }
}
