package com.company.pos.dining.api;

/** Edit a table's label and seat count. Both required (seats must be a positive number). */
public record UpdateTableCommand(String label, Integer seats) {
}
