package com.company.pos.auth.api;

import java.util.Set;

/** Request to edit a user's display name and roles. Username is immutable; credentials
 *  are changed via the dedicated reset endpoints. */
public record UpdateUserCommand(String displayName, Set<Role> roles) {}
