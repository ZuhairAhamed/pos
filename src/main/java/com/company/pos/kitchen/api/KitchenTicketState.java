package com.company.pos.kitchen.api;

/** Prep state of one station's slice of a fired order. */
public enum KitchenTicketState {
    FIRED, PREPARING, READY, BUMPED, CANCELLED
}
