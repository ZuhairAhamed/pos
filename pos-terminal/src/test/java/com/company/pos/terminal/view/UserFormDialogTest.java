package com.company.pos.terminal.view;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class UserFormDialogTest {

    @Test
    void validWhenAllRequiredPresent() {
        assertTrue(UserFormDialog.isValidCreate("alice", "Alice", "pw", Set.of("CASHIER")));
    }

    @Test
    void invalidWhenUsernameBlank() {
        assertFalse(UserFormDialog.isValidCreate("  ", "Alice", "pw", Set.of("CASHIER")));
    }

    @Test
    void invalidWhenPasswordBlank() {
        assertFalse(UserFormDialog.isValidCreate("alice", "Alice", "", Set.of("CASHIER")));
    }

    @Test
    void invalidWhenNoRolesSelected() {
        assertFalse(UserFormDialog.isValidCreate("alice", "Alice", "pw", Set.of()));
    }

    @Test
    void invalidWhenDisplayNameBlank() {
        assertFalse(UserFormDialog.isValidCreate("alice", " ", "pw", Set.of("CASHIER")));
    }
}
