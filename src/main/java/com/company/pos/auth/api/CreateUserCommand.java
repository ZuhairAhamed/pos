package com.company.pos.auth.api;

import java.util.Set;

/** Request to create a staff user. Password required; cashierCode + pin optional
 *  (needed only for PIN login and manager-PIN approval at the terminal). */
public record CreateUserCommand(String username, String displayName, String password,
        Set<Role> roles, String cashierCode, String pin) {}
