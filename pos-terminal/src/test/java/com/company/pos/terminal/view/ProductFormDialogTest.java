package com.company.pos.terminal.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ProductFormDialogTest {

    @Test
    void validWhenSkuNamePricePresent() {
        assertTrue(ProductFormDialog.isValidCreate("COLA", "Cola", "5.00"));
    }

    @Test
    void invalidWhenSkuBlank() {
        assertFalse(ProductFormDialog.isValidCreate("  ", "Cola", "5.00"));
    }

    @Test
    void invalidWhenNameBlank() {
        assertFalse(ProductFormDialog.isValidCreate("COLA", " ", "5.00"));
    }

    @Test
    void invalidWhenPriceNegative() {
        assertFalse(ProductFormDialog.isValidCreate("COLA", "Cola", "-1"));
    }

    @Test
    void invalidWhenPriceNotNumeric() {
        assertFalse(ProductFormDialog.isValidCreate("COLA", "Cola", "abc"));
    }

    @Test
    void parsePriceReturnsNullForBlank() {
        assertNull(ProductFormDialog.parsePrice("  "));
    }

    @Test
    void parsePriceParsesDecimal() {
        assertEquals(new BigDecimal("6.50"), ProductFormDialog.parsePrice(" 6.50 "));
    }
}
