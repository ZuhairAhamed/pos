package com.company.pos.terminal.api.dto;

import java.util.List;

/**
 * Body for {@code POST /dining/orders/{id}/close-split}. Mirrors the server's SplitCloseCommand
 * (field order: mode, bills, even, waiveServiceCharge). The split UI never sends discounts or a
 * waiver.
 */
public record SplitCloseRequest(String mode, List<BillRequest> bills, EvenSplitRequest even,
        boolean waiveServiceCharge) {
}
