package com.company.pos.terminal.view;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class VariantMemberFormDialogTest {

    @Test
    void validLabelAccepted() {
        assertNull(VariantMemberFormDialog.validate("Small"));
        assertNull(VariantMemberFormDialog.validate("Large (500ml)"));
    }

    @Test
    void blankLabelRejected() {
        assertNotNull(VariantMemberFormDialog.validate("  "));
        assertNotNull(VariantMemberFormDialog.validate(null));
        assertNotNull(VariantMemberFormDialog.validate(""));
    }
}
