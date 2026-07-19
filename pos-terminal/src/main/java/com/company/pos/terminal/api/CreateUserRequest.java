package com.company.pos.terminal.api;

import java.util.Set;

public record CreateUserRequest(String username, String displayName, String password,
        Set<String> roles, String cashierCode, String pin) {}
