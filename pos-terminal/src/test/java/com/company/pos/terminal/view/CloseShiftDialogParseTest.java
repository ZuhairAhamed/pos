package com.company.pos.terminal.view;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class CloseShiftDialogParseTest {

    @Test
    void blankParsesToZero() {
        assertEquals(0, BigDecimal.ZERO.compareTo(CloseShiftDialog.parse("")));
        assertEquals(0, BigDecimal.ZERO.compareTo(CloseShiftDialog.parse(null)));
    }

    @Test
    void validAmountParses() {
        assertEquals(0, new BigDecimal("1640.00").compareTo(CloseShiftDialog.parse(" 1640.00 ")));
    }

    @Test
    void negativeIsRejected() {
        assertNull(CloseShiftDialog.parse("-5"));
    }

    @Test
    void nonNumericIsRejected() {
        assertNull(CloseShiftDialog.parse("abc"));
    }
}
