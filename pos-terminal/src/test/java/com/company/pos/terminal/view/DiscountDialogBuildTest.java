package com.company.pos.terminal.view;

import static org.junit.jupiter.api.Assertions.*;

import com.company.pos.terminal.api.dto.DiscountInput;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Validation rule for the discount modal's Apply button (mirrors server-side constraints). */
class DiscountDialogBuildTest {

    @Test
    void validPercentBuilds() {
        DiscountInput d = DiscountDialog.build("PERCENT", "10", "LOYALTY");
        assertNotNull(d);
        assertEquals("PERCENT", d.type());
        assertEquals(0, new BigDecimal("10").compareTo(d.value()));
        assertEquals("LOYALTY", d.reasonCode());
    }

    @Test
    void validAmountBuilds() {
        DiscountInput d = DiscountDialog.build("AMOUNT", "5.50", "PRICE_MATCH");
        assertNotNull(d);
        assertEquals("AMOUNT", d.type());
        assertEquals(0, new BigDecimal("5.50").compareTo(d.value()));
    }

    @Test
    void garbageZeroAndNegativeValuesRejected() {
        assertNull(DiscountDialog.build("PERCENT", "abc", "LOYALTY"));
        assertNull(DiscountDialog.build("PERCENT", "", "LOYALTY"));
        assertNull(DiscountDialog.build("PERCENT", null, "LOYALTY"));
        assertNull(DiscountDialog.build("PERCENT", "0", "LOYALTY"));
        assertNull(DiscountDialog.build("AMOUNT", "-5", "LOYALTY"));
    }

    @Test
    void percentOverOneHundredRejectedButAmountIsNot() {
        assertNull(DiscountDialog.build("PERCENT", "101", "LOYALTY"));
        assertNotNull(DiscountDialog.build("AMOUNT", "101", "LOYALTY")); // server caps at base
    }

    @Test
    void missingReasonRejected() {
        assertNull(DiscountDialog.build("PERCENT", "10", null));
        assertNull(DiscountDialog.build("PERCENT", "10", " "));
    }
}
