package com.company.pos.terminal.order;

import static org.junit.jupiter.api.Assertions.*;

import com.company.pos.terminal.api.dto.ProductView;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class MenuCacheTest {

    private static ProductView p(String sku, String cat, String barcode, String price) {
        return new ProductView(sku, sku + " name", cat, barcode, new BigDecimal(price));
    }

    @Test
    void skuForBarcodeResolvesKnownBarcode() {
        MenuCache cache = new MenuCache(List.of(
                p("LATTE", "Drinks", "6291041500213", "12.00"),
                p("FRIES", "Sides", "6291041500299", "8.00")));
        assertEquals("FRIES", cache.skuForBarcode("6291041500299"));
    }

    @Test
    void skuForBarcodeReturnsNullForUnknownOrBlank() {
        MenuCache cache = new MenuCache(List.of(p("LATTE", "Drinks", "6291041500213", "12.00")));
        assertNull(cache.skuForBarcode("0000000000000"));
        assertNull(cache.skuForBarcode(""));
        assertNull(cache.skuForBarcode(null));
    }

    @Test
    void productsWithNullBarcodeAreIndexedByCategoryButNotBarcode() {
        MenuCache cache = new MenuCache(List.of(p("LATTE", "Drinks", null, "12.00")));
        assertEquals(1, cache.productsInCategory("Drinks").size());
        assertNull(cache.skuForBarcode("anything"));
    }
}
