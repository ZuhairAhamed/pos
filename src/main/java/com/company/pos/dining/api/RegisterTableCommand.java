package com.company.pos.dining.api;

/** {@code seats} may be null — the module fills in the configured default. */
public record RegisterTableCommand(String label, Integer seats) {
}
