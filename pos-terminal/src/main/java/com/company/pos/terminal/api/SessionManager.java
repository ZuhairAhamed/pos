package com.company.pos.terminal.api;

import java.util.Set;

public final class SessionManager {
    private volatile String token;
    private volatile String username;
    private volatile Set<String> roles = Set.of();

    public void setToken(String token) { this.token = token; }
    public String token() { return token; }
    public boolean isAuthenticated() { return token != null; }

    public void setUser(String username, Set<String> roles) {
        this.username = username;
        this.roles = roles == null ? Set.of() : Set.copyOf(roles);
    }

    public String username() { return username; }
    public Set<String> roles() { return roles; }
    public boolean isManager() { return roles.contains("MANAGER") || roles.contains("ADMIN"); }

    public void clear() {
        this.token = null;
        this.username = null;
        this.roles = Set.of();
    }
}
