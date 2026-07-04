package com.company.pos.menu.api;

public record CreateModifierGroupCommand(String name, int minSelections, int maxSelections) {
}
