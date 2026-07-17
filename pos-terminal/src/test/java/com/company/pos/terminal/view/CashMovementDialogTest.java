package com.company.pos.terminal.view;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class CashMovementDialogTest {

    @Test
    void parsesPositiveDecimal() {
        assertEquals(0, new BigDecimal("25.50").compareTo(CashMovementDialog.parseAmount("25.50")));
    }

    @Test
    void rejectsZeroNegativeBlankAndNonNumeric() {
        assertNull(CashMovementDialog.parseAmount("0"));
        assertNull(CashMovementDialog.parseAmount("-5"));
        assertNull(CashMovementDialog.parseAmount(""));
        assertNull(CashMovementDialog.parseAmount("   "));
        assertNull(CashMovementDialog.parseAmount(null));
        assertNull(CashMovementDialog.parseAmount("abc"));
    }
}
