package com.company.pos.terminal.api.dto;

import java.util.List;
import java.util.Map;

/**
 * Body for {@code POST /dining/orders/{id}/close}. Mirrors the server's {@code CloseOrderCommand}
 * (field order: tenders, lineDiscounts, transactionDiscount, waiveServiceCharge). As of slice 5
 * the transaction discount is typed; lineDiscounts stays empty.
 */
public record CloseOrderRequest(List<TenderInput> tenders, Map<String, DiscountInput> lineDiscounts,
        DiscountInput transactionDiscount, boolean waiveServiceCharge) {
}
