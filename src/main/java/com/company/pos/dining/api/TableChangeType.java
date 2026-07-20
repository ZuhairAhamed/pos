package com.company.pos.dining.api;

/** The kind of table-registry write that produced a {@link TableChanged} event. */
public enum TableChangeType {
    CREATED, UPDATED, DEACTIVATED, REACTIVATED
}
