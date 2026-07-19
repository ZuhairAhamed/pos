package com.company.pos.terminal.api;

import java.util.Set;

public record UpdateUserRequest(String displayName, Set<String> roles) {}
