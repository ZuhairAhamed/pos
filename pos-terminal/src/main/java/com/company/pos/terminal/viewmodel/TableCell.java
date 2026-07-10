package com.company.pos.terminal.viewmodel;

import java.util.UUID;

/**
 * Immutable view of a single dining table on the table map. {@code orderId} is the id of the OPEN
 * order occupying the table, or {@code null} when the table is free ({@code occupied == false}).
 */
public record TableCell(UUID tableId, String label, boolean occupied, UUID orderId) {
}
