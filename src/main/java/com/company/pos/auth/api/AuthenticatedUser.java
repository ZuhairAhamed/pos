package com.company.pos.auth.api;

import java.util.List;

public record AuthenticatedUser(String username, List<String> roles) {
}
