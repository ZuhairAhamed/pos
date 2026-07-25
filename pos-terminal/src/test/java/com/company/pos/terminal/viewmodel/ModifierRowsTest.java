package com.company.pos.terminal.viewmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.company.pos.terminal.api.dto.ProductView;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModifierRowsTest {

    @Test
    void selectionsLabelJoinsMinMax() {
        assertEquals("1–3", ModifierRows.selectionsLabel(1, 3));
    }

    @Test
    void priceDeltaLabelSignsAndScales() {
        assertEquals("+2.50", ModifierRows.priceDeltaLabel(new BigDecimal("2.5")));
        assertEquals("-1.00", ModifierRows.priceDeltaLabel(new BigDecimal("-1")));
        assertEquals("0.00", ModifierRows.priceDeltaLabel(BigDecimal.ZERO));
    }

    @Test
    void skuLabelResolvesName() {
        List<ProductView> products = List.of(
                new ProductView("STEAK", "Ribeye", "Food", "bc", new BigDecimal("80.00"), true, true));
        assertEquals("STEAK — Ribeye", ModifierRows.skuLabel("STEAK", products));
        assertEquals("NOPE", ModifierRows.skuLabel("NOPE", products));
    }
}
