package com.company.pos.common.util;

import java.util.UUID;

public final class Identifiers {

    private Identifiers() {
    }

    public static UUID newId() {
        return UUID.randomUUID();
    }
}
