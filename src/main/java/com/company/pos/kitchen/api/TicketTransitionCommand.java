package com.company.pos.kitchen.api;

public record TicketTransitionCommand(KitchenTicketState expectedState) {
}
