package com.company.pos.terminal.api.dto;

import java.util.List;

/** POST /dining/orders/{id}/quote-split body. {@code mode} is "BY_ITEM" (populate {@code bills})
 *  or "EVEN" (populate {@code even}), mirroring the server's SplitMode enum names. */
public record QuoteSplitRequest(String mode, List<QuoteBillInput> bills, QuoteEvenInput even) {
}
