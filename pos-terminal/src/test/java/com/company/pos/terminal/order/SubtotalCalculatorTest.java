package com.company.pos.terminal.order;

import com.company.pos.terminal.api.dto.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class SubtotalCalculatorTest {

    private MenuCache cacheWith(String sku, String cat, String price) {
        return new MenuCache(List.of(new ProductView(sku, sku, cat, new BigDecimal(price))));
    }

    private OrderView orderWith(OrderLineView... lines) {
        return new OrderView(UUID.randomUUID(), UUID.randomUUID(), "DINE_IN", "OPEN", null,
                null, null, null, List.of(lines));
    }

    @Test
    void estimatesBasePriceTimesQty() {
        MenuCache cache = cacheWith("BURGER", "Mains", "25.00");
        OrderView order = orderWith(new OrderLineView(UUID.randomUUID(), "BURGER", new BigDecimal("2"),
                null, "MAIN", null, List.of()));
        assertEquals(0, new BigDecimal("50.00").compareTo(SubtotalCalculator.estimate(order, cache)));
    }

    @Test
    void addsModifierDeltas() {
        MenuCache cache = cacheWith("STEAK", "Mains", "40.00");
        OrderLineModifierView addOn = new OrderLineModifierView(UUID.randomUUID(), "Extra cheese", new BigDecimal("2.00"));
        OrderView order = orderWith(new OrderLineView(UUID.randomUUID(), "STEAK", new BigDecimal("1"),
                null, "MAIN", null, List.of(addOn)));
        assertEquals(0, new BigDecimal("42.00").compareTo(SubtotalCalculator.estimate(order, cache)));
    }

    @Test
    void unknownSkuContributesZeroBase() {
        MenuCache cache = cacheWith("BURGER", "Mains", "25.00");
        OrderView order = orderWith(new OrderLineView(UUID.randomUUID(), "GHOST", new BigDecimal("3"),
                null, "MAIN", null, List.of()));
        assertEquals(0, BigDecimal.ZERO.compareTo(SubtotalCalculator.estimate(order, cache)));
    }

    @Test
    void emptyOrderEstimatesZero() {
        MenuCache cache = cacheWith("BURGER", "Mains", "25.00");
        OrderView order = orderWith();
        assertEquals(0, new BigDecimal("0.00").compareTo(SubtotalCalculator.estimate(order, cache)));
        assertEquals(2, SubtotalCalculator.estimate(order, cache).scale());
    }

    @Test
    void modifierDeltaAppliesPerUnitAcrossQty() {
        MenuCache cache = cacheWith("STEAK", "Mains", "40.00");
        OrderLineModifierView addOn = new OrderLineModifierView(UUID.randomUUID(), "Extra cheese", new BigDecimal("2.00"));
        OrderView order = orderWith(new OrderLineView(UUID.randomUUID(), "STEAK", new BigDecimal("3"),
                null, "MAIN", null, List.of(addOn)));
        // (40.00 + 2.00) * 3 = 126.00
        assertEquals(0, new BigDecimal("126.00").compareTo(SubtotalCalculator.estimate(order, cache)));
    }

    @Test
    void categoriesAreDistinctInOrderWithOtherFallback() {
        MenuCache cache = new MenuCache(List.of(
                new ProductView("A", "A", "Mains", new BigDecimal("1")),
                new ProductView("B", "B", "Mains", new BigDecimal("1")),
                new ProductView("C", "C", null, new BigDecimal("1"))));
        assertEquals(List.of("Mains", "Other"), cache.categories());
        assertEquals(2, cache.productsInCategory("Mains").size());
    }

    @Test
    void blankCategoryMapsToOther() {
        MenuCache cache = new MenuCache(List.of(
                new ProductView("A", "A", "  ", new BigDecimal("1"))));
        assertEquals(List.of("Other"), cache.categories());
        assertEquals(1, cache.productsInCategory("Other").size());
    }

    @Test
    void unknownCategoryReturnsEmptyList() {
        MenuCache cache = cacheWith("BURGER", "Mains", "25.00");
        assertTrue(cache.productsInCategory("Desserts").isEmpty());
    }

    @Test
    void basePriceOfUnknownSkuIsZero() {
        MenuCache cache = cacheWith("BURGER", "Mains", "25.00");
        assertEquals(0, BigDecimal.ZERO.compareTo(cache.basePrice("NOPE")));
    }
}
