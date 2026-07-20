package com.company.pos.dining.api;

/** What kind of dining write triggered a {@link DiningFloorChanged} floor-invalidation ping. */
public enum FloorChangeType {
    TABLE_REGISTERED, TABLE_DEACTIVATED, TABLE_UPDATED, TABLE_REACTIVATED,
    ORDER_OPENED, LINE_ADDED, LINE_UPDATED, LINE_REMOVED, ORDER_FIRED,
    ORDER_CLOSED, ORDER_SPLIT_CLOSED, ORDER_TRANSFERRED, ORDER_MERGED, ORDER_VOIDED
}
