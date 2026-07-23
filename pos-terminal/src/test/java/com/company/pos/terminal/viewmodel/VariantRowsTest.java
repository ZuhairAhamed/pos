package com.company.pos.terminal.viewmodel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class VariantRowsTest {

    @Test
    void memberLabelCombinesSkuAndLabel() {
        assertEquals("BEER-S — Small", VariantRows.memberLabel("BEER-S", "Small"));
    }

    @Test
    void memberLabelNullLabelReturnsBareSkuOnly() {
        assertEquals("BEER-S", VariantRows.memberLabel("BEER-S", null));
        assertEquals("BEER-S", VariantRows.memberLabel("BEER-S", "  "));
    }

    @Test
    void memberLabelNullSkuReturnsEmpty() {
        assertEquals("", VariantRows.memberLabel(null, "Small"));
    }

    @Test
    void statusLabelActiveInactive() {
        assertEquals("Active", VariantRows.statusLabel(true));
        assertEquals("Inactive", VariantRows.statusLabel(false));
    }
}
