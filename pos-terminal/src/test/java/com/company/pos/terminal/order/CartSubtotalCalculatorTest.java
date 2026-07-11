package com.company.pos.terminal.order;

import static org.junit.jupiter.api.Assertions.*;

import com.company.pos.terminal.api.dto.CartLineView;
import com.company.pos.terminal.api.dto.CartView;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CartSubtotalCalculatorTest {

    private static CartLineView line(String sku, String qty, String unit) {
        return new CartLineView(UUID.randomUUID(), sku, sku, new BigDecimal(qty), new BigDecimal(unit),
                new BigDecimal(unit), "SAR", List.of());
    }

    @Test
    void sumsUnitPriceTimesQuantityAtScale2() {
        CartView cart = new CartView(UUID.randomUUID(), "OPEN", "SAR", null,
                List.of(line("A", "2", "14.00"), line("B", "1", "8.50")));
        assertEquals(0, new BigDecimal("36.50").compareTo(CartSubtotalCalculator.estimate(cart)));
    }

    @Test
    void emptyOrNullCartIsZero() {
        assertEquals(0, BigDecimal.ZERO.compareTo(CartSubtotalCalculator.estimate(null)));
        CartView empty = new CartView(UUID.randomUUID(), "OPEN", "SAR", null, List.of());
        assertEquals(0, BigDecimal.ZERO.compareTo(CartSubtotalCalculator.estimate(empty)));
    }
}
