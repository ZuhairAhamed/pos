package com.company.pos.auth.api;

import java.util.Set;
import java.util.UUID;

/** Read model for a staff user. Never exposes password or PIN hashes. */
public record UserView(UUID id, String username, String displayName, String cashierCode,
        Set<Role> roles, boolean enabled) {}
