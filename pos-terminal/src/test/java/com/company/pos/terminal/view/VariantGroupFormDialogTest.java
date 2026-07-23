package com.company.pos.terminal.view;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class VariantGroupFormDialogTest {

    @Test
    void validNameAccepted() {
        assertNull(VariantGroupFormDialog.validate("Sizes"));
        assertNull(VariantGroupFormDialog.validate("Beer sizes"));
    }

    @Test
    void blankNameRejected() {
        assertNotNull(VariantGroupFormDialog.validate("  "));
        assertNotNull(VariantGroupFormDialog.validate(null));
        assertNotNull(VariantGroupFormDialog.validate(""));
    }
}
