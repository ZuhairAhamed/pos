package com.company.pos.auth.api;

/** New credential value: a new password, or a new PIN (blank clears the PIN). */
public record ResetCredentialRequest(String value) {}
