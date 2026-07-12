package com.company.pos.terminal.api.dto;

import java.util.Set;

/** One-shot elevation result: a manager's JWT used for a single call, never stored in the session. */
public record ManagerAuth(String token, String username, Set<String> roles) {
    public boolean isManager() {
        return roles != null && (roles.contains("MANAGER") || roles.contains("ADMIN"));
    }
}
