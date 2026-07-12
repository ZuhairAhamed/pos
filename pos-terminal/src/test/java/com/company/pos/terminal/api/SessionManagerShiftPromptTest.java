package com.company.pos.terminal.api;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SessionManagerShiftPromptTest {

    @Test
    void dismissalIsSessionScopedAndClearedOnSignOut() {
        SessionManager session = new SessionManager();
        assertFalse(session.shiftPromptDismissed());
        session.dismissShiftPrompt();
        assertTrue(session.shiftPromptDismissed());
        session.clear();
        assertFalse(session.shiftPromptDismissed(), "sign-out must reset the dismissal");
    }
}
