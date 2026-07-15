package com.company.pos.terminal.viewmodel;

import java.util.UUID;

/**
 * Immutable snapshot of one open takeaway (QUICK_SERVICE) order for the takeaway list.
 * {@code openMinutes} is how long it has been open at snapshot time; {@code attention} is true
 * when that exceeds the configured dwell threshold.
 */
public record TakeawayRow(UUID orderId, String label, int openMinutes, int lineCount,
        boolean attention) {
}
