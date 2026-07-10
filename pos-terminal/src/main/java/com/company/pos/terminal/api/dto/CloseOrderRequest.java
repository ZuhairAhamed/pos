package com.company.pos.terminal.api.dto;

import java.util.List;
import java.util.Map;

/**
 * Body for {@code POST /dining/orders/{id}/close}. Mirrors the server's {@code CloseOrderCommand}
 * (field order: tenders, lineDiscounts, transactionDiscount, waiveServiceCharge). For this slice
 * {@code lineDiscounts} is an empty map and {@code transactionDiscount} is null.
 */
public record CloseOrderRequest(List<TenderInput> tenders, Map<String, Object> lineDiscounts,
        Object transactionDiscount, boolean waiveServiceCharge) {
}
