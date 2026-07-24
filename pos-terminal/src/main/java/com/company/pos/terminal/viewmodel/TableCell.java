package com.company.pos.terminal.viewmodel;

import java.util.UUID;

/**
 * Immutable snapshot of one dining table on the table map. {@code state} is derived from the open
 * order (if any): FREE (no open order), SEATED (open order, no items yet), ACTIVE (open order with
 * items). {@code openMinutes} is how long the occupying order has been open at snapshot time (0
 * when free); {@code attention} is true when that exceeds the configured dwell threshold.
 * {@code orderId} is the OPEN order's id, or {@code null} when {@code state == FREE}.
 */
public record TableCell(UUID tableId, String label, TableState state, int openMinutes,
        boolean attention, UUID orderId, boolean foodReady) {

    public enum TableState { FREE, SEATED, ACTIVE }

    public boolean occupied() {
        return state != TableState.FREE;
    }
}
