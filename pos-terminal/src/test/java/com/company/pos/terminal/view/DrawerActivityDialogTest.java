package com.company.pos.terminal.view;

import static org.junit.jupiter.api.Assertions.*;

import com.company.pos.terminal.api.dto.DrawerReconciliation;
import com.company.pos.terminal.view.DrawerActivityDialog.Row;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DrawerActivityDialogTest {

    /** Distinct known values so the blind-safety assertions are meaningful. */
    private DrawerReconciliation activity() {
        return new DrawerReconciliation(UUID.randomUUID(),
                new BigDecimal("500.00"),   // opening float  (shown)
                new BigDecimal("1234.00"),  // cash sales AMOUNT (must NOT be shown)
                37,                          // cash sales count (shown)
                new BigDecimal("100.00"),   // pay-ins (shown)
                new BigDecimal("50.00"),    // pay-outs (shown)
                new BigDecimal("1784.00"),  // expectedCash (must NOT be shown)
                null, null, "SAR");
    }

    @Test
    void showsExactlyTheFourBlindSafeRows() {
        List<Row> rows = DrawerActivityDialog.activityRows(activity());
        assertEquals(4, rows.size());
        assertEquals(List.of("Opening float", "Cash sales", "Pay-ins", "Pay-outs"),
                rows.stream().map(Row::label).toList());
    }

    @Test
    void cashSalesRowShowsCountNotAmount() {
        List<Row> rows = DrawerActivityDialog.activityRows(activity());
        Row cashSales = rows.stream().filter(r -> r.label().equals("Cash sales")).findFirst().get();
        assertTrue(cashSales.value().contains("37"), "cash-sales row shows the count");
        assertFalse(cashSales.value().contains("1234"), "cash-sales AMOUNT must not appear");
    }

    @Test
    void neverExposesExpectedOrCashSalesAmountOrVariance() {
        List<Row> rows = DrawerActivityDialog.activityRows(activity());
        for (Row r : rows) {
            assertFalse(r.value().contains("1234"), "cash-sales amount leaked in " + r.label());
            assertFalse(r.value().contains("1784"), "expected cash leaked in " + r.label());
        }
    }
}
