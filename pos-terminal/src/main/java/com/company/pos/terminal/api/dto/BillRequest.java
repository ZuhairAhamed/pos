package com.company.pos.terminal.api.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** One bill of a by-item split close — mirrors the server's BillInput. The split UI always
 *  sends {@code lineDiscounts = Map.of()} and {@code transactionDiscount = null}. */
public record BillRequest(List<UUID> lineIds, List<TenderInput> tenders,
        Map<String, DiscountInput> lineDiscounts, DiscountInput transactionDiscount) {
}
