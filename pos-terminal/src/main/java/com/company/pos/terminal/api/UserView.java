package com.company.pos.terminal.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Set;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record UserView(UUID id, String username, String displayName, String cashierCode,
        Set<String> roles, boolean enabled) {}
