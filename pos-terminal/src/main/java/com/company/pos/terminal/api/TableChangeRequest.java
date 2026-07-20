package com.company.pos.terminal.api;

/** Outbound body for create (POST /dining/tables) and update (PUT /dining/tables/{id}). */
public record TableChangeRequest(String label, Integer seats) {
}
