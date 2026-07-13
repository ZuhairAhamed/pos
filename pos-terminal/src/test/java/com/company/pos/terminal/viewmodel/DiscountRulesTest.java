package com.company.pos.terminal.viewmodel;

import static org.junit.jupiter.api.Assertions.*;

import com.company.pos.terminal.api.dto.DiscountInput;
import com.company.pos.terminal.api.dto.DiscountPolicyView;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Mirrors the server's DiscountCalculator cap check (amount > maxAmount OR pct > maxPercent). */
class DiscountRulesTest {

    private static final DiscountPolicyView POLICY = new DiscountPolicyView(
            new BigDecimal("10"), new BigDecimal("20.00"),
            List.of("DAMAGED", "PRICE_MATCH", "LOYALTY", "MANAGER_COMP"));
    private static final BigDecimal BASE = new BigDecimal("60.00");

    @Test
    void percentExactlyAtCapNeedsNoApproval() {
        // 10% of 60.00 = 6.00: pct == maxPercent, amount <= maxAmount — the cap is exceeded, not met.
        assertFalse(DiscountRules.needsApproval(
                new DiscountInput("PERCENT", new BigDecimal("10"), "LOYALTY"), BASE, POLICY, false));
    }

    @Test
    void percentJustOverCapNeedsApproval() {
        assertTrue(DiscountRules.needsApproval(
                new DiscountInput("PERCENT", new BigDecimal("11"), "LOYALTY"), BASE, POLICY, false));
    }

    @Test
    void amountOverAbsoluteCapNeedsApproval() {
        // 25.00 > maxAmount 20.00 even though 25/60 = 41.67% is also over — either trips it.
        assertTrue(DiscountRules.needsApproval(
                new DiscountInput("AMOUNT", new BigDecimal("25.00"), "DAMAGED"), BASE, POLICY, false));
    }

    @Test
    void amountUnderAbsoluteCapCanStillTripThePercentCap() {
        // 10.00 <= 20.00 but 10/60 = 16.67% > 10% — mirrors the server's dual check.
        assertTrue(DiscountRules.needsApproval(
                new DiscountInput("AMOUNT", new BigDecimal("10.00"), "DAMAGED"), BASE, POLICY, false));
    }

    @Test
    void smallAmountNeedsNoApproval() {
        // 5.00 <= 20.00 and 5/60 = 8.33% <= 10%.
        assertFalse(DiscountRules.needsApproval(
                new DiscountInput("AMOUNT", new BigDecimal("5.00"), "DAMAGED"), BASE, POLICY, false));
    }

    @Test
    void managerNeverNeedsApproval() {
        assertFalse(DiscountRules.needsApproval(
                new DiscountInput("PERCENT", new BigDecimal("50"), "MANAGER_COMP"), BASE, POLICY, true));
    }

    @Test
    void nullDiscountOrPolicyNeedsNoApproval() {
        // No policy (fetch failed) -> no local prompt; the server still rejects at checkout and
        // the controller's defensive retry path opens the approval dialog then.
        assertFalse(DiscountRules.needsApproval(null, BASE, POLICY, false));
        assertFalse(DiscountRules.needsApproval(
                new DiscountInput("PERCENT", new BigDecimal("50"), "LOYALTY"), BASE, null, false));
    }

    @Test
    void amountOfResolvesPercentAndCapsAmountAtBase() {
        assertEquals(0, new BigDecimal("6.00").compareTo(DiscountRules.amountOf(
                new DiscountInput("PERCENT", new BigDecimal("10"), "LOYALTY"), BASE)));
        assertEquals(0, new BigDecimal("60.00").compareTo(DiscountRules.amountOf(
                new DiscountInput("AMOUNT", new BigDecimal("99.00"), "LOYALTY"), BASE)));
    }
}
