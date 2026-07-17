package com.company.pos.terminal.view;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ShiftResultDialogTest {

    @Test
    void balancedWhenZero() {
        assertEquals("Balanced", ShiftResultDialog.varianceText(new BigDecimal("0.00"), "SAR"));
        assertEquals("variance-balanced", ShiftResultDialog.varianceStyle(new BigDecimal("0.00")));
    }

    @Test
    void overWhenPositive() {
        assertEquals("Over 10.00 SAR", ShiftResultDialog.varianceText(new BigDecimal("10.00"), "SAR"));
        assertEquals("variance-over", ShiftResultDialog.varianceStyle(new BigDecimal("10.00")));
    }

    @Test
    void shortWhenNegative() {
        assertEquals("Short 10.00 SAR", ShiftResultDialog.varianceText(new BigDecimal("-10.00"), "SAR"));
        assertEquals("variance-short", ShiftResultDialog.varianceStyle(new BigDecimal("-10.00")));
    }

    @Test
    void nullVarianceIsBalanced() {
        assertEquals("Balanced", ShiftResultDialog.varianceText(null, "SAR"));
        assertEquals("variance-balanced", ShiftResultDialog.varianceStyle(null));
    }
}
