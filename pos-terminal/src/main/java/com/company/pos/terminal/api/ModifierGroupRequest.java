package com.company.pos.terminal.api;

public record ModifierGroupRequest(String name, int minSelections, int maxSelections) {
}
