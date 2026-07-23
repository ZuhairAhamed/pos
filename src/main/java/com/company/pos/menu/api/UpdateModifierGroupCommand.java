package com.company.pos.menu.api;

public record UpdateModifierGroupCommand(String name, int minSelections, int maxSelections) {
}
